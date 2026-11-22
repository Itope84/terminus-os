/** Tests for POST /api/chat retry + reconcile helpers (pure + fakes, no servers).
 *  Run: npm test   (builds first, then node --test)
 *
 *  Contract: prompt-stage transport failures retry with backoff, but a
 *  resend never goes out without first checking the prompt didn't already
 *  land (send-once). wait/list failures reconcile once before failing.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  PROMPT_RETRY_DELAYS_MS,
  isReplyCompletedAfter,
  isSessionGone,
  listedTokens,
  pickReplyFromList,
  promptLandedInList,
  promptWithBackoff,
  historyFromMessages,
} from "../dist/opencode-proxy.js";

const txt = (text) => ({ type: "text", text });
const user = (at, text) => ({ type: "user", time: { created: at }, text, content: [] });
const asst = (at, text) => ({
  type: "assistant",
  time: { created: at },
  content: [txt(text)],
});

describe("PROMPT_RETRY_DELAYS_MS", () => {
  it("backs off 500ms, 1.5s, 4s", () => {
    assert.deepEqual(PROMPT_RETRY_DELAYS_MS, [500, 1500, 4000]);
  });
});

describe("isSessionGone", () => {
  it("catches dead-session shapes", () => {
    assert.equal(isSessionGone("Session not found: ses_abc"), true);
    assert.equal(isSessionGone("no such session"), true);
    assert.equal(isSessionGone("request failed 404"), true);
    assert.equal(isSessionGone("session gone"), true);
  });
  it("passes transients through", () => {
    assert.equal(isSessionGone("Transport error"), false);
    assert.equal(isSessionGone("socket hang up"), false);
    assert.equal(isSessionGone("opencode unavailable (prompt)"), false);
  });
});

describe("pickReplyFromList", () => {
  it("carries the picked row's token counters", () => {
    const row = {
      ...asst(9000, "fresh answer"),
      tokens: { input: 900, output: 100, reasoning: 20, cache: { read: 5_000, write: 40 } },
    };
    assert.equal(pickReplyFromList([row], 1)?.tokens, 6_020);
    // A row without counters simply carries none.
    assert.equal(pickReplyFromList([asst(9000, "fresh answer")], 1)?.tokens, undefined);
  });

  it("listedTokens sums prompt, generated, reasoning and cache reads", () => {
    assert.equal(listedTokens({ tokens: { input: 10, output: 2, reasoning: 3, cache: { read: 5 } } }), 20);
    assert.equal(listedTokens({}), 0);
    assert.equal(listedTokens({ tokens: { input: "x", output: -4, reasoning: 1 } }), 1);
  });
  it("prefers the in-window reply, misses explicitly out of window", () => {
    // Caller passes message.list order: newest first.
    const desc = [asst(9000, "fresh answer"), asst(1000, "old answer")];
    assert.equal(pickReplyFromList(desc, 9000)?.text, "fresh answer");
    // Window empty (older than since - 60s): null, never a wrong older row.
    // Callers record "no assistant reply recorded" on null.
    assert.equal(pickReplyFromList([asst(1000, "old answer")], 70_000), null);
  });
  it("returns null when nothing non-empty", () => {
    assert.equal(pickReplyFromList([], 1000), null);
    assert.equal(
      pickReplyFromList([{ type: "assistant", time: { created: 5 }, content: [] }], 5)?.text ?? null,
      null,
    );
  });
  it("extracts messageID from id/messageID/messageId", () => {
    const mk = (idObj) => ({ type: "assistant", time: { created: 1 }, content: [txt("r")], ...idObj });
    assert.equal(pickReplyFromList([mk({ id: "a" })], 1)?.messageID, "a");
    assert.equal(pickReplyFromList([mk({ messageID: "b" })], 1)?.messageID, "b");
    assert.equal(pickReplyFromList([mk({ messageId: "c" })], 1)?.messageID, "c");
    assert.equal(pickReplyFromList([mk({})], 1)?.messageID, undefined);
  });
  it("prefers the terminal reply over nearer interim text", () => {
    // Agentic turn: preamble (tool-calls) lands right after the prompt,
    // the real answer (stop) later. Caller passes newest first.
    const fin = (at, text, finish) => ({ ...asst(at, text), finish, time: { created: at, completed: at } });
    const desc = [fin(9000, "final answer", "stop"), fin(2000, "checking…", "tool-calls")];
    const picked = pickReplyFromList(desc, 1000);
    assert.equal(picked?.text, "final answer");
  });
  it("keeps overlap attribution per turn with terminal replies", () => {
    const fin = (at, id, text, finish) => ({ ...asst(at, text), id, finish, time: { created: at, completed: at } });
    // A prompts at 1000, B at 5000; each owns its own stop reply.
    // Caller passes newest first; B's worker excludes A's claimed message,
    // exactly as settledTurnsOf() provides in production.
    const desc = [fin(9000, "mB", "reply B", "stop"), fin(3000, "mA", "reply A", "stop")];
    assert.equal(pickReplyFromList(desc, 1000)?.text, "reply A");
    assert.equal(
      pickReplyFromList(desc, 5000, new Map([["mA", "tA"]]), "tB")?.text,
      "reply B",
    );
  });
  it("falls back to nearest when no finish info exists", () => {
    const desc = [asst(9000, "later"), asst(2000, "earlier")];
    assert.equal(pickReplyFromList(desc, 1000)?.text, "earlier");
  });
  it("keeps the shared in-window message for merged prompts", () => {
    // Both turns fused into one opencode message: the second turn settles
    // with it too, and the client's messageID merge rule renders one row.
    const shared = { ...asst(5000, "shared answer"), id: "mS" };
    const excl = new Map([["mS", "tA"]]);
    assert.equal(pickReplyFromList([shared], 1000, excl, "tB")?.text, "shared answer");
  });
  it("P10: stale terminal relist loses to the fresh completion", () => {
    const finDone = (created, completed, id, text) => ({
      type: "assistant",
      id,
      finish: "stop",
      time: { created, completed },
      content: [txt(text)],
    });
    const since = 5000;
    // Relist bumped created just past the prompt, but it finished before it.
    const stale = finDone(5100, 4000, "mOld", "stale final");
    const fresh = finDone(9000, 9500, "mNew", "fresh reply");
    assert.equal(pickReplyFromList([fresh, stale], since)?.text, "fresh reply");
  });
  it("P10: completed == since counts (>= boundary)", () => {
    const m = {
      type: "assistant",
      id: "mE",
      finish: "stop",
      time: { created: 5000, completed: 5000 },
      content: [txt("edge")],
    };
    assert.equal(pickReplyFromList([m], 5000)?.text, "edge");
    assert.equal(isReplyCompletedAfter(m, 5000), true);
    assert.equal(isReplyCompletedAfter(m, 5001), false);
  });
  it("P10: missing completed never counts as finished, still settles as text", () => {
    assert.equal(isReplyCompletedAfter({ type: "assistant", time: { created: 6000 } }, 5000), false);
    const noDone = {
      type: "assistant",
      id: "mN",
      finish: "stop",
      time: { created: 6000 },
      content: [txt("plain")],
    };
    assert.equal(pickReplyFromList([noDone], 5000)?.text, "plain");
  });
});

describe("waitForEarlierTurns order gate", () => {
  it("true when first, false on timeout while blocked, true after release", async () => {
    const { trackPending, untrackPending, waitForEarlierTurns } = await import("../dist/opencode-proxy.js");
    const chat = `c-gate-${Date.now()}`;
    trackPending(chat, "t1");
    assert.equal(await waitForEarlierTurns(chat, "t1", 50), true);
    trackPending(chat, "t2");
    assert.equal(await waitForEarlierTurns(chat, "t2", 60), false);
    untrackPending(chat, "t1");
    assert.equal(await waitForEarlierTurns(chat, "t2", 50), true);
    untrackPending(chat, "t2");
  });
});

describe("promptLandedInList", () => {  it("finds our prompt text at/after the attempt start", () => {
    const data = [user(100, "earlier"), user(200, "hello world")];
    assert.equal(promptLandedInList(data, "hello world", 150), 200);
  });
  it("ignores older copies, other roles, and blanks", () => {
    const data = [user(100, "hello world"), asst(200, "hello world")];
    assert.equal(promptLandedInList(data, "hello world", 150), 0);
    assert.equal(promptLandedInList(data, "   ", 0), 0);
    assert.equal(promptLandedInList([], "hello world", 0), 0);
  });
});

describe("promptWithBackoff", () => {
  const transportErr = () => {
    const e = new Error("Transport error");
    e.cause = { code: "ECONNRESET" };
    return e;
  };
  const fake = (impl) => ({
    sessionPrompt: impl.prompt,
    sessionWait: impl.wait ?? (async () => {}),
    messageList: impl.list ?? (async () => []),
  });

  it("returns inbox time on first-try success", async () => {
    let calls = 0;
    const rc = fake({ prompt: async () => { calls++; return { time: { created: 42 } }; } });
    assert.equal(await promptWithBackoff(rc, "s", {}, "hi", "c"), 42);
    assert.equal(calls, 1);
  });

  it("retries transport failures with backoff, then succeeds", async () => {
    let calls = 0;
    const rc = fake({
      prompt: async () => { calls++; if (calls < 3) throw transportErr(); return { time: { created: 7 } }; },
    });
    assert.equal(await promptWithBackoff(rc, "s", {}, "hi", "c"), 7);
    assert.equal(calls, 3);
  });

  it("throws non-transport errors immediately without retry", async () => {
    let calls = 0;
    const rc = fake({ prompt: async () => { calls++; throw new Error("Session not found: ses_x"); } });
    await assert.rejects(() => promptWithBackoff(rc, "s", {}, "hi", "c"), /Session not found/);
    assert.equal(calls, 1);
  });

  it("gives up after initial + 3 retries on persistent transport failure", async () => {
    let calls = 0;
    const rc = fake({ prompt: async () => { calls++; throw transportErr(); } });
    await assert.rejects(() => promptWithBackoff(rc, "s", {}, "hi", "c"), /Transport/);
    assert.equal(calls, 4);
  });

  it("skips the resend when the prompt already landed", async () => {
    let prompts = 0;
    let lists = 0;
    let listTypes;
    const rc = fake({
      prompt: async () => { prompts++; throw transportErr(); },
      list: async (sid, limit, types) => { lists++; listTypes = types; return [user(Date.now(), "hi there")]; },
    });
    const at = await promptWithBackoff(rc, "s", {}, "hi there", "c");
    assert.ok(at > 0);
    assert.equal(prompts, 1);
    assert.equal(lists, 1);
    assert.deepEqual(listTypes, "user");
  });
});

describe("sessionWaitWithRetry", () => {
  const transportErr = () => {
    const e = new Error("socket hang up");
    e.cause = { code: "ECONNRESET" };
    return e;
  };
  const fake = (wait) => ({ sessionWait: wait });

  it("resolves on first success without retry", async () => {
    const { sessionWaitWithRetry } = await import("../dist/opencode-proxy.js");
    let calls = 0;
    await sessionWaitWithRetry(fake(async () => { calls++; }), "s", "c", 1000);
    assert.equal(calls, 1);
  });

  it("retries transport drops, then resolves", async () => {
    const { sessionWaitWithRetry } = await import("../dist/opencode-proxy.js");
    let calls = 0;
    await sessionWaitWithRetry(
      fake(async () => { calls++; if (calls < 3) throw transportErr(); }),
      "s", "c", 30_000,
    );
    assert.equal(calls, 3);
  });

  it("throws dead sessions immediately without retry", async () => {
    const { sessionWaitWithRetry } = await import("../dist/opencode-proxy.js");
    let calls = 0;
    await assert.rejects(
      () => sessionWaitWithRetry(
        fake(async () => { calls++; throw new Error("Session not found: ses_x"); }),
        "s", "c", 30_000,
      ),
      /Session not found/,
    );
    assert.equal(calls, 1);
  });

  it("gives up at the deadline on persistent transport failure", async () => {
    const { sessionWaitWithRetry } = await import("../dist/opencode-proxy.js");
    let calls = 0;
    await assert.rejects(
      () => sessionWaitWithRetry(fake(async () => { calls++; throw transportErr(); }), "s", "c", 2500),
      /socket hang up/,
    );
    assert.ok(calls >= 2);
  });
});

describe("historyFromMessages", () => {
  it("returns user + assistant-text rows chronologically", () => {
    const data = [
      asst(300, "second answer"),
      user(100, "first question"),
      asst(200, "first answer"),
    ];
    assert.deepEqual(historyFromMessages(data), [
      { role: "user", text: "first question", ts: 100 },
      { role: "assistant", text: "first answer", ts: 200 },
      { role: "assistant", text: "second answer", ts: 300 },
    ]);
  });
  it("skips textless rows and non-chat types", () => {
    const data = [
      user(100, "   "),
      asst(200, "kept"),
      { type: "tool", time: { created: 300 }, content: [{ type: "text", text: "tool noise" }] },
      { type: "assistant", time: { created: 400 }, content: [{ type: "reasoning", text: "thinking" }] },
    ];
    assert.deepEqual(historyFromMessages(data), [{ role: "assistant", text: "kept", ts: 200 }]);
  });
  it("caps at 200, keeping the most recent", () => {
    const data = Array.from({ length: 600 }, (_, i) => user(i, `q${i}`));
    const out = historyFromMessages(data);
    assert.equal(out.length, 200);
    assert.equal(out[0].text, "q400");
    assert.equal(out[199].text, "q599");
  });
  it("keeps compaction markers as tool rows", () => {
    const data = [
      user(100, "q"),
      { type: "compaction", time: { created: 200 }, summary: "did things" },
    ];
    assert.deepEqual(historyFromMessages(data), [
      { role: "user", text: "q", ts: 100 },
      { role: "tool", text: "context compacted: did things", ts: 200 },
    ]);
  });
});

describe("providerRetryDelayMs", () => {
  it("null for non-provider errors", async () => {
    const { providerRetryDelayMs } = await import("../dist/opencode-proxy.js");
    assert.equal(providerRetryDelayMs(new Error("Session not found"), 0), null);
    assert.equal(providerRetryDelayMs(new Error("Transport error"), 0), null);
    assert.equal(providerRetryDelayMs({ status: 501 }, 0), null);
    assert.equal(providerRetryDelayMs({ status: 400 }, 0), null);
  });
  it("schedule delays for 429/5xx, honoring Retry-After", async () => {
    const { providerRetryDelayMs, PROMPT_RETRY_DELAYS_MS } = await import("../dist/opencode-proxy.js");
    assert.equal(providerRetryDelayMs({ status: 429 }, 0), PROMPT_RETRY_DELAYS_MS[0]);
    assert.equal(providerRetryDelayMs({ status: 503 }, 1), PROMPT_RETRY_DELAYS_MS[1]);
    assert.equal(providerRetryDelayMs({ code: 500 }, 2), PROMPT_RETRY_DELAYS_MS[2]);
    assert.equal(providerRetryDelayMs(new Error("rate limited, retry later"), 0), PROMPT_RETRY_DELAYS_MS[0]);
    assert.equal(providerRetryDelayMs({ status: 429, headers: { "retry-after": "2" } }, 0), 2000);
    assert.equal(providerRetryDelayMs({ status: 429 }, 3), null);
  });
  it("retries provider rejections only after the landed-check", async () => {
    const { promptWithBackoff } = await import("../dist/opencode-proxy.js");
    let prompts = 0;
    const listed = [];
    const rc = {
      sessionPrompt: async () => { prompts++; throw { status: 429 }; },
      sessionWait: async () => {},
      messageList: async () => listed,
    };
    await assert.rejects(() => promptWithBackoff(rc, "s", {}, "hi", "c"), (e) => e?.status === 429);
    assert.equal(prompts, 4);
  });
});

describe("pickReplyFromList overlap exclusion", () => {
  it("skips messages settled to another turn, keeping each reply own", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const data = [
      { type: "assistant", time: { created: 300 }, text: "B answer", content: [], id: "mB" },
      { type: "assistant", time: { created: 100 }, text: "A answer", content: [], id: "mA" },
    ];
    const settledToA = new Map([["mA", "tA"]]);
    const forB = pickReplyFromList(data, 250, settledToA, "tB");
    assert.equal(forB?.text, "B answer");
    // No exclusion info: each turn takes the reply nearest its own prompt.
    const forA = pickReplyFromList(data, 50, new Map(), "tA");
    assert.equal(forA?.text, "A answer");
  });
  it("merged prompts still share one message (fallback keeps merge rule)", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const data = [
      { type: "assistant", time: { created: 100 }, text: "shared", content: [], id: "mS" },
    ];
    const settledToA = new Map([["mS", "tA"]]);
    const forB = pickReplyFromList(data, 150, settledToA, "tB");
    assert.equal(forB?.text, "shared");
    assert.equal(forB?.messageID, "mS");
  });
  it("own turn id is never excluded", async () => {    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const data = [
      { type: "assistant", time: { created: 100 }, text: "mine", content: [], id: "mM" },
    ];
    const r = pickReplyFromList(data, 50, new Map([["mM", "tM"]]), "tM");
    assert.equal(r?.text, "mine");
  });
});

describe("pickReplyFromList fused agentic run", () => {
  const fin = (at, id, text, finish) => ({ ...asst(at, text), id, finish, time: { created: at, completed: at } });
  it("later turn takes the shared final over the unclaimed preamble", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    // T_a prompts at 1000, T_U at 5000; opencode emits interim A1 then final A4.
    // T_a picks first and claims A4; T_U must share A4 (pointer), not take A1.
    const desc = [
      fin(9000, "mA4", "direct fetch worked — full breakdown", "stop"),
      fin(2000, "mA1", "setting that up", "tool-calls"),
    ];
    const forA = pickReplyFromList(desc, 1000, new Map(), "tA");
    assert.equal(forA?.messageID, "mA4");
    const excl = new Map([["mA4", "tA"]]);
    const forU = pickReplyFromList(desc, 5000, excl, "tU");
    assert.equal(forU?.text, "direct fetch worked — full breakdown");
    assert.equal(forU?.messageID, "mA4");
  });
  it("later turn with only interims still gets its interim", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const desc = [fin(6000, "mI", "working on it", "tool-calls")];
    const r = pickReplyFromList(desc, 5000, new Map(), "tU");
    assert.equal(r?.text, "working on it");
  });
  it("three fused turns all end on the shared final", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const desc = [
      fin(9000, "mF", "shared final", "stop"),
      fin(2000, "mP", "preamble", "tool-calls"),
    ];
    const e1 = new Map();
    const r1 = pickReplyFromList(desc, 1000, e1, "t1");
    assert.equal(r1?.messageID, "mF");
    const e2 = new Map([["mF", "t1"]]);
    const r2 = pickReplyFromList(desc, 5000, e2, "t2");
    assert.equal(r2?.messageID, "mF");
    const e3 = new Map([["mF", "t1"]]);
    const r3 = pickReplyFromList(desc, 7000, e3, "t3");
    assert.equal(r3?.messageID, "mF");
  });
  it("pre-prompt message loses to post-prompt one", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const desc = [fin(9000, "mAfter", "after", "stop"), fin(500, "mBefore", "before", "stop")];
    const r = pickReplyFromList(desc, 1000, new Map(), "tU");
    assert.equal(r?.messageID, "mAfter");
  });
});

describe("pickReplyFromList long overlap", () => {
  it("A finishing after B keeps its own reply once B booked its own", async () => {
    const { pickReplyFromList } = await import("../dist/opencode-proxy.js");
    const data = [
      { type: "assistant", time: { created: 320 }, text: "B answer", content: [], id: "mB" },
      { type: "assistant", time: { created: 0 }, text: "A answer", content: [], id: "mA" },
    ];
    const settledToB = new Map([["mB", "tB"]]);
    const forA = pickReplyFromList(data, 0, settledToB, "tA");
    assert.equal(forA?.text, "A answer");
    assert.equal(forA?.messageID, "mA");
  });
});
