"""Pipecat live-voice ("Call Mode") pipeline.

Mic in -> Whisper STT -> LLM (the same pluggable backend /api/chat already
uses) -> Kokoro TTS -> speaker, with turn-taking and barge-in via Silero VAD.
Connected over a local WebRTC offer/answer route (POST /api/call/offer in
server.py) using Pipecat's SmallWebRTCTransport — a peer-to-peer connection
negotiated over plain HTTP by this same process, no cloud transport involved.

Not a separate service: this module is imported by server.py and reuses its
already-resolved chat-backend config (llama.cpp / openrouter / hermes) so a
live call always talks to whatever backend the user has selected for typed
chat too — see resolve_llm_endpoint() callers in server.py.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Awaitable, Callable, Optional

from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.frames.frames import (
    BotStartedSpeakingFrame,
    BotStoppedSpeakingFrame,
    Frame,
    UserStartedSpeakingFrame,
    UserStoppedSpeakingFrame,
)
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker
from pipecat.processors.aggregators.llm_context import LLMContext
from pipecat.processors.aggregators.llm_response_universal import (
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.runner.types import RunnerArguments
from pipecat.runner.utils import create_transport
from pipecat.services.kokoro.tts import KokoroTTSService
from pipecat.services.openai.llm import OpenAILLMService
from pipecat.transports.base_transport import TransportParams
from pipecat.workers.runner import WorkerRunner

from .clawd_state import ClawdBridge
from .log_setup import get_logger
from .whisper_cpp_stt import WhisperCppServer, WhisperCppSTTService


@dataclass(frozen=True)
class LLMEndpoint:
    """Resolved OpenAI-compatible chat endpoint — the same backend choice
    /api/chat already uses (llama.cpp / openrouter / hermes)."""

    base_url: str
    api_key: str
    model: str


class _PetStateBridgeProcessor(FrameProcessor):
    """Forwards call-mode speaking state to the desk pet via ClawdBridge.

    listening -> "attention", TTS playback -> "working" — reuses the pet's
    existing states (themes only define idle/thinking/working/attention/
    notification/error/sleeping; no new theme art needed for Call Mode).
    """

    def __init__(self, bridge: ClawdBridge, **kwargs):
        super().__init__(**kwargs)
        self._bridge = bridge

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, (UserStartedSpeakingFrame, UserStoppedSpeakingFrame)):
            self._bridge.post("attention", event="CallListening")
        elif isinstance(frame, BotStartedSpeakingFrame):
            self._bridge.post("working", event="CallSpeaking")
        elif isinstance(frame, BotStoppedSpeakingFrame):
            self._bridge.post("attention", event="CallListening")
        await self.push_frame(frame, direction)


def build_call_bot(
    *,
    resolve_llm_endpoint: Callable[[], LLMEndpoint],
    whisper_server: WhisperCppServer,
    bridge: ClawdBridge,
    system_prompt: Optional[str] = None,
) -> Callable[[RunnerArguments], Awaitable[None]]:
    """Return a Pipecat ``bot(runner_args)`` entry point bound to this
    gateway's backend resolution + pet-state bridge.

    ``resolve_llm_endpoint`` is called fresh for every call (not memoized) so
    a just-started llama-server's dynamically-assigned port, or a backend
    switch made in Settings, is picked up on the next call without a gateway
    restart — mirrors how /api/chat re-reads the live ``server`` object on
    every request rather than snapshotting it at boot.

    ``whisper_server`` is shared across calls (started lazily on the first
    one, mirroring how llama-server itself is a single long-lived
    subprocess) rather than spawned fresh per call — model load alone is
    ~150-200ms, not worth paying on every call start.
    """

    async def run_bot(runner_args: RunnerArguments) -> None:
        log = get_logger()
        transport = await create_transport(
            runner_args,
            {
                "webrtc": lambda: TransportParams(
                    audio_in_enabled=True,
                    audio_out_enabled=True,
                ),
            },
        )

        endpoint = resolve_llm_endpoint()
        stt = WhisperCppSTTService(server=whisper_server)
        tts = KokoroTTSService()
        llm = OpenAILLMService(
            base_url=endpoint.base_url,
            api_key=endpoint.api_key,
            model=endpoint.model,
        )

        messages = [{"role": "system", "content": system_prompt}] if system_prompt else []
        context = LLMContext(messages)
        user_aggregator, assistant_aggregator = LLMContextAggregatorPair(
            context,
            user_params=LLMUserAggregatorParams(vad_analyzer=SileroVADAnalyzer()),
        )

        pipeline = Pipeline(
            [
                transport.input(),
                _PetStateBridgeProcessor(bridge),
                stt,
                user_aggregator,
                llm,
                tts,
                transport.output(),
                assistant_aggregator,
            ]
        )

        worker = PipelineWorker(pipeline, params=PipelineParams(enable_metrics=False))

        @transport.event_handler("on_client_disconnected")
        async def on_client_disconnected(_transport, _client):
            log.info("call: client disconnected")
            await worker.stop()

        bridge.new_session()
        bridge.post("attention", event="CallStarted")
        runner = WorkerRunner(handle_sigint=False)
        await runner.add_workers(worker)
        try:
            await runner.run()
        finally:
            bridge.post("idle", event="CallEnded")

    return run_bot
