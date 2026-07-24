"use strict";

const test = require("node:test");
const assert = require("node:assert");

const systemActions = require("../src/settings-actions-system");

test("settings system actions expose the command surface", () => {
  assert.deepStrictEqual(Object.keys(systemActions).sort(), [
    "openAtLogin",
    "repairLocalServer",
    "restartClawd",
  ]);
});

test("settings system actions apply the OS login item via the injected setter", () => {
  const calls = [];
  const result = systemActions.openAtLogin.effect(true, {
    setOpenAtLogin: (value) => calls.push(value),
  });

  assert.deepStrictEqual(result, { status: "ok" });
  assert.deepStrictEqual(calls, [true]);
});

test("settings system actions surface openAtLogin errors without a setter dep", () => {
  const result = systemActions.openAtLogin.effect(true, {});
  assert.strictEqual(result.status, "error");
  assert.match(result.message, /setOpenAtLogin/);
});

test("settings system actions normalize local server repair failures", async () => {
  const result = await systemActions.repairLocalServer(null, {
    repairLocalServer: async () => false,
  });

  assert.strictEqual(result.status, "error");
  assert.match(result.message, /Local server repair failed/);
});

test("settings system actions require restart confirmation", () => {
  const calls = [];
  const result = systemActions.restartClawd({}, {
    restartClawd: () => calls.push("restart"),
  });

  assert.strictEqual(result.status, "error");
  assert.match(result.message, /confirmation/);
  assert.deepStrictEqual(calls, []);
});

test("settings system actions restart Clawd when confirmed", () => {
  const calls = [];
  const result = systemActions.restartClawd({ confirmed: true }, {
    restartClawd: () => calls.push("restart"),
  });

  assert.strictEqual(result.status, "ok");
  assert.deepStrictEqual(calls, ["restart"]);
});
