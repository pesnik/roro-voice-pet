"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert/strict");

const {
  parseScutilOutput,
  matchesNoProxy,
  resolveProxyUrl,
  createProxyAgent,
} = require("../src/proxy-agent");

describe("parseScutilOutput", () => {
  it("parses SOCKS, HTTPS, and HTTP proxy from scutil output", () => {
    const input = [
      "<dictionary> {",
      "  HTTPEnable : 1",
      "  HTTPPort : 10808",
      "  HTTPProxy : 127.0.0.1",
      "  HTTPSEnable : 1",
      "  HTTPSPort : 443",
      "  HTTPSProxy : proxy.example.com",
      "  SOCKSEnable : 1",
      "  SOCKSPort : 1080",
      "  SOCKSProxy : socks.example.com",
      "}",
    ].join("\n");
    const result = parseScutilOutput(input);
    assert.equal(result.socks, "socks5://socks.example.com:1080");
    assert.equal(result.https, "http://proxy.example.com:443");
    assert.equal(result.http, "http://127.0.0.1:10808");
  });

  it("returns null for disabled proxy types", () => {
    const input = [
      "<dictionary> {",
      "  HTTPEnable : 0",
      "  HTTPPort : 10808",
      "  HTTPProxy : 127.0.0.1",
      "  SOCKSEnable : 0",
      "}",
    ].join("\n");
    const result = parseScutilOutput(input);
    assert.equal(result.socks, null);
    assert.equal(result.https, null);
    assert.equal(result.http, null);
  });

  it("parses ExceptionsList", () => {
    const input = [
      "<dictionary> {",
      "  ExceptionsList : <array> {",
      "    0 : 127.0.0.1",
      "    1 : *.local",
      "    2 : localhost",
      "  }",
      "  SOCKSEnable : 1",
      "  SOCKSPort : 1080",
      "  SOCKSProxy : 127.0.0.1",
      "}",
    ].join("\n");
    const result = parseScutilOutput(input);
    assert.deepEqual(result.exceptions, ["127.0.0.1", "*.local", "localhost"]);
  });

  it("handles empty or invalid input gracefully", () => {
    assert.deepEqual(parseScutilOutput("").exceptions, []);
    assert.equal(parseScutilOutput("").socks, null);
    assert.equal(parseScutilOutput(null).socks, null);
    assert.equal(parseScutilOutput(undefined).socks, null);
  });
});

describe("matchesNoProxy", () => {
  it("matches exact hostname", () => {
    assert.ok(matchesNoProxy("example.com", ["example.com"]));
  });

  it("matches domain suffix with leading dot", () => {
    assert.ok(matchesNoProxy("sub.example.com", [".example.com"]));
    assert.ok(!matchesNoProxy("example.com", [".example.com"]));
  });

  it("matches wildcard patterns", () => {
    assert.ok(matchesNoProxy("anything.local", ["*.local"]));
    assert.ok(matchesNoProxy("local", ["*.local"]));
  });

  it("matches * wildcard for all hosts", () => {
    assert.ok(matchesNoProxy("any.host.com", ["*"]));
  });

  it("matches <local> for hostnames without dots", () => {
    assert.ok(matchesNoProxy("localhost", ["<local>"]));
    assert.ok(!matchesNoProxy("some.host.com", ["<local>"]));
  });

  it("is case-insensitive", () => {
    assert.ok(matchesNoProxy("Example.COM", ["example.com"]));
  });

  it("returns false for empty inputs", () => {
    assert.ok(!matchesNoProxy("example.com", []));
    assert.ok(!matchesNoProxy("", ["example.com"]));
    assert.ok(!matchesNoProxy(null, ["example.com"]));
  });
});

const noSys = { detectSystemProxy: () => null };

describe("resolveProxyUrl", () => {
  it("returns MINICPM_PROXY when set", () => {
    const env = { MINICPM_PROXY: "socks5://127.0.0.1:1080" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "socks5://127.0.0.1:1080");
  });

  it("returns HTTPS_PROXY for https targets", () => {
    const env = { HTTPS_PROXY: "http://proxy:8080" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "http://proxy:8080");
  });

  it("falls back to HTTP_PROXY for https targets", () => {
    const env = { HTTP_PROXY: "http://proxy:8080" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "http://proxy:8080");
  });

  it("returns ALL_PROXY as fallback", () => {
    const env = { ALL_PROXY: "socks5://127.0.0.1:1080" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "socks5://127.0.0.1:1080");
  });

  it("respects lowercase env vars", () => {
    const env = { https_proxy: "http://proxy:8080" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "http://proxy:8080");
  });

  it("returns null when NO_PROXY matches", () => {
    const env = { HTTPS_PROXY: "http://proxy:8080", NO_PROXY: "example.com,other.com" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), null);
  });

  it("returns null when no proxy is configured and no system proxy", () => {
    assert.equal(resolveProxyUrl("https://example.com", {}, noSys), null);
  });

  it("returns null for invalid target URL", () => {
    assert.equal(resolveProxyUrl("not a url", {}, noSys), null);
  });

  it("prefers MINICPM_PROXY over HTTPS_PROXY", () => {
    const env = { MINICPM_PROXY: "socks5://a:1", HTTPS_PROXY: "http://b:2" };
    assert.equal(resolveProxyUrl("https://example.com", env, noSys), "socks5://a:1");
  });

  it("falls back to system proxy when no env vars set", () => {
    const mockSys = { detectSystemProxy: () => ({ socks: "socks5://sys:1080", https: null, http: null, exceptions: [] }) };
    assert.equal(resolveProxyUrl("https://example.com", {}, mockSys), "socks5://sys:1080");
  });

  it("respects system proxy exceptions", () => {
    const mockSys = { detectSystemProxy: () => ({ socks: "socks5://sys:1080", https: null, http: null, exceptions: ["example.com"] }) };
    assert.equal(resolveProxyUrl("https://example.com", {}, mockSys), null);
  });
});

describe("createProxyAgent", () => {
  it("returns undefined when no proxy is configured", () => {
    assert.equal(createProxyAgent("https://example.com", {}, noSys), undefined);
  });

  it("returns a SocksProxyAgent for socks5 proxy", () => {
    const agent = createProxyAgent("https://example.com", { MINICPM_PROXY: "socks5://127.0.0.1:1080" }, noSys);
    assert.ok(agent);
    assert.ok(agent.constructor.name === "SocksProxyAgent");
  });

  it("returns an HttpsProxyAgent for http proxy with https target", () => {
    const agent = createProxyAgent("https://example.com", { HTTPS_PROXY: "http://proxy:8080" }, noSys);
    assert.ok(agent);
    assert.ok(agent.constructor.name === "HttpsProxyAgent");
  });

  it("returns an HttpProxyAgent for http proxy with http target", () => {
    const agent = createProxyAgent("http://example.com", { HTTP_PROXY: "http://proxy:8080" }, noSys);
    assert.ok(agent);
    assert.ok(agent.constructor.name === "HttpProxyAgent");
  });
});
