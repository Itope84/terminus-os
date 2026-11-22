import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  assistantListSig,
  backgroundLandingOf,
  backgroundSpawnOf,
  backgroundSpawnShape,
  compactionEventOf,
  compactionText,
  errorStepOf,
  pickErrorFromList,
  providerErrorText,
  toolStepOf,
  unmatchedSyntheticShape,
} from "../dist/opencode-proxy.js";

describe("providerErrorText", () => {
  it("reads a string error payload", () => {
    assert.equal(
      providerErrorText({ type: "assistant", time: { created: 1 }, error: "The request contains invalid parameters" }),
      "The request contains invalid parameters",
    );
  });
  it("reads an object error payload", () => {
    assert.equal(
      providerErrorText({ type: "assistant", time: { created: 1 }, error: { message: "boom x" } }),
      "boom x",
    );
  });
  it("reads error-typed content parts", () => {
    assert.equal(
      providerErrorText({
        type: "assistant",
        time: { created: 1 },
        content: [{ type: "error", text: "provider rejected the call" }],
      }),
      "provider rejected the call",
    );
  });
  it("ignores text/tool-only messages", () => {
    assert.equal(
      providerErrorText({ type: "assistant", time: { created: 1 }, content: [{ type: "text", text: "hi" }] }),
      "",
    );
    assert.equal(providerErrorText({ type: "assistant", time: { created: 1 } }), "");
  });
});

describe("pickErrorFromList", () => {
  it("picks the in-window provider error", () => {
    const data = [
      { type: "assistant", time: { created: 1000 }, content: [{ type: "text", text: "old" }] },
      { type: "assistant", time: { created: 2000 }, error: { message: "invalid parameters" } },
    ];
    assert.equal(pickErrorFromList(data, 1500)?.text, "invalid parameters");
  });
  it("ignores out-of-window errors", () => {
    const data = [{ type: "assistant", time: { created: 1000 }, error: { message: "stale" } }];
    assert.equal(pickErrorFromList(data, 70_000), null);
  });
  it("returns null when nothing errored", () => {
    assert.equal(pickErrorFromList([], 1000), null);
  });
  it("skips errors settled to another turn", () => {
    const data = [
      { id: "m1", type: "assistant", time: { created: 2000 }, error: { message: "taken" } },
      { id: "m2", type: "assistant", time: { created: 2100 }, error: { message: "mine" } },
    ];
    const excl = new Map([["m1", "tA"]]);
    assert.equal(pickErrorFromList(data, 1500, excl, "tB")?.text, "mine");
    assert.equal(pickErrorFromList(data, 1500, excl, "tA")?.text, "taken");
  });
});

describe("assistantListSig", () => {
  it("is stable for identical lists", () => {
    const m = () => ([
      { id: "a", type: "assistant", time: { created: 1, completed: 2 }, content: [{ type: "text", text: "hi" }] },
    ]);
    assert.equal(assistantListSig(m()), assistantListSig(m()));
  });
  it("moves on text growth, reasoning, tools, and errors", () => {
    const base = [{ id: "a", type: "assistant", time: { created: 1 }, content: [{ type: "text", text: "hi" }] }];
    const grown = [{ id: "a", type: "assistant", time: { created: 1 }, content: [{ type: "text", text: "hi there" }] }];
    const thinking = [{ id: "a", type: "assistant", time: { created: 1 }, content: [{ type: "reasoning", text: "hmm" }] }];
    const tooled = [{ id: "a", type: "assistant", time: { created: 1 }, content: [{ type: "tool", name: "bash" }] }];
    const failed = [{ id: "a", type: "assistant", time: { created: 1 }, error: { message: "x" } }];
    const s0 = assistantListSig(base);
    for (const v of [grown, thinking, tooled, failed]) assert.notEqual(assistantListSig(v), s0);
  });
});

describe("errorStepOf", () => {
  it("carries the failure message", () => {
    const s = errorStepOf("session.error", { error: { message: "invalid parameters" } });
    assert.equal(s.label, "assistant error");
    assert.equal(s.detail, "invalid parameters");
  });
  it("falls back to top-level message fields", () => {
    const s = errorStepOf("message.error", { message: "provider overloaded" });
    assert.equal(s.detail, "provider overloaded");
  });
});

describe("toolStepOf step error detail", () => {
  it("ended carries finish state without file names", () => {
    const s = toolStepOf("session.step.ended", { finish: "stop", files: ["/repo/src/a.ts", "/repo/src/b.ts"] });
    assert.match(s.detail, /stop/);
    assert.doesNotMatch(s.detail, /a\.ts/);
  });
  it("ended carries a failure message when present", () => {
    const s = toolStepOf("session.step.ended", { finish: "error", error: { message: "build failed" } });
    assert.match(s.detail, /build failed/);
  });
});

describe("compactionText", () => {
  it("reads the summary excerpt", () => {
    assert.equal(
      compactionText({ type: "compaction", time: { created: 1 }, summary: "  work so far  " }),
      "work so far",
    );
  });
  it("falls back to recent", () => {
    assert.equal(
      compactionText({ type: "compaction", time: { created: 1 }, recent: "tail text" }),
      "tail text",
    );
  });
  it("ignores non-compaction messages", () => {
    assert.equal(compactionText({ type: "assistant", time: { created: 1 }, summary: "x" }), "");
  });
});

describe("compactionSummary", () => {
  it("keeps line breaks so markdown survives", async () => {
    const { compactionSummary } = await import("../dist/opencode-proxy.js");
    assert.equal(
      compactionSummary({ type: "compaction", time: { created: 1 }, summary: "## Objective\r\n\r\n\r- fix it\n" }),
      "## Objective\n\n- fix it",
    );
  });
  it("falls back to recent and ignores the rest", async () => {
    const { compactionSummary } = await import("../dist/opencode-proxy.js");
    assert.equal(
      compactionSummary({ type: "compaction", time: { created: 1 }, recent: "tail text" }),
      "tail text",
    );
    assert.equal(compactionSummary({ type: "assistant", time: { created: 1 }, summary: "x" }), "");
  });
});

describe("compactionEventOf", () => {
  it("maps ended with reason", () => {
    assert.deepEqual(compactionEventOf("session.compaction.ended", { reason: "auto" }), {
      status: "ended",
      reason: "auto",
      detail: "",
    });
  });
  it("maps failed with the error message", () => {
    const s = compactionEventOf("session.compaction.failed", { reason: "manual", error: { message: "nope" } });
    assert.equal(s.status, "failed");
    assert.equal(s.detail, "nope");
  });
});

describe("backgroundSpawnOf", () => {
  it("reads a detached tool call as a background spawn", () => {
    const s = backgroundSpawnOf("session.tool.called", {
      id: "call_00_x",
      input: { background: true, command: "sleep 6; echo BG-MARKER" },
    });
    assert.deepEqual(s, { status: "started", id: "call_00_x", label: "sleep 6; echo BG-MARKER" });
  });
  it("ignores an ordinary (foreground) tool call", () => {
    assert.equal(
      backgroundSpawnOf("session.tool.called", { id: "call_00_y", input: { command: "ls -la" } }),
      null,
    );
  });
  it("only fires on called, not the other tool phases", () => {
    const d = { id: "c", input: { background: true, command: "x" } };
    assert.equal(backgroundSpawnOf("session.tool.success", d), null);
    assert.equal(backgroundSpawnOf("session.tool.input.started", d), null);
  });
});

describe("backgroundLandingOf", () => {
  const item = {
    item: {
      type: "synthetic",
      payload: {
        text: '<shell id="sh_14f0abdd6001q4yhsK9RAtvwiQ" state="completed" command="sleep 6; echo BG-MARKER">\nBG-MARKER\n\n</shell>',
        description: "sleep 6; echo BG-MARKER",
        metadata: { source: "shell", shellID: "sh_14f0abdd6001q4yhsK9RAtvwiQ", jobID: "sh_14f0abdd6001q4yhsK9RAtvwiQ" },
      },
    },
  };
  it("reads the completion, command and output", () => {
    assert.deepEqual(backgroundLandingOf("session.inbox.enqueued", item), {
      status: "done",
      id: "sh_14f0abdd6001q4yhsK9RAtvwiQ",
      label: "sleep 6; echo BG-MARKER",
      detail: "BG-MARKER",
    });
  });
  it("a non-completed state reads as a failure", () => {
    const bad = structuredClone(item);
    bad.item.payload.text = '<shell id="sh1" state="failed" command="boom">\nno\n</shell>';
    assert.equal(backgroundLandingOf("session.inbox.enqueued", bad).status, "failed");
  });
  it("long output rides a capped full field; short output does not", () => {
    const big = structuredClone(item);
    big.item.payload.text = '<shell id="sh1" state="completed" command="make">\n' + "x\n".repeat(3000) + "</shell>";
    const got = backgroundLandingOf("session.inbox.enqueued", big);
    assert.ok(got.full && got.full.length <= 4000);
    assert.ok(got.full.includes("\n"));
    assert.equal(backgroundLandingOf("session.inbox.enqueued", item).full, undefined);
  });
  it("ignores non-shell inbox items and other event types", () => {
    const other = structuredClone(item);
    other.item.payload.metadata.source = "goal";
    assert.equal(backgroundLandingOf("session.inbox.enqueued", other), null);
    assert.equal(backgroundLandingOf("session.inbox.delivered", item), null);
    assert.equal(backgroundLandingOf("session.tool.called", item), null);
  });
});

describe("backgroundSpawnShape", () => {
  it("reports id + input keys for a background spawn, never values", () => {
    const s = backgroundSpawnShape("session.tool.called", {
      id: "call_00_x",
      input: { background: true, command: "sleep 6; echo secret" },
    });
    assert.deepEqual(s, { id: "call_00_x", inputKeys: ["background", "command"] });
  });
  it("null for foreground calls and other tool phases", () => {
    assert.equal(
      backgroundSpawnShape("session.tool.called", { id: "c", input: { command: "x" } }),
      null,
    );
    assert.equal(
      backgroundSpawnShape("session.tool.success", { id: "c", input: { background: true } }),
      null,
    );
  });
});

describe("unmatchedSyntheticShape", () => {
  it("reports source + key names for synthetic-but-not-shell items", () => {
    const s = unmatchedSyntheticShape("session.inbox.enqueued", {
      item: {
        type: "synthetic",
        payload: { text: "x", description: "y", metadata: { source: "task", foo: 1 } },
      },
    });
    assert.deepEqual(s, {
      source: "task",
      itemKeys: ["payload", "type"],
      payloadKeys: ["description", "metadata", "text"],
      metadataKeys: ["foo", "source"],
    });
  });
  it("null for shell landings, non-synthetic items and other events", () => {
    const shell = {
      item: {
        type: "synthetic",
        payload: {
          text: '<shell id="sh1" state="completed" command="ls">\nout\n</shell>',
          metadata: { source: "shell", shellID: "sh1" },
        },
      },
    };
    assert.equal(unmatchedSyntheticShape("session.inbox.enqueued", shell), null);
    assert.equal(
      unmatchedSyntheticShape("session.inbox.enqueued", { item: { type: "plain" } }),
      null,
    );
    assert.equal(unmatchedSyntheticShape("session.tool.called", shell), null);
  });
});

