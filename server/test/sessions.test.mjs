/** Tests for GET /api/sessions row mapping + session-id input checks
 *  (pure, no servers). Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mapSessionEntries, cleanSessionID, otherSessionID, isNoSession } from "../dist/opencode-proxy.js";

describe("mapSessionEntries", () => {
  it("returns [] for null/garbage", () => {
    assert.deepEqual(mapSessionEntries(null), []);
    assert.deepEqual(mapSessionEntries({}), []);
    assert.deepEqual(mapSessionEntries({ data: "nope" }), []);
  });

  it("maps array and {data} envelope shapes", () => {
    const row = { id: "ses_1", title: "hello", time: { created: 10, updated: 20 } };
    assert.deepEqual(mapSessionEntries([row]), [{ id: "ses_1", title: "hello", updatedAt: 20 }]);
    assert.deepEqual(mapSessionEntries({ data: [row] }), [{ id: "ses_1", title: "hello", updatedAt: 20 }]);
  });

  it("prefers updated over created, falls back to updatedAt then 0", () => {
    assert.equal(mapSessionEntries([{ id: "a", time: { created: 5 } }])[0].updatedAt, 5);
    assert.equal(mapSessionEntries([{ id: "a", updatedAt: 7 }])[0].updatedAt, 7);
    assert.equal(mapSessionEntries([{ id: "a" }])[0].updatedAt, 0);
  });

  it("accepts sessionID id key and message_count variants", () => {
    const rows = mapSessionEntries([{ sessionID: "ses_9", title: "t", time: { created: 1 }, message_count: 12 }]);
    assert.deepEqual(rows, [{ id: "ses_9", title: "t", updatedAt: 1, messageCount: 12 }]);
  });

  it("omits messageCount when absent, keeps it when a finite number", () => {
    const [a] = mapSessionEntries([{ id: "x", time: { created: 1 }, messageCount: 3 }]);
    assert.equal(a.messageCount, 3);
    const [b] = mapSessionEntries([{ id: "y", time: { created: 1 } }]);
    assert.ok(!("messageCount" in b));
  });

  it("skips rows without an id and blanks the title when missing", () => {
    const rows = mapSessionEntries([{ title: "no id" }, { id: "", title: "blank" }, { id: "ok" }]);
    assert.deepEqual(rows, [{ id: "ok", title: "", updatedAt: 0 }]);
  });
});

describe("cleanSessionID", () => {
  it("accepts ses_-shaped ids, trimmed", () => {
    assert.equal(cleanSessionID("ses_abc123"), "ses_abc123");
    assert.equal(cleanSessionID("  ses_f21be1bbdffebJAajyJWUhQ0Ib  "), "ses_f21be1bbdffebJAajyJWUhQ0Ib");
  });

  it("blanks absent and malformed input — never a lookup", () => {
    assert.equal(cleanSessionID(""), "");
    assert.equal(cleanSessionID(undefined), "");
    assert.equal(cleanSessionID(null), "");
    assert.equal(cleanSessionID(42), "");
    assert.equal(cleanSessionID("s-1790314193129"), "");
    assert.equal(cleanSessionID("ses_"), "");
    assert.equal(cleanSessionID("ses_a/b"), "");
    assert.equal(cleanSessionID("../chat-sessions.json"), "");
  });

  it("trims overlong input to 200 chars", () => {
    const out = cleanSessionID("ses_" + "a".repeat(300));
    assert.equal(out.length, 200);
    assert.ok(out.startsWith("ses_"));
  });
});

describe("isNoSession", () => {
  it("matches opencode not-found shapes", () => {
    assert.ok(isNoSession({ message: "session not found" }));
    assert.ok(isNoSession({ data: { message: "No such session" } }));
    assert.ok(isNoSession({ _tag: "404" }));
    assert.ok(isNoSession(new Error("gone")));
  });

  it("rejects transport errors and successes", () => {
    assert.ok(!isNoSession(new Error("fetch failed")));
    assert.ok(!isNoSession({}));
    assert.ok(!isNoSession(null));
  });
});

describe("otherSessionID (claude/codex ids on /api/compact)", () => {
  it("accepts uuids and codex-style ids", () => {
    assert.equal(otherSessionID("dbc6eff4-0cd4-4f3b-b7fc-c985fb899522").ok, true);
    assert.equal(otherSessionID(" 019a-aaaa-bbbb ").sessionID, "019a-aaaa-bbbb");
  });
  it("rejects paths, spaces, short and non-strings", () => {
    for (const v of ["", "../etc/passwd", "abc", "a b c d e f g h", "x/y/z/uuid-uuid", 5, null, undefined]) assert.equal(otherSessionID(v).ok, false);
  });
});
