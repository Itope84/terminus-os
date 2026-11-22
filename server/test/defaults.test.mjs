/** Tests for POST /api/defaults input validation (pure, no servers).
 *  Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { sanitizeDefaultsInput, normCompactAt, shouldCompactBefore, newestCompactionSummary, applyDefaultsPatch } from "../dist/opencode-proxy.js";

describe("sanitizeDefaultsInput", () => {
  it("accepts a full valid patch", () => {
    const r = sanitizeDefaultsInput({ defaultModel: "openai/gpt-5", systemPrompt: "Be terse." });
    assert.deepEqual(r, { ok: true, value: { defaultModel: "openai/gpt-5", systemPrompt: "Be terse." } });
  });

  it("treats blank as clear-to-server-default", () => {
    const r = sanitizeDefaultsInput({ defaultModel: "  ", systemPrompt: "" });
    assert.deepEqual(r, { ok: true, value: { defaultModel: "", systemPrompt: "" } });
  });

  it("missing keys mean no change", () => {
    assert.deepEqual(sanitizeDefaultsInput({}), { ok: true, value: {} });
  });

  it("rejects malformed model references", () => {
    for (const bad of ["gpt-5", "/gpt-5", "openai/", "default", 42, null]) {
      const r = sanitizeDefaultsInput({ defaultModel: bad });
      assert.equal(r.ok, false, `want reject for ${JSON.stringify(bad)}`);
    }
  });

  it("rejects non-string prompts and overlong prompts", () => {
    assert.equal(sanitizeDefaultsInput({ systemPrompt: 7 }).ok, false);
    assert.equal(sanitizeDefaultsInput({ systemPrompt: "x".repeat(8001) }).ok, false);
    assert.equal(sanitizeDefaultsInput({ systemPrompt: "x".repeat(8000) }).ok, true);
  });

  it("rejects non-object bodies", () => {
    for (const bad of [null, 42, "x", [], [1]]) {
      assert.equal(sanitizeDefaultsInput(bad).ok, false);
    }
  });

  it("validates compactAt: 0 or an integer inside range", () => {
    assert.deepEqual(sanitizeDefaultsInput({ compactAt: 0 }), { ok: true, value: { compactAt: 0 } });
    assert.deepEqual(sanitizeDefaultsInput({ compactAt: 50000 }), { ok: true, value: { compactAt: 50000 } });
    for (const bad of ["50000", 19_999, 1_000_001, 1.5, NaN, null]) {
      assert.equal(sanitizeDefaultsInput({ compactAt: bad }).ok, false, `want reject for ${String(bad)}`);
    }
  });

  it("carries compactAt through applyDefaultsPatch", () => {
    const stored = { defaultBackend: "opencode", defaultModels: {}, defaultModel: "", systemPrompt: "", seedVersion: 14, previousSystemPrompt: "", compactAt: 0 };
    assert.equal(applyDefaultsPatch(stored, { compactAt: 60000 }).compactAt, 60000);
    assert.equal(applyDefaultsPatch({ ...stored, compactAt: 60000 }, {}).compactAt, 60000);
  });
});

describe("normCompactAt", () => {
  it("keeps 0 and in-range integers, drops everything else", () => {
    assert.equal(normCompactAt(0), 0);
    assert.equal(normCompactAt(20_000), 20_000);
    assert.equal(normCompactAt(1_000_000), 1_000_000);
    for (const bad of [undefined, null, "x", NaN, -1, 19_999, 1_000_001, 1.5]) {
      assert.equal(normCompactAt(bad), 0, `want 0 for ${String(bad)}`);
    }
    assert.equal(normCompactAt(60000.9), 60000);
  });
});

describe("shouldCompactBefore", () => {
  it("compacts only when a ceiling is set and reached", () => {
    assert.equal(shouldCompactBefore(0, 999_999), false);
    assert.equal(shouldCompactBefore(50000, undefined), false);
    assert.equal(shouldCompactBefore(50000, 49_999), false);
    assert.equal(shouldCompactBefore(50000, 50_000), true);
    assert.equal(shouldCompactBefore(50000, 80_000), true);
  });
});

describe("newestCompactionSummary", () => {
  const msg = (text, created) => ({ type: "compaction", time: { created }, summary: text });
  it("takes the newest summary at or after since, never an older one", () => {
    const listed = [msg("new", 2000), msg("old", 1000)];
    assert.equal(newestCompactionSummary(listed, 1500), "new");
    assert.equal(newestCompactionSummary(listed, 500), "new");
    assert.equal(newestCompactionSummary(listed, 2500), "");
    assert.equal(newestCompactionSummary([], 0), "");
  });
});
