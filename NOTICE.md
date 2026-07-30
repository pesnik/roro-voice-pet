# Notices

## RoRo Voice Pet

This project is bootstrapped from `RoRo-Desk-Pet` (a personal fork/derivative of
[`rullerzhou-afk/clawd-on-desk`](https://github.com/rullerzhou-afk/clawd-on-desk), which
itself combined a local MiniCPM5 inference sidecar via
[llama.cpp](https://github.com/ggml-org/llama.cpp) with an Electron desktop pet UI).
The `app/` directory keeps the pet rendering, theme/mini-mode system, settings and
shortcuts framework, and chat bubble from that lineage; the coding-agent-hook
integration it originally shipped with has been removed, and a live voice "Call Mode"
(Pipecat-based) is being added on top.

This project and its upstream are licensed under the GNU Affero General Public License
v3.0 (AGPL-3.0-only); see [LICENSE](./LICENSE) for the full text.

---

## Third-party components

### llama.cpp

The sidecar embeds [`llama-server`](https://github.com/ggml-org/llama.cpp)
(MIT License, © 2023 Georgi Gerganov and llama.cpp contributors) for local inference.

### Pipecat

The live voice pipeline (Call Mode) is built on [Pipecat](https://github.com/pipecat-ai/pipecat)
(BSD-2-Clause, © Daily.co), including its local Whisper (STT), Kokoro (TTS), and
Silero VAD service integrations, and the `@pipecat-ai/client-js` /
`small-webrtc-transport` client SDK (BSD-2-Clause).

### RikkaHub

The Android app's markdown/message rendering (`android/app/src/main/java/dev/pesnik/roro/ui/richtext/`)
is adapted from [RikkaHub](https://github.com/rikkahub/rikkahub) (AGPL-3.0, © re-ovo and
contributors), specifically its markdown-to-Compose AST renderer
(`ui/components/richtext/Markdown.kt`) and data table layout
(`ui/components/table/DataTable.kt`), as adapted from commit `16be94e8c9`. Trimmed for
this app's needs: no HTML-fallback rendering, no citations/tool-call UI, no LaTeX
math, and no QuickJS/Prism.js-based syntax highlighting (RikkaHub's code-block chrome
was rebuilt fresh and much smaller instead). See the header comment in each adapted
file for the specific upstream source.
