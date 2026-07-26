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
from pipecat.services.openai.stt import OpenAISTTService
from pipecat.services.openai.tts import OpenAITTSService
from pipecat.transports.base_transport import TransportParams
from pipecat.utils.text.transforms.voice_formatter import VoiceFormatter
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


@dataclass(frozen=True)
class STTConfig:
    """Which speech-to-text engine a call should use.

    "local" (default) is whisper.cpp, fully offline. "openai" is any
    endpoint speaking the OpenAI /v1/audio/transcriptions schema — OpenAI
    itself, or a compatible self-hosted/third-party service (NOT
    OpenRouter, which only proxies chat completions and has no audio
    API). base_url/model empty string means "use the service's own
    default" (api.openai.com, whisper-1).
    """

    backend: str = "local"
    api_key: str = ""
    base_url: str = ""
    model: str = ""


@dataclass(frozen=True)
class TTSConfig:
    """Which text-to-speech engine a call should use — same shape as
    STTConfig. "local" is Kokoro. "openai" is any /v1/audio/speech
    -compatible endpoint."""

    backend: str = "local"
    api_key: str = ""
    base_url: str = ""
    model: str = ""
    voice: str = ""


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


def _build_stt(cfg: STTConfig, whisper_server: WhisperCppServer):
    if cfg.backend == "openai":
        return OpenAISTTService(
            api_key=cfg.api_key or None,
            base_url=cfg.base_url or None,
            settings=OpenAISTTService.Settings(model=cfg.model) if cfg.model else None,
        )
    return WhisperCppSTTService(server=whisper_server)


# Kokoro has no built-in default — KokoroTTSService raises "Kokoro TTS
# voice must be specified" at first synthesis if none is set. af_heart is
# one of Kokoro's standard bundled voices (natural-sounding American
# female); TTS_VOICE overrides it same as it does for the openai backend.
DEFAULT_KOKORO_VOICE = "af_heart"

# Matches emoji + the invisible modifiers (variation selector, zero-width
# joiner) used to combine them — neither Kokoro nor OpenAI's TTS has any
# sensible way to speak these, so hearing "asterisk asterisk" or a spelled-
# out emoji description read aloud is jarring in a voice call in a way it
# never was for typed chat. Collapsing whitespace after stripping avoids
# the double-space an emoji mid-sentence would otherwise leave behind.
_EMOJI_PATTERN = (
    r"[\U0001F1E6-\U0001F1FF\U0001F300-\U0001FAFF"
    r"☀-➿⬀-⯿️‍]+"
)

# Strips Markdown (**bold**, headers, code spans, …) and emoji before
# synthesis, plus normalizes numbers/currency/units/acronyms/dates so
# they're read out naturally instead of literally — pipecat's own
# TTS-preprocessing bundle, shared by both the local and OpenAI-compatible
# backends below rather than reimplemented per-backend.
_VOICE_FORMATTER = VoiceFormatter(
    custom_replacements=[
        (_EMOJI_PATTERN, " "),
        (r"[ \t]{2,}", " "),
    ],
)


def _build_tts(cfg: TTSConfig):
    if cfg.backend == "openai":
        return OpenAITTSService(
            api_key=cfg.api_key or None,
            base_url=cfg.base_url or None,
            settings=OpenAITTSService.Settings(model=cfg.model, voice=cfg.voice)
            if (cfg.model or cfg.voice)
            else None,
            text_transforms=[("*", _VOICE_FORMATTER)],
        )
    return KokoroTTSService(
        settings=KokoroTTSService.Settings(voice=cfg.voice or DEFAULT_KOKORO_VOICE),
        text_transforms=[("*", _VOICE_FORMATTER)],
    )


# The _VOICE_FORMATTER text filter above is a deterministic safety net —
# it strips whatever markdown/emoji the model writes regardless of
# instructions. This prompt is the other half: asking the model to not
# write that way in the first place, since stripped-after-the-fact
# markdown *structure* (headers, bullet lists) still reads awkwardly even
# once the symbols are gone. Only used when the caller doesn't supply
# their own system_prompt.
DEFAULT_CALL_SYSTEM_PROMPT = (
    "You are having a live spoken phone conversation, not writing a chat "
    "message. Reply the way a person would speak out loud: plain "
    "conversational sentences only. Never use Markdown formatting "
    "(no **bold**, *italics*, headers, bullet/numbered lists, or code "
    "blocks) and never use emoji — none of it can be spoken. Keep replies "
    "short and natural, like one side of a real conversation."
)


def build_call_bot(
    *,
    resolve_llm_endpoint: Callable[[], LLMEndpoint],
    resolve_stt_config: Callable[[], STTConfig],
    resolve_tts_config: Callable[[], TTSConfig],
    whisper_server: WhisperCppServer,
    bridge: ClawdBridge,
    system_prompt: Optional[str] = None,
) -> Callable[[RunnerArguments], Awaitable[None]]:
    """Return a Pipecat ``bot(runner_args)`` entry point bound to this
    gateway's backend resolution + pet-state bridge.

    ``resolve_llm_endpoint`` / ``resolve_stt_config`` / ``resolve_tts_config``
    are all called fresh for every call (not memoized) so a just-started
    llama-server's dynamically-assigned port, or a backend switch made in
    Settings, is picked up on the next call without a gateway restart —
    mirrors how /api/chat re-reads the live ``server`` object on every
    request rather than snapshotting it at boot.

    ``whisper_server`` is shared across calls (started lazily on the first
    one, mirroring how llama-server itself is a single long-lived
    subprocess) rather than spawned fresh per call — model load alone is
    ~150-200ms, not worth paying on every call start. Only actually used
    when the resolved STT backend is "local"; harmless to always pass in
    otherwise since it stays unstarted.
    """

    async def run_bot(runner_args: RunnerArguments) -> None:
        log = get_logger()
        log.info("call: pipeline build starting")
        transport = await create_transport(
            runner_args,
            {
                "webrtc": lambda: TransportParams(
                    audio_in_enabled=True,
                    audio_out_enabled=True,
                ),
            },
        )

        stt_cfg = resolve_stt_config()
        tts_cfg = resolve_tts_config()
        endpoint = resolve_llm_endpoint()
        stt = _build_stt(stt_cfg, whisper_server)
        tts = _build_tts(tts_cfg)
        llm = OpenAILLMService(
            base_url=endpoint.base_url,
            api_key=endpoint.api_key,
            model=endpoint.model,
        )
        log.info(
            "call: services built (stt=%s tts=%s llm=%s)",
            stt_cfg.backend, tts_cfg.backend, endpoint.model,
        )

        effective_system_prompt = system_prompt or DEFAULT_CALL_SYSTEM_PROMPT
        messages = [{"role": "system", "content": effective_system_prompt}]
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

        @transport.event_handler("on_client_connected")
        async def on_client_connected(_transport, _client):
            log.info("call: WebRTC peer connection established")

        @transport.event_handler("on_client_disconnected")
        async def on_client_disconnected(_transport, _client):
            log.info("call: client disconnected")
            await worker.stop()

        bridge.new_session()
        bridge.post("attention", event="CallStarted")
        runner = WorkerRunner(handle_sigint=False)
        await runner.add_workers(worker)
        log.info("call: pipeline running, awaiting connection")
        try:
            await runner.run()
        except Exception:
            log.exception("call: pipeline crashed")
            raise
        finally:
            log.info("call: pipeline ended")
            bridge.post("idle", event="CallEnded")

    return run_bot
