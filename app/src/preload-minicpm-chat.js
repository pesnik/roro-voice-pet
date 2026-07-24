"use strict";

const { contextBridge, ipcRenderer } = require("electron");
const { PipecatClient } = require("@pipecat-ai/client-js");
const { SmallWebRTCTransport } = require("@pipecat-ai/small-webrtc-transport");

// Call Mode (live voice) — the Pipecat client itself lives here, in the
// preload's privileged (require()-capable) context, and is exposed to the
// sandboxed renderer as a thin set of functions via contextBridge below.
// Mirrors how everything else in this file keeps Node/require access out
// of the renderer while still surfacing the capability it needs.
let _callClient = null;

function _buildCallClient(onEvent) {
  const emit = (name, payload) => {
    try { onEvent(name, payload); } catch {}
  };
  return new PipecatClient({
    transport: new SmallWebRTCTransport(),
    enableMic: true,
    enableCam: false,
    callbacks: {
      onBotStartedSpeaking: () => emit("bot-started-speaking"),
      onBotStoppedSpeaking: () => emit("bot-stopped-speaking"),
      onUserStartedSpeaking: () => emit("user-started-speaking"),
      onUserStoppedSpeaking: () => emit("user-stopped-speaking"),
      onDisconnected: () => emit("disconnected"),
      onError: (message) => emit("error", { message: String((message && message.data) || message) }),
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
    _callClient = _buildCallClient(onEvent);
    await _callClient.connect({ webrtcUrl: offerUrl });
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
