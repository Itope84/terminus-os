/** Tests for GET /api/turn exact-turnId registry reads (pure, no servers).
 *  Run: npm test   (builds first, then node --test)
 *
 *  Contract: a retained completion reads back by exact chat+turn key with
 *  its messageID/error intact; anything absent (or expired) is unknown —
 *  never failure, never a neighbor turn's result (merged-turn safety).
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readTurnResult, turnResults } from "../dist/opencode-proxy.js";

describe("readTurnResult", () => {
  it("unknown for absent keys", () => {
    assert.equal(readTurnResult("no-chat", "t-nope"), null);
  });

  it("returns the exact turn's result with messageID", () => {
    turnResults.set("c1:t1", { chatId: "c1", turnId: "t1", text: "hello", model: null, at: Date.now(), messageID: "m1" });
    turnResults.set("c1:t2", { chatId: "c1", turnId: "t2", text: "later", model: null, at: Date.now(), messageID: "m2" });
    try {
      const r = readTurnResult("c1", "t1");
      assert.equal(r?.text, "hello");
      assert.equal(r?.messageID, "m1");
      // Neighbor turn never leaks across.
      assert.equal(readTurnResult("c1", "t2")?.messageID, "m2");
      assert.equal(readTurnResult("c2", "t1"), null);
    } finally {
      turnResults.delete("c1:t1");
      turnResults.delete("c1:t2");
    }
  });

  it("carries error outcomes intact", () => {
    turnResults.set("c9:t9", { chatId: "c9", turnId: "t9", text: "", model: null, at: Date.now(), error: "session expired — send again for a fresh one" });
    try {
      const r = readTurnResult("c9", "t9");
      assert.equal(r?.error, "session expired — send again for a fresh one");
    } finally {
      turnResults.delete("c9:t9");
    }
  });

  it("expired entries read unknown (7-day completion retention)", () => {
    turnResults.set("c0:t0", { chatId: "c0", turnId: "t0", text: "stale", model: null, at: Date.now() - 8 * 24 * 60 * 60 * 1000 });
    assert.equal(readTurnResult("c0", "t0"), null);
    assert.equal(turnResults.has("c0:t0"), false);
  });
  it("hour-old completions survive for late readers", () => {
    turnResults.set("c0:t1", { chatId: "c0", turnId: "t1", text: "late", model: null, at: Date.now() - 60 * 60 * 1000 });
    try {
      assert.equal(readTurnResult("c0", "t1")?.text, "late");
      assert.equal(turnResults.has("c0:t1"), true);
    } finally {
      turnResults.delete("c0:t1");
    }
  });
});

describe("turnStatus (settled/running/unknown)", () => {
  it("accepted-but-unsettled reads running, never resend", async () => {
    const { turnStatus, pendingByChat } = await import("../dist/opencode-proxy.js");
    pendingByChat.set("cs", [{ turnId: "t-run", at: Date.now() }]);
    try {
      assert.equal(turnStatus("cs", "t-run"), "running");
      assert.equal(turnStatus("cs", "t-never"), "unknown");
    } finally {
      pendingByChat.delete("cs");
    }
  });
  it("long turns stay running past the old 10-minute horizon", async () => {
    const { turnStatus, pendingByChat, PENDING_TTL_MS, LONG_TURN_CAP_MS } = await import("../dist/opencode-proxy.js");
    assert.ok(PENDING_TTL_MS > LONG_TURN_CAP_MS);
    // 30 minutes in: past the old accept TTL, well within a legal run.
    pendingByChat.set("cl", [{ turnId: "t-long", at: Date.now() - 30 * 60 * 1000 }]);
    try {
      assert.equal(turnStatus("cl", "t-long"), "running");
    } finally {
      pendingByChat.delete("cl");
    }
  });
  it("settled beats pending", async () => {
    const { turnStatus, turnResults, pendingByChat } = await import("../dist/opencode-proxy.js");
    turnResults.set("cb:tb", { chatId: "cb", turnId: "tb", text: "done", model: null, at: Date.now() });
    pendingByChat.set("cb", [{ turnId: "tb", at: Date.now() }]);
    try {
      assert.equal(turnStatus("cb", "tb"), "settled");
    } finally {
      turnResults.delete("cb:tb");
      pendingByChat.delete("cb");
    }
  });
  it("settled errors carry a machine-readable code", async () => {
    const { readTurnResult, turnResults } = await import("../dist/opencode-proxy.js");
    turnResults.set("cc:tc", { chatId: "cc", turnId: "tc", text: "", model: null, at: Date.now(), error: "boom", code: "provider_error" });
    try {
      assert.equal(readTurnResult("cc", "tc")?.code, "provider_error");
    } finally {
      turnResults.delete("cc:tc");
    }
  });
});

describe("delta attribution (overlap safety)", () => {
  it("known message stays with its owner when a newer turn exists", async () => {
    const { ownerTurnForDelta } = await import("../dist/opencode-proxy.js");
    const pending = [{ turnId: "tA" }, { turnId: "tB" }];
    assert.equal(ownerTurnForDelta(pending, { chatId: "c", turnId: "tA" }), "tA");
  });
  it("single pending turn owns unknown messages", async () => {
    const { ownerTurnForDelta } = await import("../dist/opencode-proxy.js");
    assert.equal(ownerTurnForDelta([{ turnId: "tA" }], undefined), "tA");
  });
  it("multiple pending turns route unknown messages to the newest", async () => {
    const { ownerTurnForDelta } = await import("../dist/opencode-proxy.js");
    assert.equal(ownerTurnForDelta([{ turnId: "tA" }, { turnId: "tB" }], undefined), "tB");
  });
  it("no pending turns yields null (caller keeps legacy tag, never drops)", async () => {
    const { ownerTurnForDelta } = await import("../dist/opencode-proxy.js");
    assert.equal(ownerTurnForDelta([], undefined), null);
  });
  it("newestAssistantId picks the chronologically newest non-empty row", async () => {
    const { newestAssistantId } = await import("../dist/opencode-proxy.js");
    const rows = [
      { type: "assistant", time: { created: 300 }, content: [{ type: "text", text: "new" }], id: "m3" },
      { type: "assistant", time: { created: 100 }, content: [{ type: "text", text: "old" }], id: "m1" },
      { type: "user", time: { created: 200 }, content: [] },
      { type: "assistant", time: { created: 250 }, content: [{ type: "text", text: "  " }] },
    ];
    assert.equal(newestAssistantId(rows), "m3");
    assert.equal(newestAssistantId([]), "");
  });
});

describe("shouldEmitDelta (no-guess live placement)", () => {
  it("certain owners and lone turns stream; guesses stay silent", async () => {
    const { shouldEmitDelta } = await import("../dist/opencode-proxy.js");
    assert.equal(shouldEmitDelta({ certain: true }, 3), true);
    assert.equal(shouldEmitDelta({ certain: false }, 3), false);
    assert.equal(shouldEmitDelta(undefined, 1), true);
    assert.equal(shouldEmitDelta(undefined, 0), true);
    assert.equal(shouldEmitDelta(undefined, 2), false);
  });
});

describe("replayableDones (bounded reconnect replay)", () => {
  it("replays only turns newer than the floor, sorted, chat-scoped", async () => {
    const { replayableDones, DONE_REPLAY_WINDOW_MS } = await import("../dist/opencode-proxy.js");
    assert.equal(DONE_REPLAY_WINDOW_MS, 15 * 60_000);
    const mk = (chatId, turnId, at) => ({ chatId, turnId, text: "t", model: null, at });
    const out = replayableDones(
      [mk("c1", "t-new", 300), mk("c1", "t-old", 100), mk("c2", "t-other", 400), mk("c1", "t-mid", 200)],
      "c1", 150,
    );
    assert.deepEqual(out.map((t) => t.turnId), ["t-mid", "t-new"]);
  });
  it("floor 0 replays everything (explicit full catch-up)", async () => {
    const { replayableDones } = await import("../dist/opencode-proxy.js");
    const mk = (chatId, turnId, at) => ({ chatId, turnId, text: "t", model: null, at });
    const out = replayableDones([mk("c1", "a", 10), mk("c1", "b", 20)], "c1", 0);
    assert.equal(out.length, 2);
  });
});
