import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveOriginChat, cleanKind, cleanPriority } from "./origin.js";

const mem = () => new Map<string, { chat: string; at: number }>();

test("tab that is a running chat id wins", () => {
  const r = resolveOriginChat({ tab: "s-1", running: ["s-1", "s-2"], remembered: mem() });
  assert.equal(r.chat, "s-1");
});

test("known chat id works even when its turn just finished", () => {
  const r = resolveOriginChat({ tab: "s-9", running: [], known: new Set(["s-9"]), remembered: mem() });
  assert.equal(r.chat, "s-9");
});

test("single running chat is the origin, and the agent's tab is learned", () => {
  const m = mem();
  const r = resolveOriginChat({ tab: "abcd", running: ["s-5"], remembered: m });
  assert.equal(r.chat, "s-5");
  assert.deepEqual(r.learn, ["abcd", "s-5"]);
  m.set("abcd", { chat: "s-5", at: Date.now() });
  // later another chat starts too: the remembered tab still resolves
  const r2 = resolveOriginChat({ tab: "abcd", running: ["s-5", "s-6"], remembered: m });
  assert.equal(r2.chat, "s-5");
});

test("two running chats and an unseen tab is ambiguous: null (Alerts)", () => {
  const r = resolveOriginChat({ tab: "wxyz", running: ["s-1", "s-2"], remembered: mem() });
  assert.equal(r.chat, null);
});

test("no tab, one running chat", () => {
  assert.equal(resolveOriginChat({ running: ["s-3"], remembered: mem() }).chat, "s-3");
});

test("no tab, nothing running", () => {
  assert.equal(resolveOriginChat({ running: [], remembered: mem() }).chat, null);
});

test("remembered tab expires after an hour", () => {
  const m = mem();
  m.set("abcd", { chat: "s-5", at: 1000 });
  const r = resolveOriginChat({ tab: "abcd", running: [], remembered: m, now: 1000 + 61 * 60 * 1000 });
  assert.equal(r.chat, null);
});

test("duplicate running entries count once", () => {
  assert.equal(resolveOriginChat({ running: ["s-1", "s-1"], remembered: mem() }).chat, "s-1");
});

test("kind and priority only accept known values", () => {
  assert.equal(cleanKind("event"), "event");
  assert.equal(cleanKind("evil"), undefined);
  assert.equal(cleanPriority("high"), "high");
  assert.equal(cleanPriority(1), undefined);
});
