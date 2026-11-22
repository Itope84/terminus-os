import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import type { IncomingMessage } from "node:http";
import { atomicWriteFile, readBodyText } from "./util.js";

const reqOf = (chunks: string[]) => Readable.from(chunks.map((c) => Buffer.from(c))) as unknown as IncomingMessage;

test("readBodyText returns the body under the cap", async () => {
  assert.equal(await readBodyText(reqOf(['{"a":', "1}"]), 100), '{"a":1}');
});
test("readBodyText rejects once the cap is crossed", async () => {
  await assert.rejects(readBodyText(reqOf(["x".repeat(60), "y".repeat(60)]), 100), /body too large/);
});
test("atomicWriteFile replaces the target and leaves no temp file", async () => {
  const dir = mkdtempSync(join(tmpdir(), "util-"));
  const p = join(dir, "f.json");
  writeFileSync(p, "old");
  await atomicWriteFile(p, "new");
  assert.equal(readFileSync(p, "utf8"), "new");
  assert.deepEqual(readdirSync(dir), ["f.json"]);
});
test("concurrent atomicWriteFile calls never share a temp file", async () => {
  const dir = mkdtempSync(join(tmpdir(), "util-"));
  const p = join(dir, "f.json");
  await Promise.all(Array.from({ length: 20 }, (_, i) => atomicWriteFile(p, `v${i}`.padEnd(5000, "-"))));
  assert.match(readFileSync(p, "utf8"), /^v\d+-+$/);
  assert.deepEqual(readdirSync(dir), ["f.json"]);
});
