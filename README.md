# RoRo Voice Pet

A local-first desktop pet with a live voice conversation mode ("Call Mode") and a
pluggable LLM backend — local `llama.cpp`, OpenRouter, a self-hosted Hermes Agent
server, or any OpenAI-compatible endpoint.

## Repository structure

```
roro-voice-pet/
├── app/        Electron desktop pet — pet rendering, theme/mini-mode, settings,
│               shortcuts, and the chat bubble (text chat today, Call Mode landing next)
├── sidecar/    FastAPI gateway: local LLM inference (llama.cpp) + pluggable cloud
│               backends (OpenRouter, Hermes Agent) + the Pipecat voice pipeline
├── adapters/   LoRA persona adapters (.gguf)
├── models/     -> symlink to shared local GGUF model storage
├── go.sh       Dev launcher (install deps + start Electron pet in foreground)
└── LICENSE     AGPL-3.0-only
```

## Quick start

```bash
./go.sh              # install deps + start
./go.sh doctor        # check environment
./go.sh setup         # install deps only
./go.sh start          # skip dependency checks, just start
```

## Backend choice

The chat backend is pluggable — set `MINICPM_BACKEND` to `llama.cpp` (default, local),
`openrouter`, or `hermes` (a self-hosted Hermes Agent OpenAI-compatible server), or
switch it live from Settings. Call Mode (live voice) uses the same backend choice —
there's no separate "voice backend" setting.

## Provenance

Bootstrapped from [`RoRo-Desk-Pet`](../RoRo-Desk-Pet) (itself a fork of
[`clawd-on-desk`](https://github.com/rullerzhou-afk/clawd-on-desk)), keeping the pet
shell, theme/mini-mode system, settings/shortcuts framework, and the MiniCPM chat
bubble. The coding-agent-hook integration (Claude Code/Codex/Cursor/etc. activity
reactions) was removed — this project is a standalone voice-and-chat desktop pet, not
a coding-agent companion. See [NOTICE.md](./NOTICE.md) for full attribution. Licensed
`AGPL-3.0-only`, inherited from upstream.
