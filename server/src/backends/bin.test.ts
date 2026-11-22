import test from "node:test";
import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findBin } from "./bin.js";

test("findBin finds a binary on PATH, then in extraDirs, else null", () => {
  const a = mkdtempSync(join(tmpdir(), "bin-a-"));
  const b = mkdtempSync(join(tmpdir(), "bin-b-"));
  writeFileSync(join(a, "tc-demo-a"), "#!/bin/sh\n"); chmodSync(join(a, "tc-demo-a"), 0o755);
  writeFileSync(join(b, "tc-demo-b"), "#!/bin/sh\n"); chmodSync(join(b, "tc-demo-b"), 0o755);
  const old = process.env.PATH;
  process.env.PATH = `${a}:${a}`;
  try {
    assert.equal(findBin("tc-demo-a"), join(a, "tc-demo-a"));
    assert.equal(findBin("tc-demo-b"), null);
    assert.equal(findBin("tc-demo-b", [b]), join(b, "tc-demo-b"));
  } finally { process.env.PATH = old; }
});
