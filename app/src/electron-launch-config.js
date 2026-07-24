"use strict";

// Builds the spawn() args/env/cwd for launching the real Electron binary
// from launch.js. Extracted from the (now-removed) hooks/shared-process.js,
// which bundled this alongside a bunch of coding-agent process-detection
// logic this project doesn't use — this piece is generic and still needed.

function buildElectronLaunchConfig(projectDir, options = {}) {
  const platform = options.platform || process.platform;
  const env = { ...(options.env || process.env) };
  delete env.ELECTRON_RUN_AS_NODE;

  const disableSandbox = platform === "linux" && env.CLAWD_DISABLE_SANDBOX === "1";
  if (disableSandbox) {
    env.ELECTRON_DISABLE_SANDBOX = "1";
    env.CHROME_DEVEL_SANDBOX = "";
  }

  const entry = typeof options.entry === "string" ? options.entry : ".";
  const forwardedArgs = Array.isArray(options.forwardedArgs) ? options.forwardedArgs : [];
  const args = disableSandbox
    ? [entry, "--no-sandbox", "--disable-setuid-sandbox", ...forwardedArgs]
    : [entry, ...forwardedArgs];

  return { args, env, cwd: projectDir };
}

module.exports = { buildElectronLaunchConfig };
