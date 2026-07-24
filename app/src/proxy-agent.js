"use strict";

const { execFileSync } = require("child_process");

let _cachedSystemProxy = undefined;

function parseScutilOutput(text) {
  if (typeof text !== "string") return { socks: null, https: null, http: null, exceptions: [] };

  const val = (key) => {
    const m = text.match(new RegExp(`${key}\\s*:\\s*(.+)`));
    return m ? m[1].trim() : null;
  };
  const enabled = (key) => val(key) === "1";

  const socks = enabled("SOCKSEnable") && val("SOCKSProxy") && val("SOCKSPort")
    ? `socks5://${val("SOCKSProxy")}:${val("SOCKSPort")}`
    : null;

  const https = enabled("HTTPSEnable") && val("HTTPSProxy") && val("HTTPSPort")
    ? `http://${val("HTTPSProxy")}:${val("HTTPSPort")}`
    : null;

  const http = enabled("HTTPEnable") && val("HTTPProxy") && val("HTTPPort")
    ? `http://${val("HTTPProxy")}:${val("HTTPPort")}`
    : null;

  const exceptions = [];
  const exMatch = text.match(/ExceptionsList\s*:\s*<array>\s*\{([^}]*)\}/);
  if (exMatch) {
    const lines = exMatch[1].split(/\r?\n/);
    for (const line of lines) {
      const m = line.match(/^\s*\d+\s*:\s*(.+)/);
      if (m) exceptions.push(m[1].trim());
    }
  }

  return { socks, https, http, exceptions };
}

function detectMacSystemProxy() {
  if (process.platform !== "darwin") return null;
  if (_cachedSystemProxy !== undefined) return _cachedSystemProxy;
  try {
    const output = execFileSync("scutil", ["--proxy"], { encoding: "utf8", timeout: 3000 });
    _cachedSystemProxy = parseScutilOutput(output);
  } catch {
    _cachedSystemProxy = null;
  }
  return _cachedSystemProxy;
}

function resetCachedSystemProxy() {
  _cachedSystemProxy = undefined;
}

function matchesNoProxy(hostname, noProxyList) {
  if (!hostname || !Array.isArray(noProxyList) || noProxyList.length === 0) return false;
  const lower = hostname.toLowerCase();
  for (const entry of noProxyList) {
    const pattern = entry.trim().toLowerCase();
    if (!pattern) continue;
    if (pattern === "*") return true;
    if (pattern === "<local>" && !lower.includes(".")) return true;
    if (lower === pattern) return true;
    if (pattern.startsWith(".") && lower.endsWith(pattern)) return true;
    if (pattern.startsWith("*.") && (lower === pattern.slice(2) || lower.endsWith(pattern.slice(1)))) return true;
  }
  return false;
}

function resolveProxyUrl(targetUrl, env = process.env, { detectSystemProxy = detectMacSystemProxy } = {}) {
  let hostname = "";
  let isHttps = true;
  try {
    const u = new URL(targetUrl);
    hostname = u.hostname;
    isHttps = u.protocol === "https:";
  } catch {
    return null;
  }

  const envNoProxy = (env.NO_PROXY || env.no_proxy || "").split(",").map((s) => s.trim()).filter(Boolean);

  const explicit =
    env.MINICPM_PROXY
    || (isHttps ? (env.HTTPS_PROXY || env.https_proxy) : null)
    || env.HTTP_PROXY || env.http_proxy
    || env.ALL_PROXY || env.all_proxy
    || null;

  if (explicit) {
    const trimmed = explicit.trim();
    if (!trimmed) return null;
    if (matchesNoProxy(hostname, envNoProxy)) return null;
    return trimmed;
  }

  const sys = detectSystemProxy();
  if (sys) {
    const proxyUrl = sys.socks || (isHttps ? sys.https : sys.http) || sys.http || null;
    if (proxyUrl) {
      const allExceptions = envNoProxy.concat(sys.exceptions || []);
      if (matchesNoProxy(hostname, allExceptions)) return null;
      return proxyUrl;
    }
  }

  return null;
}

function createProxyAgent(targetUrl, env = process.env, opts) {
  const proxyUrl = resolveProxyUrl(targetUrl, env, opts);
  if (!proxyUrl) return undefined;

  try {
    const lower = proxyUrl.toLowerCase();
    if (lower.startsWith("socks") ) {
      const { SocksProxyAgent } = require("socks-proxy-agent");
      return new SocksProxyAgent(proxyUrl);
    }

    let isHttps = true;
    try { isHttps = new URL(targetUrl).protocol === "https:"; } catch {}

    if (isHttps) {
      const { HttpsProxyAgent } = require("https-proxy-agent");
      return new HttpsProxyAgent(proxyUrl);
    }

    const { HttpProxyAgent } = require("http-proxy-agent");
    return new HttpProxyAgent(proxyUrl);
  } catch {
    return undefined;
  }
}

module.exports = {
  parseScutilOutput,
  detectMacSystemProxy,
  resetCachedSystemProxy,
  matchesNoProxy,
  resolveProxyUrl,
  createProxyAgent,
};
