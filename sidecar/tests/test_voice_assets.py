from __future__ import annotations

from contextlib import contextmanager

import pytest

from gateway import voice_assets


class _FakeResponse:
    def __init__(self, chunks, *, total=None, status_error=None):
        self._chunks = chunks
        self.headers = {"content-length": str(total)} if total is not None else {}
        self._status_error = status_error

    def raise_for_status(self):
        if self._status_error:
            raise self._status_error

    def iter_bytes(self, chunk_size):
        yield from self._chunks


def _fake_stream(responses):
    """responses: list of _FakeResponse, consumed one per call to httpx.stream."""
    calls = {"n": 0}

    @contextmanager
    def stream(method, url, **kwargs):
        idx = calls["n"]
        calls["n"] += 1
        yield responses[idx]

    return stream


def test_download_one_skips_when_dest_exists(monkeypatch, tmp_path):
    dest = tmp_path / "model.bin"
    dest.write_bytes(b"already here")

    def _boom(*a, **k):
        raise AssertionError("httpx.stream should not be called when dest already exists")

    monkeypatch.setattr(voice_assets.httpx, "stream", _boom)

    events = list(voice_assets.download_one("http://example.test/model.bin", dest, asset="whisper"))

    assert events == [{"phase": "skip", "asset": "whisper", "file": "model.bin"}]
    assert dest.read_bytes() == b"already here"


def test_download_one_streams_and_atomically_renames(monkeypatch, tmp_path):
    dest = tmp_path / "model.bin"
    chunks = [b"hello ", b"world"]
    total = sum(len(c) for c in chunks)
    monkeypatch.setattr(
        voice_assets.httpx, "stream", _fake_stream([_FakeResponse(chunks, total=total)])
    )

    events = list(voice_assets.download_one("http://example.test/model.bin", dest, asset="kokoro"))

    assert dest.is_file()
    assert dest.read_bytes() == b"hello world"
    assert not (dest.parent / "model.bin.part").exists()
    phases = [e["phase"] for e in events]
    assert phases == ["start", "transfer", "transfer", "done"]
    assert events[-1]["bytes_done"] == total
    assert events[-1]["bytes_total"] == total


def test_download_one_retries_after_size_mismatch_then_succeeds(monkeypatch, tmp_path):
    dest = tmp_path / "model.bin"
    # First attempt: server claims 100 bytes but only sends 5 (simulates the
    # exact ReadTimeoutError-mid-stream failure that left a truncated Kokoro
    # cache file). Second attempt: succeeds fully.
    responses = [
        _FakeResponse([b"short"], total=100),
        _FakeResponse([b"complete-data"], total=13),
    ]
    monkeypatch.setattr(voice_assets.httpx, "stream", _fake_stream(responses))
    monkeypatch.setattr(voice_assets.time, "sleep", lambda _s: None)

    events = list(voice_assets.download_one("http://example.test/model.bin", dest, asset="whisper"))

    assert dest.read_bytes() == b"complete-data"
    assert not (dest.parent / "model.bin.part").exists()
    assert any(e["phase"] == "done" for e in events)


def test_download_one_raises_after_exhausting_retries(monkeypatch, tmp_path):
    dest = tmp_path / "model.bin"
    responses = [_FakeResponse([b"short"], total=100) for _ in range(voice_assets._RETRIES)]
    monkeypatch.setattr(voice_assets.httpx, "stream", _fake_stream(responses))
    monkeypatch.setattr(voice_assets.time, "sleep", lambda _s: None)

    with pytest.raises(RuntimeError):
        list(voice_assets.download_one("http://example.test/model.bin", dest, asset="whisper"))

    assert not dest.exists()
    assert not (dest.parent / "model.bin.part").exists()


def test_ensure_voice_assets_downloads_whisper_then_both_kokoro_files(monkeypatch, tmp_path):
    # whisper_dir and kokoro_dir are both explicit tmp_path subdirs here —
    # never rely on ensure_voice_assets' KOKORO_CACHE_DIR default in a
    # test, or a mocked download_one that still does real file I/O will
    # write into the user's actual ~/.cache/pipecat/kokoro-onnx (this
    # happened once already; see the comment on ensure_voice_assets).
    calls = []

    def _fake_download_one(url, dest, *, asset):
        calls.append((url, dest.name, asset))
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"x")
        yield {"phase": "done", "asset": asset, "file": dest.name}

    monkeypatch.setattr(voice_assets, "download_one", _fake_download_one)

    list(voice_assets.ensure_voice_assets(
        whisper_dir=tmp_path / "whisper", kokoro_dir=tmp_path / "kokoro"
    ))

    assets = [c[2] for c in calls]
    files = [c[1] for c in calls]
    assert assets == ["whisper", "kokoro", "kokoro"]
    assert files == [
        voice_assets.WHISPER_MODEL_FILENAME,
        voice_assets.KOKORO_MODEL_FILENAME,
        voice_assets.KOKORO_VOICES_FILENAME,
    ]


def test_voice_assets_ready_reports_per_asset_status(tmp_path):
    whisper_dir = tmp_path / "whisper"
    kokoro_dir = tmp_path / "kokoro-cache"

    status = voice_assets.voice_assets_ready(whisper_dir=whisper_dir, kokoro_dir=kokoro_dir)
    assert status["whisper"]["ready"] is False
    assert status["kokoro"]["ready"] is False

    whisper_dir.mkdir(parents=True)
    (whisper_dir / voice_assets.WHISPER_MODEL_FILENAME).write_bytes(b"x")
    kokoro_dir.mkdir(parents=True)
    (kokoro_dir / voice_assets.KOKORO_MODEL_FILENAME).write_bytes(b"x")
    # Only one of the two kokoro files present -> still not ready.
    status = voice_assets.voice_assets_ready(whisper_dir=whisper_dir, kokoro_dir=kokoro_dir)
    assert status["whisper"]["ready"] is True
    assert status["kokoro"]["ready"] is False

    (kokoro_dir / voice_assets.KOKORO_VOICES_FILENAME).write_bytes(b"x")
    status = voice_assets.voice_assets_ready(whisper_dir=whisper_dir, kokoro_dir=kokoro_dir)
    assert status["kokoro"]["ready"] is True
