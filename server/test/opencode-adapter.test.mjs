/** Opencode adapter tests (multi-backend spec M3). Pure + fixtures, no live. */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { OpencodeAdapter } from "../dist/backends/opencode.js";
import { mapOpencodeCommands, mapOpencodeModels } from "../dist/opencode-proxy.js";

describe("opencode adapter (M3)", () => {
  it("describes opencode with overlap + no approvals", () => {
    const a = new OpencodeAdapter();
    assert.equal(a.id, "opencode");
    const d = a.descriptor();
    assert.equal(d.label, "OpenCode");
    assert.equal(d.capabilities.overlappingTurns, true);
    assert.equal(d.capabilities.approvals, false);
  });
  it("lifecycle methods fail closed until M6 (never fake success)", async () => {
    const a = new OpencodeAdapter();
    await assert.rejects(() => a.startTurn({}), /routes in M6/);
    await assert.rejects(() => a.interruptTurn({}), /routes in M6/);
    await assert.rejects(() => a.getStatus({}), /routes in M6/);
  });
});

describe("opencode extraction mappers (M3)", () => {
  it("mapOpencodeModels dedupes, drops partials, sorts, falls back to modelID", () => {
    const out = mapOpencodeModels([
      { providerID: "openai", modelID: "gpt-5", name: "GPT-5" },
      { providerID: "openai", modelID: "gpt-5", name: "GPT-5 dup" },
      { providerID: "x" },
      {},
      { providerID: "anthropic", modelID: "claude" },
    ]);
    assert.deepEqual(out, [
      { id: "anthropic/claude", provider: "anthropic", label: "claude" },
      { id: "openai/gpt-5", provider: "openai", label: "GPT-5" },
    ]);
  });
  it("mapOpencodeCommands keeps slash commands with descriptions, sorts", () => {
    const out = mapOpencodeCommands([
      { name: "/wait", description: "wait" },
      { name: "noslash" },
      {},
      { name: "/compact" },
    ]);
    assert.deepEqual(out, [
      { name: "/compact", description: "" },
      { name: "/wait", description: "wait" },
    ]);
  });
});
