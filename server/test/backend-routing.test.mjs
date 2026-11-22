/** Backend-aware routing tests (multi-backend spec M4a + P16-P18).
 *  Pure unit level (no live provider): query parsing, defaults merge,
 *  catalog isolation rules. Live shape checks stay in the mirror tier.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  applyDefaultsPatch,
  backendFromQuery,
  backendOfChat,
  bindOrValidateBackend,
  checkRouteBackend,
  resolveRouteBackend,
  sanitizeDefaultsInput,
} from "../dist/opencode-proxy.js";

const req = (url) => ({ url });

describe("backendFromQuery (M4a)", () => {
  it("missing/blank backend means opencode", () => {
    assert.equal(backendFromQuery(req("/api/models")), "opencode");
    assert.equal(backendFromQuery(req("/api/models?backend=")), "opencode");
    assert.equal(backendFromQuery(req("/api/models?backend=  ")), "opencode");
  });
  it("accepts opencode", () => {
    assert.equal(backendFromQuery(req("/api/models?backend=opencode")), "opencode");
  });
  it("rejects unknown ids; registered backends resolve (M9: all three)", () => {
    assert.throws(() => backendFromQuery(req("/api/models?backend=nope")), /unknown backend/);
    assert.equal(backendFromQuery(req("/api/models?backend=codex")), "codex");
    assert.equal(backendFromQuery(req("/api/models?backend=claude")), "claude");
  });
});

describe("chat backend binding (M6, P16/P19)", () => {
  it("binds on first sight, holds, reports mismatch — reads never bind", () => {
    const chat = `c-m6-${Date.now()}`;
    assert.deepEqual(bindOrValidateBackend(chat, "opencode"), { ok: true });
    assert.deepEqual(bindOrValidateBackend(chat, "opencode"), { ok: true });
    assert.deepEqual(bindOrValidateBackend(chat, "codex"), { ok: false, expected: "opencode" });
    assert.equal(backendOfChat(chat), "opencode");
    const fresh = `c-m6-fresh-${Date.now()}`;
    assert.deepEqual(checkRouteBackend(fresh, "opencode"), { ok: true });
    assert.equal(backendOfChat(fresh), undefined);
    assert.deepEqual(checkRouteBackend(chat, "codex"), { ok: false, expected: "opencode" });
    assert.deepEqual(checkRouteBackend(chat, "opencode"), { ok: true });
  });
  it("resolveRouteBackend: default opencode, 400 unknown, 409 mismatch", () => {
    const calls = [];
    const res = {};
    const json = (_r, status, body) => { calls.push({ status, body }); };
    const chat = `c-m6-r-${Date.now()}`;
    assert.equal(resolveRouteBackend(null, res, json, chat, "getStatus"), "opencode");
    assert.equal(calls.length, 0);
    assert.equal(resolveRouteBackend("  ", res, json, chat, "getStatus"), "opencode");
    assert.equal(resolveRouteBackend("nope", res, json, chat, "getStatus"), null);
    assert.equal(calls.at(-1).status, 400);
    // Registered: resolves on an unbound chat (reads never bind).
    assert.equal(resolveRouteBackend("codex", res, json, chat, "getStatus"), "codex");
    assert.equal(calls.length, 1);
    assert.deepEqual(bindOrValidateBackend(chat, "opencode"), { ok: true });
    // Bound chat + foreign backend: 409 backend_mismatch, never execution.
    assert.equal(resolveRouteBackend("codex", res, json, chat, "getStatus"), null);
    assert.equal(calls.at(-1).status, 409);
    assert.equal(calls.at(-1).body.error, "backend_mismatch");
    assert.equal(resolveRouteBackend("opencode", res, json, chat, "getStatus"), "opencode");
  });
});

describe("handleStop backend-first validation (bug-1)", () => {
  let EventEmitter = null;
  let mod = null;
  const ready = (async () => {
    ({ EventEmitter } = await import("node:events"));
    mod = await import("../dist/opencode-proxy.js");
  })();
  const fakeReq = (body) => {
    const req = new EventEmitter();
    req.url = "/api/stop";
    req.method = "POST";
    req.headers = {};
    process.nextTick(() => {
      req.emit("data", Buffer.from(JSON.stringify(body)));
      req.emit("end");
    });
    return req;
  };
  const callStop = async (body) => {
    await ready;
    let out = null;
    const res = {};
    const json = (_r, status, b) => { out = { status, body: b }; };
    await mod.handleStop(fakeReq(body), res, json);
    return out;
  };
  it("codex UUID session reaches the backend (never 400 bad sessionID)", async () => {
    const chat = `c-stop-${Date.now()}-a`;
    const r = await callStop({ sessionID: "01a115f3-75f1-7620-84c4-57e8c0a9cd9b", chatId: chat, backend: "codex" });
    assert.equal(r.status, 200);
    assert.equal(r.body.backend, "codex");
  });
  it("blank session id with a chat is stop-by-chat (never 400)", async () => {
    const chat = `c-stop-${Date.now()}-b`;
    const r = await callStop({ chatId: chat, backend: "claude" });
    assert.equal(r.status, 200);
  });
  it("opencode keeps ses_ validation + blank means nothing running", async () => {
    const bad = await callStop({ sessionID: "not-a-session!!!" });
    assert.equal(bad.status, 400);
    const blank = await callStop({});
    assert.deepEqual(blank, { status: 200, body: { ok: true, stopped: false } });
  });
  it("unknown backend 400s, mismatch 409s", async () => {
    const unk = await callStop({ sessionID: "x", backend: "nope" });
    assert.equal(unk.status, 400);
    const chat = `c-stop-${Date.now()}-c`;
    mod.bindOrValidateBackend(chat, "opencode");
    const mm = await callStop({ sessionID: "th-1", chatId: chat, backend: "codex" });
    assert.equal(mm.status, 409);
  });
});

describe("defaults backend merge (M4a, P16-P18)", () => {
  const stored = {
    defaultBackend: "opencode",
    defaultModels: { opencode: "openai/gpt-5" },
    defaultModel: "openai/gpt-5",
    systemPrompt: "s",
    seedVersion: 3,
    previousSystemPrompt: "",
  };
  it("switching backend keeps the other backend's model (P18)", () => {
    const next = applyDefaultsPatch(stored, { defaultBackend: "opencode", defaultModels: { opencode: "openai/gpt-5.1" } });
    assert.equal(next.defaultModels.opencode, "openai/gpt-5.1");
    const withCodex = applyDefaultsPatch(next, { defaultModels: { codex: "gpt-5.6-codex" } });
    assert.equal(withCodex.defaultModels.opencode, "openai/gpt-5.1");
    assert.equal(withCodex.defaultModels.codex, "gpt-5.6-codex");
    assert.equal(withCodex.defaultBackend, "opencode");
  });
  it("legacy defaultModel targets the effective backend", () => {
    const next = applyDefaultsPatch(stored, { defaultModel: "openai/gpt-5.2" });
    assert.equal(next.defaultModels.opencode, "openai/gpt-5.2");
    assert.equal(next.defaultModel, "openai/gpt-5.2");
  });
  it("sanitize accepts backend + per-backend models, rejects bad shapes", () => {
    assert.deepEqual(
      sanitizeDefaultsInput({ defaultBackend: "opencode", defaultModels: { opencode: "openai/gpt-5" } }),
      { ok: true, value: { defaultBackend: "opencode", defaultModels: { opencode: "openai/gpt-5" } } },
    );
    assert.equal(sanitizeDefaultsInput({ defaultBackend: "nope" }).ok, false);
    assert.equal(sanitizeDefaultsInput({ defaultBackend: "codex" }).ok, true);
    assert.equal(sanitizeDefaultsInput({ defaultModels: { nope: "x" } }).ok, false);
    assert.equal(sanitizeDefaultsInput({ defaultModels: { opencode: "gpt-5" } }).ok, false);
    assert.equal(sanitizeDefaultsInput({ defaultModels: "x" }).ok, false);
  });
});
