"use strict";

// Local HTTP server identity + port/runtime-config plumbing for the /state
// route. This used to live under hooks/server-config.js alongside a much
// larger set of helpers for Node-based coding-agent hook scripts (hook HTTP
// posting, permission discovery, node-binary resolution for hook commands,
// etc.) — none of that applies anymore since this app has no hook scripts.
// The MiniCPM sidecar is the only /state poster, and it talks straight HTTP
// from Python (see sidecar/gateway/clawd_state.py), so it doesn't need any
// of this file either. What's left here is just the generic "which port is
// the Electron HTTP server on, and where is that recorded" bookkeeping that
// src/server.js itself needs regardless of who calls /state.

const fs = require("fs");
const os = require("os");
const path = require("path");

const CLAWD_SERVER_ID = "clawd-on-desk";
const CLAWD_SERVER_HEADER = "x-clawd-server";
const DEFAULT_SERVER_PORT = 23333;
const SERVER_PORT_COUNT = 5;
const SERVER_PORTS = Array.from({ length: SERVER_PORT_COUNT }, (_, i) => DEFAULT_SERVER_PORT + i);
const RUNTIME_CONFIG_PATH = path.join(os.homedir(), ".clawd", "runtime.json");

function normalizePort(value) {
  const port = Number(value);
  return Number.isInteger(port) && SERVER_PORTS.includes(port) ? port : null;
}

function readRuntimeConfig() {
  try {
    const raw = JSON.parse(fs.readFileSync(RUNTIME_CONFIG_PATH, "utf8"));
    if (!raw || typeof raw !== "object") return null;
    const port = normalizePort(raw.port);
    return port ? { port } : null;
  } catch {
    return null;
  }
}

function readRuntimePort() {
  const config = readRuntimeConfig();
  return config ? config.port : null;
}

function writeRuntimeConfig(port) {
  const safePort = normalizePort(port);
  if (!safePort) return false;

  const dir = path.dirname(RUNTIME_CONFIG_PATH);
  const tmpPath = path.join(dir, `.runtime.${process.pid}.${Date.now()}.tmp`);
  const body = JSON.stringify({ app: CLAWD_SERVER_ID, port: safePort }, null, 2);
  fs.mkdirSync(dir, { recursive: true });
  try {
    fs.writeFileSync(tmpPath, body, "utf8");
    fs.renameSync(tmpPath, RUNTIME_CONFIG_PATH);
    return true;
  } catch {
    try { fs.unlinkSync(tmpPath); } catch {}
    return false;
  }
}

function clearRuntimeConfig(filePath = RUNTIME_CONFIG_PATH) {
  try {
    fs.unlinkSync(filePath);
    return true;
  } catch {
    return false;
  }
}

function getPortCandidates(preferredPort, options = {}) {
  const ports = [];
  const seen = new Set();
  const runtimePort = normalizePort(
    Object.prototype.hasOwnProperty.call(options, "runtimePort")
      ? options.runtimePort
      : readRuntimePort()
  );
  const add = (value) => {
    const port = normalizePort(value);
    if (!port || seen.has(port)) return;
    seen.add(port);
    ports.push(port);
  };

  if (Array.isArray(preferredPort)) preferredPort.forEach(add);
  else add(preferredPort);
  add(runtimePort);
  SERVER_PORTS.forEach(add);
  return ports;
}

module.exports = {
  CLAWD_SERVER_HEADER,
  CLAWD_SERVER_ID,
  DEFAULT_SERVER_PORT,
  RUNTIME_CONFIG_PATH,
  SERVER_PORTS,
  clearRuntimeConfig,
  getPortCandidates,
  readRuntimePort,
  writeRuntimeConfig,
};
