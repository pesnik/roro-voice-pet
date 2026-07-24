# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in
this repository.

## Project Overview

RoRo Voice Pet is a local-first desktop pet: a floating Electron companion with a chat
bubble and a live voice "Call Mode", backed by a llama.cpp-based inference sidecar with
a pluggable chat backend (local llama.cpp, OpenRouter, or a self-hosted Hermes Agent
server). It is **not** a coding-agent companion — the upstream project this was
bootstrapped from reacted to Claude Code/Codex/Cursor/etc. hook activity; that entire
integration surface was removed. This is a standalone pet + voice + chat product.

## Repository Structure

```
roro-voice-pet/
├── app/                 ← Electron desktop pet
│   ├── src/                 main process, renderer, settings, chat bubble, theme system
│   ├── themes/               built-in theme packs (flat PNG sprite per pet state)
│   └── test/                 Node built-in test runner tests
├── sidecar/             ← llama.cpp inference + FastAPI gateway + Pipecat voice pipeline
│   ├── gateway/               Python gateway (FastAPI/uvicorn/httpx, no torch)
│   ├── scripts/               Build and fetch scripts for llama-server
│   └── tests/                 pytest suite
├── adapters/             ← LoRA persona adapters (.gguf)
├── models/                ← GGUF model files (symlink to shared local storage)
└── go.sh                  ← Dev launcher and build entry point
```

## Development Commands

```bash
./go.sh              # Install all deps + start Electron pet in foreground
./go.sh doctor        # Check environment (node 18+, uv, sidecar)
./go.sh setup         # Install deps only, don't start
./go.sh start         # Skip dependency checks, just start
./go.sh build         # Full packaged build (mac arm64 dmg)
```

### Tests

```bash
cd app && npm test
cd sidecar && uv run pytest -q
```

### Debugging

```bash
curl -s http://127.0.0.1:18765/api/health | python3 -m json.tool
lsof -ti:18765 | xargs -r kill -9   # sidecar port
lsof -ti:23333 | xargs -r kill -9   # app HTTP server
```

## Architecture

### Two-Process Model

1. **Electron app** (`app/`): pet UI, state machine, settings, chat bubble, theme system.
2. **Inference sidecar** (`sidecar/`): a bundled `llama-server` binary fronted by a thin
   FastAPI gateway. Handles chat backend selection, adapter switching, chat
   completions, and (new) the Pipecat live-voice pipeline. No PyTorch — all local ML
   inference happens in native binaries (llama-server; whisper.cpp/Kokoro via Pipecat).

### Chat backend — pluggable, not hard-locked to local

`sidecar/gateway/server.py` dispatches chat across `llama.cpp` (local), `openrouter`
(cloud), and `hermes` (a self-hosted Hermes Agent OpenAI-compatible server) — set via
`MINICPM_BACKEND` or Settings. The live Call Mode pipeline reuses this same backend
resolution rather than having a separate "voice backend" setting.

### Settings System

`src/prefs.js` → `src/settings-controller.js` (sole writer) → `src/settings-store.js`
(immutable snapshots). Side effects in `src/settings-actions*.js`. Don't bypass
`settings-controller.js`.

### Electron Event Flow

`server.js` (local HTTP server, `/state` route) → `state.js` (pet state machine) → IPC
→ `renderer.js` (animation). The sidecar's `ClawdBridge` posts pet-state updates to this
same `/state` route today (chat), and Call Mode will drive it too (listening/thinking/
speaking).

## Conventions

- **Commit style**: [Conventional Commits](https://www.conventionalcommits.org/).
- **License**: `AGPL-3.0-only`, inherited from upstream — see `NOTICE.md`.
- **Resource paths**: always `path.join(__dirname, ...)`.
