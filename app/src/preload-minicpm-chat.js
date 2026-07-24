"use strict";

const { contextBridge, ipcRenderer } = require("electron");
const { PipecatClient } = require("@pipecat-ai/client-js");
const { SmallWebRTCTransport, WavMediaManager } = require("@pipecat-ai/small-webrtc-transport");

// Call Mode (live voice) — the Pipecat client itself lives here, in the
// preload's privileged (require()-capable) context, and is exposed to the
// sandboxed renderer as a thin set of functions via contextBridge below.
// Mirrors how everything else in this file keeps Node/require access out
// of the renderer while still surfacing the capability it needs.
let _callClient = null;

// @pipecat-ai/client-js's connect() resolves only once a "bot-ready" RTVI
// message arrives over the data channel — there's no built-in timeout, so
// if the server-side pipeline never sends it (crashed pipeline, WebRTC
// negotiation stuck, local STT/TTS model failing to start), the promise
// hangs forever with zero feedback. This wraps it with a hard deadline so
// the renderer's existing catch-block error toast can actually fire.
const CALL_CONNECT_TIMEOUT_MS = 20000;

// The bot's voice comes back over a real WebRTC audio track, not through
// WavMediaManager's DataChannel-PCM path (that's mic-capture-only in this
// mode) — PipecatClient only ever hands the raw MediaStreamTrack to
// onTrackStarted and leaves playback entirely to the app (confirmed: no
// auto-attached <audio> element anywhere in client-js). Without this, the
// server does everything right (STT transcribes, LLM replies, Kokoro
// synthesizes, "bot started/stopped speaking" fires) and the pet still
// never makes a sound, because nothing was ever connected to a speaker.
let _botAudioEl = null;

function _attachBotAudioTrack(track) {
  if (!track || track.kind !== "audio") return;
  _detachBotAudioTrack();
  _botAudioEl = new Audio();
  _botAudioEl.autoplay = true;
  _botAudioEl.srcObject = new MediaStream([track]);
}

function _detachBotAudioTrack() {
  if (!_botAudioEl) return;
  try { _botAudioEl.pause(); _botAudioEl.srcObject = null; } catch {}
  _botAudioEl = null;
}

function _buildCallClient(onEvent) {
  const emit = (name, payload) => {
    try { onEvent(name, payload); } catch {}
  };
  return new PipecatClient({
    // SmallWebRTCTransport's default media manager (DailyMediaManager) pulls
    // in @daily-co/daily-js, which fetches a "call machine" bundle from
    // https://c.daily.co at runtime — even though we never use Daily's
    // transport/cloud infra, just this one client library's default device
    // manager. Our CSP correctly blocks that as an unexpected external
    // fetch, but the library swallows the resulting rejection internally
    // instead of failing connect() — so Call Mode just hung on "Connecting…"
    // forever with no error. WavMediaManager is the same package's
    // Daily-free alternative (plain getUserMedia + Web Audio, no CDN call)
    // and is all a WebRTC-only, fully-local call needs.
    transport: new SmallWebRTCTransport({ mediaManager: new WavMediaManager() }),
    enableMic: true,
    enableCam: false,
    callbacks: {
      onBotStartedSpeaking: () => emit("bot-started-speaking"),
      onBotStoppedSpeaking: () => emit("bot-stopped-speaking"),
      onUserStartedSpeaking: () => emit("user-started-speaking"),
      onUserStoppedSpeaking: () => emit("user-stopped-speaking"),
      // pipecat's RTVIObserver sends these by default (enable_rtvi=True,
      // no server-side change needed) — user's speech (interim + final)
      // and the bot's reply, sentence-aggregated. Forwarded so the
      // renderer can show a live transcript instead of the call being a
      // black box with nothing to show for it once it ends.
      onUserTranscript: (data) => emit("user-transcript", { text: data && data.text, final: !!(data && data.final) }),
      onBotTranscript: (data) => emit("bot-transcript", { text: data && data.text }),
      onDisconnected: () => { _detachBotAudioTrack(); emit("disconnected"); },
      onError: (message) => emit("error", { message: String((message && message.data) || message) }),
      onTrackStarted: (track) => _attachBotAudioTrack(track),
      onTrackStopped: (track) => { if (track === (_botAudioEl && _botAudioEl.srcObject && _botAudioEl.srcObject.getTracks()[0])) _detachBotAudioTrack(); },
    },
  });
}

contextBridge.exposeInMainWorld("minicpm", {
  // Sidecar lifecycle
  start: (opts) => ipcRenderer.invoke("minicpm:start", opts),
  status: () => ipcRenderer.invoke("minicpm:status"),

  // Bubble window controls
  resize: (width, height) => ipcRenderer.invoke("minicpm:resize", { width, height }),
  setChatAnchor: (bottomY) => ipcRenderer.invoke("minicpm:set-chat-anchor", { bottomY }),
  hideWindow: () => ipcRenderer.invoke("minicpm:hide-window"),
  showWindow: () => ipcRenderer.invoke("minicpm:show-window"),
  focusWindow: () => ipcRenderer.invoke("minicpm:focus-window"),
  openContextMenu: () => ipcRenderer.send("minicpm:open-context-menu"),

  // Updater
  updateStatus: () => ipcRenderer.invoke("minicpm:update-status"),
  updateApply:  () => ipcRenderer.invoke("minicpm:update-apply"),

  // Chat generation parameters (shared with Settings tab)
  getChatParams: () => ipcRenderer.invoke("minicpm:get-chat-params"),

  // Adapter (LoRA) load/unload — same IPC handler the Settings tab
  // uses, so chat-based switching ("切到猫娘") persists the user's
  // choice to prefs and shares the 90s timeout + bubble notification
  // pipeline. Pass `null` to unload.
  loadAdapter: (pathOrNull) => ipcRenderer.invoke("minicpm-settings:load-adapter", { path: pathOrNull }),

  // Proactive macOS mic-permission check — call before callStart() so a
  // denial shows a friendly message instead of a silent connect failure.
  ensureMicAccess: () => ipcRenderer.invoke("minicpm:ensure-mic-access"),

  // Call Mode (live voice) — offerUrl is the sidecar's POST /api/call/offer
  // endpoint. onEvent(name, payload) receives: bot-started-speaking,
  // bot-stopped-speaking, user-started-speaking, user-stopped-speaking,
  // disconnected, error.
  callStart: async (offerUrl, onEvent) => {
    if (_callClient) {
      try { await _callClient.disconnect(); } catch {}
    }
    const client = _buildCallClient(onEvent);
    _callClient = client;
    let timedOut = false;
    let timer = null;
    try {
      await Promise.race([
        client.connect({ webrtcUrl: offerUrl }),
        new Promise((_resolve, reject) => {
          timer = setTimeout(() => {
            timedOut = true;
            reject(new Error("Call connection timed out — check sidecar logs for details."));
          }, CALL_CONNECT_TIMEOUT_MS);
        }),
      ]);
    } catch (err) {
      if (timedOut && _callClient === client) {
        try { await client.disconnect(); } catch {}
        if (_callClient === client) _callClient = null;
      }
      throw err;
    } finally {
      if (timer) clearTimeout(timer);
    }
  },
  callEnd: async () => {
    if (!_callClient) return;
    try { await _callClient.disconnect(); } finally { _callClient = null; }
  },
  callSetMuted: (muted) => {
    if (_callClient) _callClient.enableMic(!muted);
  },

  // i18n: initial fetch + live updates
  getI18n: () => ipcRenderer.invoke("minicpm:get-i18n"),
  onLangChange: (cb) => {
    const listener = (_e, payload) => { try { cb(payload || {}); } catch {} };
    ipcRenderer.on("minicpm:lang-change", listener);
    return () => ipcRenderer.removeListener("minicpm:lang-change", listener);
  },

  // Messages from main → renderer
  onOpen:           (cb) => ipcRenderer.on("minicpm:cmd-open",            (_e, payload) => cb(payload || {})),
  onDismiss:        (cb) => ipcRenderer.on("minicpm:cmd-dismiss",         () => cb()),
  onReset:          (cb) => ipcRenderer.on("minicpm:cmd-reset",           () => cb()),
  onToggleThinking: (cb) => ipcRenderer.on("minicpm:cmd-toggle-thinking", () => cb()),
  onToggleCallMode: (cb) => ipcRenderer.on("minicpm:cmd-toggle-call-mode", () => cb()),
  onUpdateStatus:   (cb) => ipcRenderer.on("minicpm:update-status",       (_e, p) => cb(p || {})),
  onUpdateApplying: (cb) => ipcRenderer.on("minicpm:update-applying",     (_e, p) => cb(p || {})),
  onNarrate:        (cb) => ipcRenderer.on("minicpm:narrate",             (_e, p) => cb(p || {})),
  onCmdReply:       (cb) => ipcRenderer.on("minicpm:cmd-reply",           (_e, p) => cb(p || {})),
  onEditMode:       (cb) => ipcRenderer.on("minicpm:edit-mode",           (_e, p) => cb(p || {})),
});
