/** Tests for delivery plumbing (pure, no servers).
 *  Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { sanitizeJobName, parseSince, selectInbox, isChatId, scheduleChatOf, ALERTS_CHAT } from "../dist/inbox.js";
import { latestAssistantText } from "../dist/opencode-proxy.js";

describe("sanitizeJobName", () => {
  it("accepts plain job names", () => {
    assert.equal(sanitizeJobName("morning-brief"), "morning-brief");
    assert.equal(sanitizeJobName(" job_1 "), "job_1");
  });
  it("rejects traversal, slashes, blanks, non-strings", () => {
    for (const bad of ["../x", "a/b", "", "  ", "a:b", null, 42, "x".repeat(65)]) {
      assert.equal(sanitizeJobName(bad), null, `want reject for ${JSON.stringify(bad)}`);
    }
  });
});

describe("parseSince", () => {
  it("parses ms epochs, 0 on garbage", () => {
    assert.equal(parseSince("123"), 123);
    assert.equal(parseSince(null), 0);
    assert.equal(parseSince("abc"), 0);
    assert.equal(parseSince("-5"), 0);
  });
});

describe("selectInbox", () => {
  const e = (job, at) => ({ job, title: "t", body: "b", at });
  it("filters by job + since, oldest first, caps at 500", () => {
    const all = [e("a", 3), e("b", 2), e("a", 1)];
    assert.deepEqual(selectInbox(all, "a", 0).map((x) => x.at), [1, 3]);
    assert.deepEqual(selectInbox(all, null, 2).map((x) => x.at), [3]);
    const big = Array.from({ length: 600 }, (_, i) => e("a", i + 1));
    const sel = selectInbox(big, null, 0);
    assert.equal(sel.length, 500);
    assert.equal(sel[0].at, 101);
    assert.equal(sel[499].at, 600);
  });
});

describe("chat ids", () => {
  it("accepts legacy, schedule: and alerts ids", () => {
    assert.equal(isChatId("s-123"), true);
    assert.equal(isChatId("alerts"), true);
    assert.equal(isChatId("schedule:morning-brief"), true);
    assert.equal(isChatId(ALERTS_CHAT), true);
    assert.equal(scheduleChatOf("x"), "schedule:x");
  });
  it("rejects blanks, spaces, slashes", () => {
    for (const bad of ["", "a b", "a/b", "../x", "x".repeat(81)]) {
      assert.equal(isChatId(bad), false, `want reject for ${JSON.stringify(bad)}`);
    }
  });
});

describe("latestAssistantText", () => {
  it("returns the newest assistant text across messages", () => {
    const msgs = [
      { type: "user", time: { created: 1 } },
      { type: "assistant", time: { created: 2 }, content: [{ type: "text", text: "old" }] },
      { type: "assistant", time: { created: 3 }, content: [{ type: "tool", name: "read" }] },
      { type: "assistant", time: { created: 4 }, content: [{ type: "text", text: "new" }] },
    ];
    assert.equal(latestAssistantText(msgs), "new");
  });
  it("returns empty with no assistant text", () => {
    assert.equal(latestAssistantText([]), "");
    assert.equal(
      latestAssistantText([{ type: "assistant", time: { created: 1 }, content: [{ type: "tool", name: "x" }] }]),
      "",
    );
  });
});
