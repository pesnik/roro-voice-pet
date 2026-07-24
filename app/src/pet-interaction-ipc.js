"use strict";

function requiredDependency(value, name) {
  if (!value) throw new Error(`registerPetInteractionIpc requires ${name}`);
  return value;
}

function registerPetInteractionIpc(options = {}) {
  const ipcMain = requiredDependency(options.ipcMain, "ipcMain");
  const showContextMenu = requiredDependency(options.showContextMenu, "showContextMenu");
  const moveWindowForDrag = requiredDependency(options.moveWindowForDrag, "moveWindowForDrag");
  const setIdlePaused = requiredDependency(options.setIdlePaused, "setIdlePaused");
  const isMiniTransitioning = requiredDependency(options.isMiniTransitioning, "isMiniTransitioning");
  const getCurrentState = requiredDependency(options.getCurrentState, "getCurrentState");
  const getCurrentSvg = requiredDependency(options.getCurrentSvg, "getCurrentSvg");
  const sendToRenderer = requiredDependency(options.sendToRenderer, "sendToRenderer");
  const setDragLocked = requiredDependency(options.setDragLocked, "setDragLocked");
  const setMouseOverPet = requiredDependency(options.setMouseOverPet, "setMouseOverPet");
  const beginDragSnapshot = requiredDependency(options.beginDragSnapshot, "beginDragSnapshot");
  const clearDragSnapshot = requiredDependency(options.clearDragSnapshot, "clearDragSnapshot");
  const syncHitWin = requiredDependency(options.syncHitWin, "syncHitWin");
  const isMiniMode = requiredDependency(options.isMiniMode, "isMiniMode");
  const checkMiniModeSnap = requiredDependency(options.checkMiniModeSnap, "checkMiniModeSnap");
  const hasPetWindow = requiredDependency(options.hasPetWindow, "hasPetWindow");
  const getPetWindowBounds = requiredDependency(options.getPetWindowBounds, "getPetWindowBounds");
  const getCurrentPixelSize = requiredDependency(options.getCurrentPixelSize, "getCurrentPixelSize");
  // #408: prefer the effective (frozen, when keepSizeAcrossDisplays) size over
  // re-reading live bounds; falls back to proportional when not provided.
  const getEffectiveCurrentPixelSize = options.getEffectiveCurrentPixelSize || getCurrentPixelSize;
  const computeDragEndBounds = requiredDependency(options.computeDragEndBounds, "computeDragEndBounds");
  const applyPetWindowBounds = requiredDependency(options.applyPetWindowBounds, "applyPetWindowBounds");
  const flushRuntimeStateToPrefs = requiredDependency(
    options.flushRuntimeStateToPrefs,
    "flushRuntimeStateToPrefs"
  );
  const reassertWinTopmost = requiredDependency(options.reassertWinTopmost, "reassertWinTopmost");
  const scheduleHwndRecovery = requiredDependency(options.scheduleHwndRecovery, "scheduleHwndRecovery");
  const repositionFloatingBubbles = requiredDependency(
    options.repositionFloatingBubbles,
    "repositionFloatingBubbles"
  );
  const exitMiniMode = requiredDependency(options.exitMiniMode, "exitMiniMode");
  const getDisableMiniMode = options.getDisableMiniMode || (() => false);
  const setLowPowerIdlePaused = requiredDependency(
    options.setLowPowerIdlePaused,
    "setLowPowerIdlePaused"
  );
  const disposers = [];

  function on(channel, listener) {
    ipcMain.on(channel, listener);
    disposers.push(() => ipcMain.removeListener(channel, listener));
  }

  on("show-context-menu", showContextMenu);
  on("drag-move", () => moveWindowForDrag());

  on("pause-cursor-polling", () => {
    setIdlePaused(true);
  });
  on("resume-from-reaction", () => {
    setIdlePaused(false);
    if (isMiniTransitioning()) return;
    sendToRenderer("state-change", getCurrentState(), getCurrentSvg());
  });
  on("low-power-idle-paused", (_event, paused) => {
    setLowPowerIdlePaused(!!paused);
  });

  on("drag-lock", (_event, locked) => {
    setDragLocked(!!locked);
    if (locked) {
      setMouseOverPet(true);
      beginDragSnapshot();
    } else {
      clearDragSnapshot();
      syncHitWin();
    }
  });

  on("start-drag-reaction", (_event, direction) => {
    sendToRenderer("start-drag-reaction", direction === "left" || direction === "right" ? direction : null);
  });
  on("end-drag-reaction", () => sendToRenderer("end-drag-reaction"));
  on("play-click-reaction", (_event, svg, duration) => {
    sendToRenderer("play-click-reaction", svg, duration);
  });

  on("drag-end", () => {
    try {
      if (!isMiniMode() && !isMiniTransitioning()) {
        if (!getDisableMiniMode()) checkMiniModeSnap();
        if (isMiniMode() || isMiniTransitioning()) return;
        if (hasPetWindow()) {
          const virtualBounds = getPetWindowBounds();
          const size = getEffectiveCurrentPixelSize();
          const clamped = computeDragEndBounds(virtualBounds, size);
          if (clamped) {
            applyPetWindowBounds(clamped);
            flushRuntimeStateToPrefs();
          }
          reassertWinTopmost();
          scheduleHwndRecovery();
          syncHitWin();
          repositionFloatingBubbles();
        }
      }
    } finally {
      setDragLocked(false);
      clearDragSnapshot();
    }
  });

  on("exit-mini-mode", () => {
    if (isMiniMode()) exitMiniMode();
  });

  return {
    dispose() {
      while (disposers.length) {
        const dispose = disposers.pop();
        dispose();
      }
    },
  };
}

module.exports = {
  registerPetInteractionIpc,
};
