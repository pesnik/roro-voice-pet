"""whisper.cpp `whisper-server` subprocess manager + Pipecat STT service.

Mirrors llama_client.py's shape (binary discovery, spawn, health poll,
stop) for the same reason: a native binary is the only way to get local
Whisper transcription without pulling torch into this dependency-light
gateway — Pipecat's own built-in Whisper STT service transitively requires
torch on Apple Silicon (its whisper module unconditionally imports
mlx_whisper there), which this gateway deliberately avoids.

Unlike llama.cpp, whisper.cpp does not publish a prebuilt macOS binary via
GitHub Releases (only Linux/Windows) — `_candidate_binary_paths()` treats
`whisper-server` on PATH (e.g. `brew install whisper-cpp`) as a primary
lookup on macOS, not just a last-resort fallback like llama_client.py's
PATH check.
"""

from __future__ import annotations

import asyncio
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import AsyncGenerator, Optional

import httpx
from pipecat.frames.frames import ErrorFrame, Frame, TranscriptionFrame
from pipecat.services.stt_service import SegmentedSTTService
from pipecat.utils.time import time_now_iso8601

from .lifecycle import (
    cleanup_stale_llama_server,
    clear_pid_file,
    pdeathsig_preexec,
    write_pid_file,
)
from .llama_client import _find_free_port, _platform_triple
from .log_setup import get_logger
from .voice_assets import WHISPER_MODEL_FILENAME, download_one

# Re-exported for callers/tests that predate voice_assets.py.
DEFAULT_MODEL_FILENAME = WHISPER_MODEL_FILENAME


def _candidate_binary_paths() -> list[Path]:
    """Where to look for whisper-server, in priority order.

    1. $MINICPM_WHISPER_SERVER  (explicit override, dev convenience)
    2. Next to ourselves       — packaged sidecar's sidecar-bin/
    3. sidecar/bin/<os>-<arch>/ — fetched release output (Linux/Windows)
    4. whisper-server on PATH   — primary on macOS today (`brew install
       whisper-cpp`), since whisper.cpp has no prebuilt macOS GitHub
       Release asset the way llama.cpp does.
    """
    sys_name = platform.system()
    exe = "whisper-server.exe" if sys_name == "Windows" else "whisper-server"
    override = os.environ.get("MINICPM_WHISPER_SERVER")
    out: list[Path] = []
    if override:
        out.append(Path(override).expanduser())

    if getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
        out.append(base / exe)
    else:
        pkg_root = Path(__file__).resolve().parent.parent
        triple = _platform_triple()
        out.append(pkg_root / "bin" / triple / exe)

    which = shutil.which(exe)
    if which:
        out.append(Path(which))

    seen: set[Path] = set()
    unique: list[Path] = []
    for p in out:
        rp = p.resolve() if p.exists() else p
        if rp in seen:
            continue
        seen.add(rp)
        unique.append(p)
    return unique


def ensure_whisper_model(models_dir: Path, *, filename: str = DEFAULT_MODEL_FILENAME) -> Path:
    """Lazily download the default ggml Whisper model on first use.

    Synchronous fallback for WhisperCppServer.start() when a call begins
    without going through the /api/call/prepare progress route first
    (e.g. an older client). Drains voice_assets.download_one's progress
    generator to completion rather than reporting it anywhere — same
    retrying, atomic-write download either way.
    """
    from .voice_assets import WHISPER_MODEL_URL

    target = models_dir / filename
    for _ in download_one(WHISPER_MODEL_URL, target, asset="whisper"):
        pass
    return target


class WhisperCppServer:
    """Subprocess manager for `whisper-server` (mirrors llama_client.LlamaServer)."""

    def __init__(self, *, models_dir: Path, host: str = "127.0.0.1") -> None:
        # Directory, not a fixed file — the model is lazy-downloaded into
        # it on first start() rather than required to exist up front, so
        # constructing this class at gateway boot never blocks on a ~140MB
        # fetch (only the first Call Mode call does).
        self.models_dir = models_dir
        self.model_path: Optional[Path] = None
        self.host = host
        self.port = 0
        self._proc: Optional[subprocess.Popen] = None
        self._client: Optional[httpx.AsyncClient] = None
        self.last_stderr: list[str] = []
        self._pid_file = self._default_pid_file()
        self._lock = threading.Lock()

    @staticmethod
    def _default_pid_file() -> Path:
        from .log_setup import resolve_log_dir

        return resolve_log_dir() / "whisper-server.pid"

    def _resolve_binary(self) -> Path:
        for candidate in _candidate_binary_paths():
            if candidate.is_file():
                return candidate
        raise RuntimeError(
            "whisper-server binary not found. Dev: `brew install whisper-cpp` "
            "(macOS) or run scripts/fetch-whisper-release.sh (Linux/Windows)."
        )

    @property
    def alive(self) -> bool:
        return bool(self._proc and self._proc.poll() is None and self._client is not None)

    async def health(self) -> Optional[dict]:
        if not self._client:
            return None
        try:
            r = await self._client.get("/")
            return {"status": "ok" if r.status_code == 200 else "error"}
        except Exception:
            return None

    async def start(self) -> None:
        log = get_logger()
        if self.alive:
            return
        # Lazy model download happens outside the lock (asyncio.to_thread
        # so the ~140MB first-use fetch doesn't block the event loop) —
        # idempotent (ensure_whisper_model no-ops once the file exists),
        # so no harm if two callers race here.
        model_path = await asyncio.to_thread(ensure_whisper_model, self.models_dir)
        with self._lock:
            if self._proc and self._proc.poll() is None:
                return
            cleanup_stale_llama_server(self._pid_file, expected_name="whisper-server")
            binary = self._resolve_binary()
            self.model_path = model_path
            self.port = _find_free_port(start=18801, end=18830)
            argv = [
                str(binary),
                "--host", self.host,
                "--port", str(self.port),
                "--model", str(self.model_path),
                "--inference-path", "/inference",
            ]
            log.info("spawn whisper-server: %s", " ".join(argv))
            popen_kwargs: dict = {
                "stdout": subprocess.PIPE,
                "stderr": subprocess.PIPE,
                "text": True,
                "bufsize": 1,
            }
            preexec = pdeathsig_preexec()
            if preexec is not None:
                popen_kwargs["preexec_fn"] = preexec
            self._proc = subprocess.Popen(argv, **popen_kwargs)
            write_pid_file(self._pid_file, self._proc.pid)
            self._spawn_tailer(self._proc)
            self._client = httpx.AsyncClient(
                base_url=f"http://{self.host}:{self.port}",
                timeout=httpx.Timeout(connect=5.0, read=30.0, write=30.0, pool=5.0),
            )
        await self._await_ready(timeout=60.0)

    def _spawn_tailer(self, proc: subprocess.Popen) -> None:
        log = get_logger()

        def reader(stream, kind: str) -> None:
            try:
                for line in iter(stream.readline, ""):
                    if not line:
                        break
                    line = line.rstrip()
                    if kind == "stderr":
                        self.last_stderr.append(line)
                        if len(self.last_stderr) > 80:
                            self.last_stderr.pop(0)
                        log.warning("[whisper-server] %s", line)
                    else:
                        log.info("[whisper-server] %s", line)
            finally:
                try:
                    stream.close()
                except Exception:
                    pass

        threading.Thread(target=reader, args=(proc.stdout, "stdout"), daemon=True).start()
        threading.Thread(target=reader, args=(proc.stderr, "stderr"), daemon=True).start()

    async def _await_ready(self, *, timeout: float) -> None:
        log = get_logger()
        deadline = time.monotonic() + timeout
        last_err: Optional[Exception] = None
        while time.monotonic() < deadline:
            if self._proc and self._proc.poll() is not None:
                tail = "\n".join(self.last_stderr[-30:]) or "(no stderr)"
                raise RuntimeError(
                    f"whisper-server exited early code={self._proc.returncode}\n"
                    f"----- stderr tail -----\n{tail}"
                )
            try:
                r = await self._client.get("/")
                if r.status_code < 500:
                    log.info("whisper-server ready on :%d", self.port)
                    return
            except Exception as exc:
                last_err = exc
            await asyncio.sleep(0.4)
        raise TimeoutError(
            f"whisper-server did not become ready in {timeout:.0f}s (last probe error: {last_err})"
        )

    async def stop(self, *, timeout: float = 5.0) -> None:
        log = get_logger()
        with self._lock:
            if self._client is not None:
                try:
                    await self._client.aclose()
                except Exception:
                    pass
                self._client = None
            if self._proc is None:
                return
            if self._proc.poll() is None:
                self._proc.terminate()
                try:
                    self._proc.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    log.warning("whisper-server didn't exit on SIGTERM; killing")
                    self._proc.kill()
                    self._proc.wait(timeout=timeout)
            self._proc = None
            clear_pid_file(self._pid_file)

    async def transcribe(self, wav_bytes: bytes, *, language: Optional[str] = None) -> str:
        if not self._client:
            raise RuntimeError("whisper-server not started")
        files = {"file": ("segment.wav", wav_bytes, "audio/wav")}
        data = {"response_format": "json"}
        if language:
            data["language"] = language
        r = await self._client.post("/inference", files=files, data=data)
        r.raise_for_status()
        payload = r.json()
        return (payload.get("text") or "").strip()


class WhisperCppSTTService(SegmentedSTTService):
    """Pipecat-compatible STT service backed by a local `whisper-server`.

    Drop-in for pipecat.services.whisper.stt.WhisperSTTService, minus the
    torch/mlx_whisper dependency: receives VAD-delimited WAV segments
    (wants_wav_segments defaults to True, matching whisper-server's
    multipart /inference upload) and transcribes them via HTTP.
    """

    def __init__(self, *, server: WhisperCppServer, language: Optional[str] = None, **kwargs):
        super().__init__(**kwargs)
        self._server = server
        self._language = language

    async def start(self, frame) -> None:
        await super().start(frame)
        if not self._server.alive:
            await self._server.start()

    async def run_stt(self, audio: bytes) -> AsyncGenerator[Frame | None, None]:
        try:
            text = await self._server.transcribe(audio, language=self._language)
        except Exception as exc:
            yield ErrorFrame(f"whisper-server transcription failed: {exc}")
            return
        if not text:
            return
        yield TranscriptionFrame(
            text=text,
            user_id="",
            timestamp=time_now_iso8601(),
        )
