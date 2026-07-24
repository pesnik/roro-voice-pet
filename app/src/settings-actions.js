"use strict";

// ── Settings actions (transport-agnostic) ──
//
// Two registries:
//
//   updateRegistry  — single-field updates. Each entry is EITHER:
//
//     (a) a plain function `(value, deps) => { status, message? }` —
//         a PURE VALIDATOR with no side effect. Used for fields whose
//         truth lives entirely inside prefs (lang, soundMuted, ...).
//         Reactive UI projection lives in main.js subscribers.
//
//     (b) an object `{ validate, effect }` — a PRE-COMMIT GATE for
//         fields whose truth depends on the OUTSIDE WORLD (the OS login
//         items database, ~/.claude/settings.json, etc.). The effect
//         actually performs the system call; if it fails, the controller
//         does NOT commit, so prefs cannot drift away from system reality.
//         Effects can be sync or async; effects throw → controller wraps
//         as { status: 'error' }.
//
//     Why both forms coexist: the gate-vs-projection split is real (see
//     plan-settings-panel.md §4.2). Forcing every entry to be a gate
//     would create empty effect functions for pure-data fields and blur
//     the contract. Forcing every effect into a subscriber would make
//     "save the system call's failure" impossible because subscribers
//     run AFTER commit and can't unwind it.
//
//   commandRegistry — non-field actions like `removeTheme`, `installHooks`,
//                     `registerShortcut`. These return
//                     `{ status, message?, commit? }`. If `commit` is present,
//                     the controller calls `_commit(commit)` after success so
//                     commands can update store fields atomically with their
//                     side effects.
//
// This module imports nothing from electron, the store, or the controller.
// All deps that an action needs are passed via the second argument:
//
//   actionFn(value, { snapshot, ...injectedDeps })
//
// `injectedDeps` is whatever main.js passed to `createSettingsController`. For
// effect-bearing entries this MUST include the system helpers the effect
// needs (e.g. `setLoginItem`, `registerHooks`) — actions never `require()`
// electron or fs directly so the test suite can inject mocks.
//
// HYDRATE PATH: `controller.hydrate(partial)` runs only the validator and
// SKIPS the effect. This is how startup imports system-backed values into
// prefs without writing them right back. Object-form entries must therefore
// keep validate side-effect-free.

const { CURRENT_VERSION } = require("./prefs");
const {
  TEXT_SCALE_MIN,
  TEXT_SCALE_MAX,
  isValidTextScale,
  normalizeTextScaleByDisplay,
} = require("./text-scale");
const { isValidDisplaySnapshot } = require("./work-area");
const {
  MAX_AUTO_CLOSE_SECONDS,
  buildAggregateHideCommit,
  buildCategoryEnabledCommit,
} = require("./bubble-policy");
const {
  normalizeSessionAliases,
  pruneExpiredSessionAliases,
  sanitizeSessionAlias,
  sessionAliasKey,
} = require("./session-alias");
const { validateShortcutMapShape } = require("./shortcut-actions");
const {
  requireBoolean,
  requireFiniteNumber,
  requireNonNegativeFiniteNumber,
  requireNumberInRange,
  requireIntegerInRange,
  requireEnum,
  requireString,
  requirePlainObject,
} = require("./settings-validators");
const {
  registerShortcut,
  resetShortcut,
  resetAllShortcuts,
} = require("./settings-actions-shortcuts");
const {
  ANIMATION_OVERRIDES_EXPORT_VERSION,
  ONESHOT_OVERRIDE_STATES,
  importAnimationOverrides,
  resetThemeOverrides,
  setAnimationOverride,
  setSoundOverride,
  setThemeOverrideDisabled,
  setWideHitboxOverride,
} = require("./settings-actions-theme-overrides");
const {
  openAtLogin,
  repairLocalServer,
  restartClawd,
} = require("./settings-actions-system");

// ── updateRegistry ──
// Maps prefs field name → validator. Controller looks up by key and runs.

const updateRegistry = {
  // ── Window state ──
  x: requireFiniteNumber("x"),
  y: requireFiniteNumber("y"),
  size(value) {
    if (typeof value !== "string") {
      return { status: "error", message: "size must be a string" };
    }
    if (value === "S" || value === "M" || value === "L") return { status: "ok" };
    if (/^P:\d+(?:\.\d+)?$/.test(value)) return { status: "ok" };
    return {
      status: "error",
      message: `size must be S/M/L or P:<num>, got: ${value}`,
    };
  },

  // ── Mini mode persisted state ──
  miniMode: requireBoolean("miniMode"),
  miniEdge: requireEnum("miniEdge", ["left", "right"]),
  preMiniX: requireFiniteNumber("preMiniX"),
  preMiniY: requireFiniteNumber("preMiniY"),
  positionSaved: requireBoolean("positionSaved"),
  positionThemeId: requireString("positionThemeId", { allowEmpty: true }),
  positionVariantId: requireString("positionVariantId", { allowEmpty: true }),
  // Written only by flushRuntimeStateToPrefs() with a snapshot Electron just
  // handed us; null marks "no snapshot yet" (legacy prefs, headless CI, the
  // rare startup race where screen.* is still coming up).
  positionDisplay: (value) => {
    if (value === null || isValidDisplaySnapshot(value)) return { status: "ok" };
    return { status: "error", message: "positionDisplay must be null or a valid display snapshot" };
  },
  savedPixelWidth: requireNonNegativeFiniteNumber("savedPixelWidth"),
  savedPixelHeight: requireNonNegativeFiniteNumber("savedPixelHeight"),
  // #408: frozen-origin work area for keepSizeAcrossDisplays. null = unknown
  // (legacy prefs / never seeded); otherwise positive width+height.
  savedPixelWorkArea: (value) => {
    if (value === null) return { status: "ok" };
    if (!value || typeof value !== "object") {
      return { status: "error", message: "savedPixelWorkArea must be null or { width, height }" };
    }
    const w = Number(value.width);
    const h = Number(value.height);
    if (!Number.isFinite(w) || w <= 0 || !Number.isFinite(h) || h <= 0) {
      return { status: "error", message: "savedPixelWorkArea.width/height must be positive finite numbers" };
    }
    return { status: "ok" };
  },

  // ── Pure data prefs (function-form: validator only) ──
  lang: requireEnum("lang", ["en", "zh", "zh-TW", "ko", "ja"]),
  soundMuted: requireBoolean("soundMuted"),
  soundVolume: requireNumberInRange("soundVolume", 0, 1),
  textScale: requireNumberInRange("textScale", TEXT_SCALE_MIN, TEXT_SCALE_MAX),
  // Committed by the setTextScaleForDisplay command (the controller requires
  // every commit key to have a registry entry). Strict per-entry validation
  // so a direct settings:update can't park junk in the in-memory store.
  textScaleByDisplay: (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { status: "error", message: "textScaleByDisplay must be an object map" };
    }
    for (const [key, raw] of Object.entries(value)) {
      if (typeof key !== "string" || !key.trim() || !isValidTextScale(raw)) {
        return {
          status: "error",
          message: `textScaleByDisplay entry "${key}" must map a display id to ${TEXT_SCALE_MIN}–${TEXT_SCALE_MAX}`,
        };
      }
    }
    return { status: "ok" };
  },
  flashTaskbarOnComplete: requireBoolean("flashTaskbarOnComplete"),
  flashIntervalMs: requireNumberInRange("flashIntervalMs", 200, 2000),
  flashDurationMs: requireNumberInRange("flashDurationMs", 0, 60000),
  lowPowerIdleMode: requireBoolean("lowPowerIdleMode"),
  keepAwakeWhileWorking: requireBoolean("keepAwakeWhileWorking"),
  bubbleFollowPet: requireBoolean("bubbleFollowPet"),
  sessionHudEnabled: requireBoolean("sessionHudEnabled"),
  sessionHudShowStateLabels: requireBoolean("sessionHudShowStateLabels"),
  sessionHudShowElapsed: requireBoolean("sessionHudShowElapsed"),
  sessionHudShowContextUsage: requireBoolean("sessionHudShowContextUsage"),
  sessionHudCleanupDetached: requireBoolean("sessionHudCleanupDetached"),
  sessionHudPinned: requireBoolean("sessionHudPinned"),
  hideBubbles: requireBoolean("hideBubbles"),
  permissionBubblesEnabled: requireBoolean("permissionBubblesEnabled"),
  autoApproveAllPermissions: requireBoolean("autoApproveAllPermissions"),
  notificationBubbleAutoCloseSeconds: requireIntegerInRange(
    "notificationBubbleAutoCloseSeconds",
    0,
    MAX_AUTO_CLOSE_SECONDS
  ),
  permissionBubbleAutoCloseSeconds: requireIntegerInRange(
    "permissionBubbleAutoCloseSeconds",
    0,
    MAX_AUTO_CLOSE_SECONDS
  ),
  updateBubbleAutoCloseSeconds: requireIntegerInRange(
    "updateBubbleAutoCloseSeconds",
    0,
    MAX_AUTO_CLOSE_SECONDS
  ),
  // Session stale-cleanup intervals. Cross-field invariant
  // (sessionStaleMs > 0 -> workingStaleMs <= sessionStaleMs) is enforced
  // here against the live snapshot AND atomically through the
  // `sessionCleanup.setTriple` command below. Hand-edit fallback lives in
  // prefs.normalizeStaleTriple.
  sessionStaleMs(value, deps = {}) {
    if (value === 0) return { status: "ok" };
    const base = requireIntegerInRange("sessionStaleMs", 60_000, 86_400_000)(value);
    if (base.status !== "ok") return base;
    const snapshot = (deps && deps.snapshot) || {};
    const currentWorking = Number(snapshot.workingStaleMs);
    if (Number.isFinite(currentWorking) && currentWorking > value) {
      return {
        status: "error",
        message:
          `sessionStaleMs (${value}) must be >= workingStaleMs (${currentWorking}). ` +
          "To lower both, use the Reset / paired control.",
      };
    }
    return { status: "ok" };
  },
  workingStaleMs(value, deps = {}) {
    const base = requireIntegerInRange("workingStaleMs", 30_000, 86_400_000)(value);
    if (base.status !== "ok") return base;
    const snapshot = (deps && deps.snapshot) || {};
    const currentSession = Number(snapshot.sessionStaleMs);
    if (Number.isFinite(currentSession) && currentSession > 0 && value > currentSession) {
      return {
        status: "error",
        message: `workingStaleMs (${value}) must be <= sessionStaleMs (${currentSession}).`,
      };
    }
    return { status: "ok" };
  },
  detachedIdleStaleMs: requireIntegerInRange("detachedIdleStaleMs", 5_000, 300_000),
  allowEdgePinning: requireBoolean("allowEdgePinning"),
  disableMiniMode: requireBoolean("disableMiniMode"),
  freeRoam: requireBoolean("freeRoam"),
  keepSizeAcrossDisplays: requireBoolean("keepSizeAcrossDisplays"),
  fullscreenOverlay: requireBoolean("fullscreenOverlay"),
  mobilePreviewEnabled: requireBoolean("mobilePreviewEnabled"),

  // ── System-backed prefs (object-form: validate + effect pre-commit gate) ──
  openAtLogin,

  // openAtLoginHydrated is set exactly once by hydrateSystemBackedSettings()
  //   on first run after the openAtLogin field is added. Pure validator —
  //   no effect. After hydration prefs becomes the source of truth and the
  //   user-visible toggle goes through the openAtLogin gate above.
  openAtLoginHydrated: requireBoolean("openAtLoginHydrated"),

  // ── macOS visibility (cross-field validation) ──
  showTray(value, { snapshot }) {
    if (typeof value !== "boolean") {
      return { status: "error", message: "showTray must be a boolean" };
    }
    if (!value && snapshot && snapshot.showDock === false) {
      return {
        status: "error",
        message: "Cannot hide Menu Bar while Dock is also hidden — Clawd would become unquittable.",
      };
    }
    return { status: "ok" };
  },
  showDock(value, { snapshot }) {
    if (typeof value !== "boolean") {
      return { status: "error", message: "showDock must be a boolean" };
    }
    if (!value && snapshot && snapshot.showTray === false) {
      return {
        status: "error",
        message: "Cannot hide Dock while Menu Bar is also hidden — Clawd would become unquittable.",
      };
    }
    return { status: "ok" };
  },

  // Strict activation gate. Startup uses the lenient path + hydrate() so
  // a deleted theme can't brick boot without polluting this effect.
  theme: {
    validate: requireString("theme"),
    effect(value, deps) {
      if (!deps || typeof deps.activateTheme !== "function") {
        return {
          status: "error",
          message: "theme effect requires activateTheme dep",
        };
      }
      try {
        const snapshot = (deps && deps.snapshot) || {};
        const currentOverrides = snapshot.themeOverrides || {};
        deps.activateTheme(value, null, currentOverrides[value] || null);
        return { status: "ok" };
      } catch (err) {
        return {
          status: "error",
          message: `theme: ${err && err.message}`,
        };
      }
    },
  },

  // ── #329 background update check (Phase 4) ──
  autoUpdateCheck: requireBoolean("autoUpdateCheck"),
  pendingUpdateVersion: requireString("pendingUpdateVersion", { allowEmpty: true }),
  dismissedUpdateVersions(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { status: "error", message: "dismissedUpdateVersions must be a plain object" };
    }
    for (const key of Object.keys(value)) {
      if (typeof key !== "string" || !key) {
        return { status: "error", message: "dismissedUpdateVersions keys must be non-empty strings" };
      }
      if (value[key] !== true) {
        return { status: "error", message: `dismissedUpdateVersions["${key}"] must be the literal true` };
      }
    }
    return { status: "ok" };
  },
  dismissedAgentInstallHints(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { status: "error", message: "dismissedAgentInstallHints must be a plain object" };
    }
    for (const key of Object.keys(value)) {
      if (typeof key !== "string" || !key) {
        return { status: "error", message: "dismissedAgentInstallHints keys must be non-empty strings" };
      }
      if (value[key] !== true) {
        return { status: "error", message: `dismissedAgentInstallHints["${key}"] must be the literal true` };
      }
    }
    return { status: "ok" };
  },
  dismissedAgentCleanupHints(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { status: "error", message: "dismissedAgentCleanupHints must be a plain object" };
    }
    for (const key of Object.keys(value)) {
      if (typeof key !== "string" || !key) {
        return { status: "error", message: "dismissedAgentCleanupHints keys must be non-empty strings" };
      }
      if (value[key] !== true) {
        return { status: "error", message: `dismissedAgentCleanupHints["${key}"] must be the literal true` };
      }
    }
    return { status: "ok" };
  },

  // ── Phase 2/3 placeholders — schema reserves these so applyUpdate accepts them ──
  agents: requirePlainObject("agents"),
  themeOverrides: requirePlainObject("themeOverrides"),
  sessionAliases(value, deps = {}) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { status: "error", message: "sessionAliases must be a plain object" };
    }
    const normalized = normalizeSessionAliases(value, { now: deps.now });
    if (Object.keys(normalized).length !== Object.keys(value).length) {
      return { status: "error", message: "sessionAliases must contain valid alias entries" };
    }
    return { status: "ok" };
  },

  // Phase 3b-swap: per-theme variant selection. NO effect — the runtime switch
  // runs through the `setThemeSelection` command which atomically commits
  // `theme` + `themeVariant` after calling activateTheme(themeId, variantId).
  // Letting this field have an effect would double-activate when the UI
  // updates `theme` and `themeVariant` separately.
  themeVariant: requirePlainObject("themeVariant"),

  shortcuts: {
    validate(value) {
      return validateShortcutMapShape(value);
    },
  },

  // ── Internal — version is owned by prefs.js / migrate(), shouldn't normally
  //    be set via applyUpdate, but we accept it so programmatic upgrades work. ──
  version(value) {
    if (typeof value !== "number" || !Number.isFinite(value) || value < 1) {
      return { status: "error", message: "version must be a positive number" };
    }
    if (value > CURRENT_VERSION) {
      return {
        status: "error",
        message: `version ${value} is newer than supported (${CURRENT_VERSION})`,
      };
    }
    return { status: "ok" };
  },
};

// ── commandRegistry ──
// Non-field actions. Phase 0 has only stubs — they'll be filled in by later phases.

function notImplemented(name) {
  return function () {
    return {
      status: "error",
      message: `${name}: not implemented yet (Phase 0 stub)`,
    };
  };
}

function setAllBubblesHidden(payload, deps) {
  const hidden = typeof payload === "boolean" ? payload : payload && payload.hidden;
  if (typeof hidden !== "boolean") {
    return { status: "error", message: "setAllBubblesHidden.hidden must be a boolean" };
  }
  return { status: "ok", commit: buildAggregateHideCommit(hidden, deps && deps.snapshot) };
}

// DANGER "auto-pilot" writer. Enabling auto-approve-everything is a one-way
// trust decision, so this command — not a raw settings:update — is the only
// path allowed to flip it ON, and it requires an explicit confirmed:true.
// The settings:update IPC handler rejects the field directly (see
// settings-ipc.js), so the confirmation dialog is a real gate, not just UI
// decoration: anything reaching the data layer must carry proof the user
// confirmed. Disabling needs no confirmation (turning a danger toggle off is
// always safe).
function setAutoApproveAll(payload, _deps) {
  if (!payload || typeof payload !== "object") {
    return { status: "error", message: "setAutoApproveAll: payload must be an object" };
  }
  const enabled = payload.enabled;
  if (typeof enabled !== "boolean") {
    return { status: "error", message: "setAutoApproveAll.enabled must be a boolean" };
  }
  if (enabled && payload.confirmed !== true) {
    return {
      status: "error",
      message: "setAutoApproveAll: enabling requires confirmed:true (user must confirm the danger dialog)",
    };
  }
  return { status: "ok", commit: { autoApproveAllPermissions: enabled } };
}

function setBubbleCategoryEnabled(payload, deps) {
  if (!payload || typeof payload !== "object") {
    return { status: "error", message: "setBubbleCategoryEnabled: payload must be an object" };
  }
  const { category, enabled } = payload;
  const result = buildCategoryEnabledCommit((deps && deps.snapshot) || {}, category, enabled);
  if (result.error) return { status: "error", message: result.error };
  return { status: "ok", commit: result.commit };
}

// Atomic three-key writer for the session-cleanup intervals. Lives as a
// command (not as `applyBulk`) because applyBulk runs each single-key
// validator against the PRE-bulk snapshot, which would reject a Reset that
// lowers both knobs simultaneously. The controller's command path re-runs
// validators against the merged snapshot, so the cross-field invariant is
// checked against the values being written together rather than mixed
// with the current state.
function setSessionCleanupTriple(payload, deps) {
  if (!payload || typeof payload !== "object") {
    return { status: "error", message: "sessionCleanup.setTriple: payload must be an object" };
  }
  const snapshot = (deps && deps.snapshot) || {};

  // Strict presence check: a present-but-wrong-type value is a programmer
  // error and must surface, not silently fall back to the snapshot.
  function pick(key) {
    if (key in payload) {
      const v = payload[key];
      if (!Number.isInteger(v)) {
        return { error: `${key} must be an integer (received ${typeof v})` };
      }
      return { value: v };
    }
    const fallback = Number(snapshot[key]);
    if (!Number.isFinite(fallback)) {
      return { error: `${key} missing from payload and not present in snapshot` };
    }
    return { value: fallback };
  }

  const s = pick("sessionStaleMs");
  if (s.error) return { status: "error", message: s.error };
  const w = pick("workingStaleMs");
  if (w.error) return { status: "error", message: w.error };
  const d = pick("detachedIdleStaleMs");
  if (d.error) return { status: "error", message: d.error };

  const sessionStaleMs = s.value;
  const workingStaleMs = w.value;
  const detachedIdleStaleMs = d.value;

  if (!(sessionStaleMs === 0 || (sessionStaleMs >= 60_000 && sessionStaleMs <= 86_400_000))) {
    return { status: "error", message: `sessionStaleMs out of range: ${sessionStaleMs}` };
  }
  if (!(workingStaleMs >= 30_000 && workingStaleMs <= 86_400_000)) {
    return { status: "error", message: `workingStaleMs out of range: ${workingStaleMs}` };
  }
  if (!(detachedIdleStaleMs >= 5_000 && detachedIdleStaleMs <= 300_000)) {
    return { status: "error", message: `detachedIdleStaleMs out of range: ${detachedIdleStaleMs}` };
  }

  if (sessionStaleMs > 0 && workingStaleMs > sessionStaleMs) {
    return {
      status: "error",
      message: `workingStaleMs (${workingStaleMs}) must be <= sessionStaleMs (${sessionStaleMs}).`,
    };
  }

  return {
    status: "ok",
    commit: { sessionStaleMs, workingStaleMs, detachedIdleStaleMs },
  };
}

function sessionAliasMapEqual(a, b) {
  const aKeys = Object.keys(a || {});
  const bKeys = Object.keys(b || {});
  if (aKeys.length !== bKeys.length) return false;
  for (const key of aKeys) {
    const av = a[key];
    const bv = b[key];
    if (!bv || av.title !== bv.title || av.updatedAt !== bv.updatedAt) return false;
  }
  return true;
}

function getCommandNow(deps) {
  const now = deps && typeof deps.now === "function" ? deps.now() : deps && deps.now;
  return Number.isFinite(Number(now)) && Number(now) > 0 ? Number(now) : Date.now();
}

function getActiveSessionAliasKeys(deps) {
  if (!deps || typeof deps.getActiveSessionAliasKeys !== "function") return new Set();
  try {
    const keys = deps.getActiveSessionAliasKeys();
    if (keys instanceof Set) return keys;
    if (Array.isArray(keys)) return new Set(keys);
    if (keys && typeof keys[Symbol.iterator] === "function") return new Set(keys);
  } catch {}
  return new Set();
}

function setSessionAlias(payload, deps) {
  if (!payload || typeof payload !== "object") {
    return { status: "error", message: "setSessionAlias: payload must be an object" };
  }
  const { host, agentId, sessionId, cwd, alias } = payload;
  const key = sessionAliasKey(host, agentId, sessionId, { cwd });
  if (!key) {
    return { status: "error", message: "setSessionAlias.sessionId must be a non-empty string" };
  }
  const cleanAlias = sanitizeSessionAlias(alias);
  if (cleanAlias === null) {
    return { status: "error", message: "setSessionAlias.alias must be a string" };
  }

  const now = getCommandNow(deps);
  const snapshot = (deps && deps.snapshot) || {};
  const currentAliases = normalizeSessionAliases(snapshot.sessionAliases || {}, { now });
  const nextAliases = { ...currentAliases };
  if (cleanAlias) {
    const existing = currentAliases[key];
    if (!existing || existing.title !== cleanAlias) {
      nextAliases[key] = { title: cleanAlias, updatedAt: now };
    }
  }
  else delete nextAliases[key];

  const prunedAliases = pruneExpiredSessionAliases(nextAliases, {
    now,
    activeKeys: getActiveSessionAliasKeys(deps),
  });

  if (sessionAliasMapEqual(prunedAliases, currentAliases)) {
    return { status: "ok", noop: true };
  }
  return { status: "ok", commit: { sessionAliases: prunedAliases } };
}

const _validateRemoveThemeId = requireString("removeTheme.themeId");
async function removeTheme(payload, deps) {
  const themeId = typeof payload === "string" ? payload : (payload && payload.themeId);
  const idCheck = _validateRemoveThemeId(themeId);
  if (idCheck.status !== "ok") return idCheck;

  if (!deps || typeof deps.getThemeInfo !== "function" || typeof deps.removeThemeDir !== "function") {
    return {
      status: "error",
      message: "removeTheme effect requires getThemeInfo and removeThemeDir deps",
    };
  }

  let info;
  try {
    info = deps.getThemeInfo(themeId);
  } catch (err) {
    return { status: "error", message: `removeTheme: ${err && err.message}` };
  }
  if (!info) {
    return { status: "error", message: `removeTheme: theme "${themeId}" not found` };
  }
  if (info.builtin) {
    return { status: "error", message: `removeTheme: cannot delete built-in theme "${themeId}"` };
  }
  if (info.active) {
    return {
      status: "error",
      message: `removeTheme: cannot delete active theme "${themeId}" — switch to another theme first`,
    };
  }
  if (info.managedCodexPet) {
    return {
      status: "error",
      message: `removeTheme: cannot delete managed Codex Pet theme "${themeId}" — remove it from Petdex instead`,
    };
  }

  try {
    await deps.removeThemeDir(themeId);
  } catch (err) {
    return { status: "error", message: `removeTheme: ${err && err.message}` };
  }

  const snapshot = deps.snapshot || {};
  const currentOverrides = snapshot.themeOverrides || {};
  const currentVariantMap = snapshot.themeVariant || {};
  const nextCommit = {};
  if (currentOverrides[themeId]) {
    const nextOverrides = { ...currentOverrides };
    delete nextOverrides[themeId];
    nextCommit.themeOverrides = nextOverrides;
  }
  if (currentVariantMap[themeId] !== undefined) {
    const nextVariantMap = { ...currentVariantMap };
    delete nextVariantMap[themeId];
    nextCommit.themeVariant = nextVariantMap;
  }
  if (Object.keys(nextCommit).length > 0) {
    return { status: "ok", commit: nextCommit };
  }
  return { status: "ok" };
}

// Phase 3b-swap: atomic theme + variant switch.
//   payload: { themeId: string, variantId?: string }
// Why a dedicated command vs. letting the `theme` field effect handle it:
// the theme effect only commits `{theme}`, so the dirty "author deleted the
// variant user had selected" scenario leaves `themeVariant[themeId]` pointing
// at a dead variantId. Fix: call activateTheme which lenient-fallbacks unknown
// variants, read back the actually-resolved variantId, and commit both fields.
// See docs/plans/plan-settings-panel-3b-swap.md §6.2 "Runtime 切换路径".
const _validateSetThemeSelectionThemeId = requireString("setThemeSelection.themeId");
function setThemeSelection(payload, deps) {
  const themeId = typeof payload === "string" ? payload : (payload && payload.themeId);
  const variantIdInput = (payload && typeof payload === "object") ? payload.variantId : null;
  const idCheck = _validateSetThemeSelectionThemeId(themeId);
  if (idCheck.status !== "ok") return idCheck;
  if (variantIdInput != null && (typeof variantIdInput !== "string" || !variantIdInput)) {
    return { status: "error", message: "setThemeSelection.variantId must be a non-empty string when provided" };
  }

  if (!deps || typeof deps.activateTheme !== "function") {
    return { status: "error", message: "setThemeSelection effect requires activateTheme dep" };
  }

  const snapshot = deps.snapshot || {};
  const currentVariantMap = snapshot.themeVariant || {};
  const currentOverrides = snapshot.themeOverrides || {};
  const targetVariant = variantIdInput || currentVariantMap[themeId] || "default";
  const targetOverrideMap = currentOverrides[themeId] || null;

  let resolved;
  try {
    resolved = deps.activateTheme(themeId, targetVariant, targetOverrideMap);
  } catch (err) {
    return { status: "error", message: `setThemeSelection: ${err && err.message}` };
  }
  // activateTheme returns { themeId, variantId } — the variantId here reflects
  // lenient fallback (dead variant → "default"). We commit the resolved value
  // so prefs self-heal away from stale ids.
  const resolvedVariant = (resolved && typeof resolved === "object" && typeof resolved.variantId === "string")
    ? resolved.variantId
    : targetVariant;

  const nextVariantMap = { ...currentVariantMap, [themeId]: resolvedVariant };
  return {
    status: "ok",
    commit: { theme: themeId, themeVariant: nextVariantMap },
  };
}

function resizePet(payload, deps) {
  // Settings panel slider entry point. Routes to menu.resizeWindow via
  // deps.resizePet so it picks up the full side-effect chain (actual window
  // resize, hitWin sync, bubble reposition, runtime flush) that a raw
  // applyUpdate("size", ...) would miss. menu.resizeWindow itself writes
  // prefs.size through the controller, so this command returns no commit.
  if (typeof payload !== "string" || !/^P:\d+(?:\.\d+)?$/.test(payload)) {
    return { status: "error", message: `resizePet: invalid size "${payload}"` };
  }
  if (!deps || typeof deps.resizePet !== "function") {
    return { status: "error", message: "resizePet requires deps.resizePet" };
  }
  try {
    deps.resizePet(payload);
    return { status: "ok" };
  } catch (err) {
    return { status: "error", message: `resizePet: ${err && err.message}` };
  }
}

// textScale is per-display: the slider edits the entry for the display the
// settings window currently sits on (what you see is what you tune). The
// renderer can't know which display that is, so the key is resolved
// main-side via the injected resolveTextScaleDisplayKey dep. Without display
// context (tests, headless) fall back to committing the legacy global so the
// slider still works.
function setTextScaleForDisplay(payload, deps) {
  const value = Number(payload && payload.value);
  if (!isValidTextScale(value)) {
    return {
      status: "error",
      message: `textScale must be a number between ${TEXT_SCALE_MIN} and ${TEXT_SCALE_MAX}`,
    };
  }
  const key = deps && typeof deps.resolveTextScaleDisplayKey === "function"
    ? deps.resolveTextScaleDisplayKey()
    : null;
  if (typeof key !== "string" || !key) {
    return { status: "ok", commit: { textScale: value } };
  }
  const snapshot = (deps && deps.snapshot) || {};
  // New key goes first so the normalize cap can only trim stale displays,
  // never the entry being written.
  const prev = { ...(snapshot.textScaleByDisplay || {}) };
  delete prev[key];
  const next = normalizeTextScaleByDisplay({ [key]: value, ...prev });
  return { status: "ok", commit: { textScaleByDisplay: next } };
}

const commandRegistry = {
  removeTheme,
  repairLocalServer,
  restartClawd,
  resizePet,
  registerShortcut,
  resetShortcut,
  resetAllShortcuts,
  setAllBubblesHidden,
  setAutoApproveAll,
  setBubbleCategoryEnabled,
  "sessionCleanup.setTriple": setSessionCleanupTriple,
  setSessionAlias,
  setTextScaleForDisplay,
  setAnimationOverride,
  setSoundOverride,
  setThemeOverrideDisabled,
  resetThemeOverrides,
  importAnimationOverrides,
  setWideHitboxOverride,
  setThemeSelection,
};

module.exports = {
  updateRegistry,
  commandRegistry,
  ONESHOT_OVERRIDE_STATES,
  ANIMATION_OVERRIDES_EXPORT_VERSION,
  // Exposed for tests
  requireBoolean,
  requireFiniteNumber,
  requireEnum,
  requireString,
  requirePlainObject,
  requireIntegerInRange,
};
