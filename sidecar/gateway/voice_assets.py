"""Local voice-model asset downloads for Call Mode (STT + TTS).

Hand-rolled instead of leaning on huggingface_hub (opaque tqdm-based
progress, no hook a UI can consume) or Pipecat's own Kokoro downloader
(no retry, no atomic write — a network hiccup mid-download leaves a
truncated file that ``_ensure_model_files``'s bare ``.exists()`` check
then trusts as complete forever after). Both problems come from the
same root cause: writing straight to the final path instead of a
`.part` sibling that only gets renamed in after a full, size-verified
transfer.

Mirrors ``updater.py``'s ``{"phase": ...}`` event shape (`start` /
`transfer` / `done`) so the SSE route in server.py and the Electron-side
consumer (already built for the LLM `.gguf` download progress bar) work
for this without inventing a second protocol.
"""

from __future__ import annotations

import os
import shutil
import time
from pathlib import Path
from typing import Iterable, Optional

import httpx

from .log_setup import get_logger

_PART_SUFFIX = ".part"
_CHUNK_SIZE = 4 * 1024 * 1024
_RETRIES = 3

WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
WHISPER_MODEL_FILENAME = "ggml-base.en.bin"

KOKORO_CACHE_DIR = Path.home() / ".cache" / "pipecat" / "kokoro-onnx"
KOKORO_MODEL_URL = (
    "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx"
)
KOKORO_VOICES_URL = (
    "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
)
KOKORO_MODEL_FILENAME = "kokoro-v1.0.onnx"
KOKORO_VOICES_FILENAME = "voices-v1.0.bin"


def _atomic_move(src: Path, dst: Path) -> None:
    try:
        os.replace(src, dst)
    except OSError:
        shutil.move(str(src), str(dst))


def download_one(url: str, dest: Path, *, asset: str) -> Iterable[dict]:
    """Stream one file to `dest`, retrying on failure.

    Writes to a `.part` sibling and atomically renames only after a
    full, size-verified download — a partial/corrupt file is never left
    where a caller's plain `.exists()` check would mistake it for done.
    No-ops (yields a single `skip` event) if `dest` already exists.
    """
    if dest.is_file():
        yield {"phase": "skip", "asset": asset, "file": dest.name}
        return

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.parent / f"{dest.name}{_PART_SUFFIX}"
    log = get_logger()
    last_exc: Optional[Exception] = None

    for attempt in range(1, _RETRIES + 1):
        try:
            with httpx.stream(
                "GET",
                url,
                follow_redirects=True,
                timeout=httpx.Timeout(connect=10.0, read=30.0, write=30.0, pool=10.0),
            ) as resp:
                resp.raise_for_status()
                total = int(resp.headers.get("content-length") or 0)
                done = 0
                yield {"phase": "start", "asset": asset, "file": dest.name, "bytes_total": total}
                with tmp.open("wb") as f:
                    for chunk in resp.iter_bytes(chunk_size=_CHUNK_SIZE):
                        f.write(chunk)
                        done += len(chunk)
                        yield {
                            "phase": "transfer",
                            "asset": asset,
                            "file": dest.name,
                            "bytes_done": done,
                            "bytes_total": total,
                        }
            if total and tmp.stat().st_size != total:
                raise IOError(f"downloaded size {tmp.stat().st_size} != expected {total}")
            _atomic_move(tmp, dest)
            yield {
                "phase": "done",
                "asset": asset,
                "file": dest.name,
                "bytes_done": done,
                "bytes_total": total,
            }
            return
        except Exception as exc:
            last_exc = exc
            log.warning(
                "voice asset download failed (attempt %d/%d) for %s: %s",
                attempt, _RETRIES, url, exc,
            )
            try:
                tmp.unlink()
            except FileNotFoundError:
                pass
            if attempt < _RETRIES:
                time.sleep(1.5 * attempt)

    raise RuntimeError(f"failed to download {url} after {_RETRIES} attempts: {last_exc}")


def ensure_voice_assets(*, whisper_dir: Path, kokoro_dir: Path = KOKORO_CACHE_DIR) -> Iterable[dict]:
    """Generator yielding progress events while ensuring every Call Mode
    asset (whisper.cpp's ggml model, Kokoro's onnx model + voices) is
    present. Safe to call repeatedly — each file is only downloaded once.

    `kokoro_dir` is a parameter (not just the module-level KOKORO_CACHE_DIR
    read directly) specifically so tests can point it at an isolated tmp
    directory — this function used to read the global straight out of its
    body, and a test that only patched `download_one` still ended up
    writing fake files into the real ~/.cache/pipecat/kokoro-onnx.
    """
    yield from download_one(WHISPER_MODEL_URL, whisper_dir / WHISPER_MODEL_FILENAME, asset="whisper")
    yield from download_one(KOKORO_MODEL_URL, kokoro_dir / KOKORO_MODEL_FILENAME, asset="kokoro")
    yield from download_one(KOKORO_VOICES_URL, kokoro_dir / KOKORO_VOICES_FILENAME, asset="kokoro")


def voice_assets_ready(*, whisper_dir: Path, kokoro_dir: Path = KOKORO_CACHE_DIR) -> dict:
    """Cheap filesystem check for /api/call/status — matches what
    ensure_voice_assets would skip, without downloading anything."""
    whisper_path = whisper_dir / WHISPER_MODEL_FILENAME
    kokoro_model = kokoro_dir / KOKORO_MODEL_FILENAME
    kokoro_voices = kokoro_dir / KOKORO_VOICES_FILENAME
    return {
        "whisper": {"ready": whisper_path.is_file(), "path": str(whisper_path)},
        "kokoro": {
            "ready": kokoro_model.is_file() and kokoro_voices.is_file(),
            "path": str(KOKORO_CACHE_DIR),
        },
    }
