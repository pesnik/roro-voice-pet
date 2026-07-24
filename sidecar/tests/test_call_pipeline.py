from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from gateway import call_pipeline
from gateway import server as server_mod
from gateway import whisper_cpp_stt
from gateway.call_pipeline import STTConfig, TTSConfig
from gateway.whisper_cpp_stt import WhisperCppSTTService


def test_call_offer_route_registered_for_every_backend():
    for backend, kwargs in [
        ("llama.cpp", {}),
        ("hermes", {"hermes_url": "http://127.0.0.1:8642/v1", "hermes_model": "hermes-agent"}),
        ("openrouter", {"openrouter_model": "openai/gpt-4o-mini"}),
    ]:
        app = server_mod.build_app(initial_model=None, backend=backend, **kwargs)
        with TestClient(app) as client:
            # Malformed body should 422 (pydantic validation), never 500 —
            # proves the route + SmallWebRTCRequest wiring is sound without
            # needing a real WebRTC offer/answer negotiation.
            response = client.post("/api/call/offer", json={"not": "a real offer"})
        assert response.status_code == 422, backend


def test_build_stt_defaults_to_local_whisper_cpp():
    stt = call_pipeline._build_stt(STTConfig(), whisper_server="fake-server")

    assert isinstance(stt, WhisperCppSTTService)
    assert stt._server == "fake-server"


def test_build_stt_openai_backend_uses_configured_endpoint(monkeypatch):
    captured = {}

    class _FakeSettings:
        def __init__(self, *, model):
            self.model = model

    class _FakeOpenAISTTService:
        Settings = _FakeSettings

        def __init__(self, *, api_key, base_url, settings):
            captured["api_key"] = api_key
            captured["base_url"] = base_url
            captured["settings"] = settings

    monkeypatch.setattr(call_pipeline, "OpenAISTTService", _FakeOpenAISTTService)

    cfg = STTConfig(backend="openai", api_key="sk-test", base_url="https://api.openai.com/v1", model="whisper-1")
    stt = call_pipeline._build_stt(cfg, whisper_server="unused")

    assert isinstance(stt, _FakeOpenAISTTService)
    assert captured["api_key"] == "sk-test"
    assert captured["base_url"] == "https://api.openai.com/v1"
    assert captured["settings"].model == "whisper-1"


def test_build_tts_defaults_to_local_kokoro_with_a_default_voice(monkeypatch):
    captured = {}

    class _FakeSettings:
        def __init__(self, *, voice):
            self.voice = voice

    class _FakeKokoroTTSService:
        Settings = _FakeSettings

        def __init__(self, *, settings):
            captured["settings"] = settings

    monkeypatch.setattr(call_pipeline, "KokoroTTSService", _FakeKokoroTTSService)

    tts = call_pipeline._build_tts(TTSConfig())

    # Regression test: KokoroTTSService raises "Kokoro TTS voice must be
    # specified" at first synthesis if none is set — it has no built-in
    # default, unlike OpenAI's TTS. A bare KokoroTTSService() call broke
    # every local-backend call at the first spoken sentence.
    assert isinstance(tts, _FakeKokoroTTSService)
    assert captured["settings"].voice == call_pipeline.DEFAULT_KOKORO_VOICE


def test_build_tts_local_kokoro_honours_tts_voice_override(monkeypatch):
    captured = {}

    class _FakeSettings:
        def __init__(self, *, voice):
            self.voice = voice

    class _FakeKokoroTTSService:
        Settings = _FakeSettings

        def __init__(self, *, settings):
            captured["settings"] = settings

    monkeypatch.setattr(call_pipeline, "KokoroTTSService", _FakeKokoroTTSService)

    call_pipeline._build_tts(TTSConfig(voice="af_sky"))

    assert captured["settings"].voice == "af_sky"


def test_build_tts_openai_backend_uses_configured_voice(monkeypatch):
    captured = {}

    class _FakeSettings:
        def __init__(self, *, model, voice):
            self.model = model
            self.voice = voice

    class _FakeOpenAITTSService:
        Settings = _FakeSettings

        def __init__(self, *, api_key, base_url, settings):
            captured["api_key"] = api_key
            captured["base_url"] = base_url
            captured["settings"] = settings

    monkeypatch.setattr(call_pipeline, "OpenAITTSService", _FakeOpenAITTSService)

    cfg = TTSConfig(backend="openai", api_key="sk-test", voice="alloy", model="gpt-4o-mini-tts")
    tts = call_pipeline._build_tts(cfg)

    assert isinstance(tts, _FakeOpenAITTSService)
    assert captured["api_key"] == "sk-test"
    assert captured["settings"].voice == "alloy"
    assert captured["settings"].model == "gpt-4o-mini-tts"


def test_whisper_server_binary_override(monkeypatch, tmp_path):
    override = tmp_path / "whisper-server"
    override.write_text("", encoding="utf-8")
    monkeypatch.setenv("MINICPM_WHISPER_SERVER", str(override))

    paths = whisper_cpp_stt._candidate_binary_paths()

    assert paths[0] == override


def test_ensure_whisper_model_skips_download_when_present(monkeypatch, tmp_path):
    # download_one itself (not ensure_whisper_model) owns the "already
    # exists, don't touch the network" check — exercise that for real
    # rather than mocking it away, by making any actual HTTP call blow up.
    existing = tmp_path / whisper_cpp_stt.DEFAULT_MODEL_FILENAME
    existing.write_bytes(b"fake-model")

    def _boom(*_args, **_kwargs):
        raise AssertionError("httpx.stream should not be called when the model already exists")

    monkeypatch.setattr(whisper_cpp_stt.httpx, "stream", _boom)

    result = whisper_cpp_stt.ensure_whisper_model(tmp_path)

    assert result == existing
    assert existing.read_bytes() == b"fake-model"


def test_ensure_whisper_model_delegates_to_voice_assets_downloader(monkeypatch, tmp_path):
    calls = []

    def _fake_download_one(url, dest, *, asset):
        calls.append((url, dest, asset))
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"fake-model")
        yield {"phase": "done", "asset": asset, "file": dest.name}

    monkeypatch.setattr(whisper_cpp_stt, "download_one", _fake_download_one)

    result = whisper_cpp_stt.ensure_whisper_model(tmp_path)

    assert result == tmp_path / whisper_cpp_stt.DEFAULT_MODEL_FILENAME
    assert result.read_bytes() == b"fake-model"
    assert len(calls) == 1
    assert calls[0][1] == tmp_path / whisper_cpp_stt.DEFAULT_MODEL_FILENAME
    assert calls[0][2] == "whisper"
