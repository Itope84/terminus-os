/** Registry + error-shape tests (multi-backend spec M2). Pure, no servers. */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  getDescriptor,
  listBackendIds,
  listBackends,
  parseBackendId,
} from "../dist/backends/registry.js";
import { isBackendId } from "../dist/backends/types.js";
import { BackendError, safeMessage, toPublicError } from "../dist/backends/errors.js";

describe("backend registry (M2)", () => {
  it("recognizes exactly opencode/codex/claude/agy, nothing else", () => {
    assert.equal(isBackendId("opencode"), true);
    assert.equal(isBackendId("codex"), true);
    assert.equal(isBackendId("claude"), true);
    assert.equal(isBackendId("agy"), true);
    for (const bad of ["", "Opencode", "OPENCODE", "codex exec", "../x", "opencode;rm", null, undefined, 3, {}]) {
      assert.equal(isBackendId(bad), false);
    }
    assert.deepEqual(listBackendIds(), ["opencode", "codex", "claude", "agy"]);
  });
  it("exposes all four backends once adapters land (M9)", () => {
    assert.deepEqual(listBackends().map((d) => d.id), ["opencode", "codex", "claude", "agy"]);
    assert.equal(getDescriptor("opencode").label, "OpenCode");
    assert.equal(getDescriptor("opencode").capabilities.overlappingTurns, true);
    assert.equal(getDescriptor("opencode").capabilities.approvals, false);
    assert.equal(getDescriptor("codex").label, "Codex");
    assert.equal(getDescriptor("claude").label, "Claude");
    assert.equal(getDescriptor("agy").label, "Agy");
  });
  it("parseBackendId accepts registered ids, rejects the rest", () => {
    assert.equal(parseBackendId("opencode"), "opencode");
    assert.equal(parseBackendId("codex"), "codex");
    assert.equal(parseBackendId("claude"), "claude");
    assert.equal(parseBackendId("agy"), "agy");
    assert.throws(() => parseBackendId("nope"), /unknown backend/);
  });
});

describe("backend errors (M2)", () => {
  it("toPublicError projects code/backend/operation, drops cause detail", () => {
    const err = new BackendError("usage_limit", "quota spent", false, "claude", "startTurn");
    err.cause = { token: "sk-ant-secret-123", env: { K: "v" } };
    assert.deepEqual(toPublicError(err), {
      error: "usage_limit",
      backend: "claude",
      operation: "startTurn",
      message: "quota spent",
    });
  });
  it("safeMessage redacts secret-shaped content", () => {
    const s = safeMessage("key sk-ant-abcdef123456 failed for Bearer abcdefgh1234 at /Users/bob/x (xoxb-1234567890)");
    assert.ok(!s.includes("sk-ant-abcdef123456"), s);
    assert.ok(!s.includes("abcdefgh1234"), s);
    assert.ok(!s.includes("/Users/bob"), s);
    assert.ok(!s.includes("xoxb-1234567890"), s);
    assert.ok(s.includes("REDACTED"), s);
  });
});
