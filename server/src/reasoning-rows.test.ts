import test from "node:test";
import assert from "node:assert/strict";
import { splitReasoning } from "./reasoning-rows.js";

test("chunks that split words are joined, not shown as separate rows", () => {
  let buf = "";
  const rows: string[] = [];
  for (const c of ["same works in messages you", " send,", " which is simpler than the card I'", "d planned. I'm mo", "ving on to the app side next."]) {
    buf += c;
    const r = splitReasoning(buf, false);
    rows.push(...r.rows);
    buf = r.rest;
  }
  rows.push(...splitReasoning(buf, true).rows);
  assert.deepEqual(rows, ["same works in messages you send, which is simpler than the card I'd planned. I'm moving on to the app side next."]);
});

test("paragraph breaks start new rows; the open paragraph waits", () => {
  const r = splitReasoning("First thought.\n\nSecond thought.\n\nThird, still typ", false);
  assert.deepEqual(r.rows, ["First thought.", "Second thought."]);
  assert.equal(r.rest, "Third, still typ");
});

test("a long paragraph is cut at a space under the cap and the tail is kept", () => {
  const words = Array.from({ length: 200 }, (_, i) => `word${i}`).join(" ");
  const r = splitReasoning(words, false, 100);
  assert.ok(r.rows.length >= 2);
  for (const row of r.rows) { assert.ok(row.length <= 100, row); assert.ok(!row.endsWith("wor"), row); }
  assert.equal([...r.rows, r.rest].join(" ").replace(/\s+/g, " ").trim(), words);
});

test("final flushes the remainder; blanks never become rows", () => {
  assert.deepEqual(splitReasoning("  \n\n  tail", true).rows, ["tail"]);
  assert.deepEqual(splitReasoning("   ", true), { rows: [], rest: "" });
});
