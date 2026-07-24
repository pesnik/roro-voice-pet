"""Hermes Agent API client — drop-in replacement for LlamaServer.

Implements the same streaming interface so the gateway (server.py)
can route to either a local llama-server subprocess or the Hermes
Agent cloud API with zero changes to the HTTP layer.

Hermes Agent exposes an OpenAI-compatible
``POST /v1/chat/completions`` endpoint when API_SERVER_ENABLED=true,
so the streaming SSE protocol is identical to what llama-server
already speaks.
"""

from __future__ import annotations

import json
import os
from typing import AsyncIterator, Optional

import httpx

from .log_setup import get_logger

DEFAULT_BASE_URL = "http://127.0.0.1:8642/v1"
DEFAULT_MODEL = "hermes-agent"


class HermesClient:
    """Thin async wrapper around the Hermes Agent chat completions API."""

    def __init__(
        self,
        *,
        api_key: Optional[str] = None,
        base_url: str = DEFAULT_BASE_URL,
        model: Optional[str] = None,
    ) -> None:
        self.api_key = api_key or os.environ.get("HERMES_API_KEY", "change-me-local-dev")
        self.base_url = base_url.rstrip("/")
        self.model = model or os.environ.get("HERMES_MODEL", DEFAULT_MODEL)
        self._client: Optional[httpx.AsyncClient] = None
        # Compatibility stubs — these attributes are accessed by server.py
        # on the unified `server` object. For Hermes they are no-ops.
        self.model_path = None  # type: ignore[assignment]
        self.port = 0
        self.adapter_paths: list = []
        self.last_stderr: list[str] = []

    # ── lifecycle ───────────────────────────────────────────────────────

    async def start(self) -> None:
        if self._client is not None:
            return
        headers: dict[str, str] = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            headers=headers,
            timeout=httpx.Timeout(connect=10.0, read=None, write=30.0, pool=10.0),
        )
        get_logger().info(
            "Hermes client started (model=%s, base_url=%s)",
            self.model,
            self.base_url,
        )

    async def stop(self) -> None:
        if self._client is not None:
            try:
                await self._client.aclose()
            except Exception:
                pass
            self._client = None

    @property
    def alive(self) -> bool:
        return self._client is not None and bool(self.api_key)

    async def health(self) -> Optional[dict]:
        if not self.alive:
            return None
        return {"ok": True, "backend": "hermes", "model": self.model}

    # ── adapter stubs (no-op for Hermes) ─────────────────────────────────

    def adapter_id_for(self, path) -> Optional[int]:
        """LoRA adapters are not supported on Hermes."""
        return None

    async def reload_adapters(self, paths: list) -> None:
        """No-op — LoRA adapters are not supported on Hermes."""
        pass

    # ── model management (no-ops for Hermes) ─────────────────────────────

    async def swap_model(self, model) -> None:
        """Switch the model used for subsequent requests."""
        self.model = str(model)
        get_logger().info("Hermes model switched to %s", self.model)

    async def complete_once(
        self,
        *,
        prompt: str,
        max_tokens: int = 1,
        temperature: float = 0.0,
    ) -> dict:
        """Fire one tiny non-streaming completion. Used by /api/warmup."""
        if not self._client:
            raise RuntimeError("Hermes client not initialised; did you await start()?")
        body = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        }
        r = await self._client.post("/chat/completions", json=body)
        r.raise_for_status()
        data = r.json()
        content = ""
        choices = data.get("choices") or []
        if choices:
            msg = choices[0].get("message") or {}
            content = msg.get("content") or ""
        return {"content": content}

    # ── streaming chat ──────────────────────────────────────────────────

    async def stream_chat(
        self,
        *,
        messages: list[dict],
        max_tokens: int,
        temperature: float,
        top_p: float,
        top_k: int,
        repetition_penalty: float,
        stop: Optional[list[str]] = None,
        enable_thinking: bool = True,
        lora: Optional[list[dict]] = None,
    ) -> AsyncIterator[tuple[str, str]]:
        """Yield ``(kind, text)`` tuples from Hermes's SSE stream.

        Same contract as ``LlamaServer.stream_chat`` so the gateway
        can consume either without branching.
        """
        if not self._client:
            raise RuntimeError("Hermes client not initialised; did you await start()?")

        body: dict = {
            "model": self.model,
            "messages": messages,
            "stream": True,
            "max_tokens": int(max_tokens),
            "temperature": float(temperature),
            "top_p": float(top_p),
        }
        if top_k and top_k > 0:
            body["top_k"] = int(top_k)
        if stop:
            body["stop"] = stop

        async with self._client.stream("POST", "/chat/completions", json=body) as resp:
            if resp.status_code != 200:
                tail = (await resp.aread()).decode("utf-8", "ignore")[:500]
                raise RuntimeError(
                    f"Hermes HTTP {resp.status_code}: {tail}"
                )
            async for raw_line in resp.aiter_lines():
                if not raw_line:
                    continue
                if not raw_line.startswith("data:"):
                    continue
                payload = raw_line[5:].strip()
                if payload == "[DONE]":
                    return
                try:
                    obj = json.loads(payload)
                except Exception:
                    continue
                choices = obj.get("choices") or []
                if not choices:
                    continue
                delta = choices[0].get("delta") or {}
                # Some models on Hermes emit reasoning_content
                reasoning = delta.get("reasoning_content") or delta.get("reasoning")
                if reasoning:
                    yield ("reasoning", reasoning)
                text = delta.get("content")
                if text:
                    yield ("content", text)
                if choices[0].get("finish_reason"):
                    return
