import test from "node:test";
import assert from "node:assert/strict";
import { takeLines } from "./listen.js";

test("takeLines keeps the unfinished tail and skips foreign lines", () => {
  const r = takeLines('{"type":"partial","t":1,"text":"hel"}\nnoise\n{"type":"final","t":1,"dur":2,"text":"hello"}\n{"type":"par');
  assert.deepEqual(r.events.map((e) => [e.type, e.text]), [["partial", "hel"], ["final", "hello"]]);
  assert.equal(r.rest, '{"type":"par');
});
