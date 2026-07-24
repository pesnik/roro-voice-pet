from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from gateway import server as server_mod
from gateway import whisper_cpp_stt


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


def test_whisper_server_binary_override(monkeypatch, tmp_path):
    override = tmp_path / "whisper-server"
    override.write_text("", encoding="utf-8")
    monkeypatch.setenv("MINICPM_WHISPER_SERVER", str(override))

    paths = whisper_cpp_stt._candidate_binary_paths()

    assert paths[0] == override


def test_ensure_whisper_model_skips_download_when_present(monkeypatch, tmp_path):
    existing = tmp_path / whisper_cpp_stt.DEFAULT_MODEL_FILENAME
    existing.write_bytes(b"fake-model")

    def _boom(*_args, **_kwargs):
        raise AssertionError("hf_hub_download should not be called when the model already exists")

    monkeypatch.setattr("huggingface_hub.hf_hub_download", _boom)

    result = whisper_cpp_stt.ensure_whisper_model(tmp_path)

    assert result == existing


def test_ensure_whisper_model_downloads_when_missing(monkeypatch, tmp_path):
    calls = []

    def _fake_download(*, repo_id, filename, local_dir):
        calls.append((repo_id, filename, local_dir))
        target = Path(local_dir) / filename
        target.write_bytes(b"fake-model")
        return str(target)

    monkeypatch.setattr("huggingface_hub.hf_hub_download", _fake_download)

    result = whisper_cpp_stt.ensure_whisper_model(tmp_path)

    assert result == tmp_path / whisper_cpp_stt.DEFAULT_MODEL_FILENAME
    assert calls == [
        (whisper_cpp_stt.DEFAULT_MODEL_REPO, whisper_cpp_stt.DEFAULT_MODEL_FILENAME, str(tmp_path))
    ]
