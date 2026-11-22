import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { handleWake, wakeDone, wakeInbox, wakePreview, wakeClientCount } from "./wake.js";

function fakeClient() {
  const req = new EventEmitter();
  const out: string[] = [];
  const res = Object.assign(new EventEmitter(), {
    writeHead() { return res; },
    write(s: string) { out.push(s); return true; },
  });
  handleWake(req as never, res as never);
  return { req, out };
}

test("wake stream sends done and inbox events to connected phones, nothing after close", () => {
  const c = fakeClient();
  assert.equal(wakeClientCount(), 1);
  wakeDone("chat-1", "t1", "Here is   the\nanswer");
  wakeInbox({ job: "morning-brief", at: 5, chat: "schedule:morning-brief" });
  const body = c.out.join("");
  assert.match(body, /event: done\ndata: \{"chatId":"chat-1","turnId":"t1","preview":"Here is the answer","failed":false\}/);
  assert.match(body, /event: inbox\ndata: \{"job":"morning-brief","chat":"schedule:morning-brief","at":5\}/);
  c.req.emit("close");
  assert.equal(wakeClientCount(), 0);
  const before = c.out.length;
  wakeDone("chat-1", "t2", "late");
  assert.equal(c.out.length, before);
});

test("a failed reply carries no text", () => {
  const c = fakeClient();
  wakeDone("chat-1", "t3", "partial secret text", "provider_error");
  assert.match(c.out.join(""), /"preview":"","failed":true/);
  c.req.emit("close");
});

test("wakePreview folds whitespace and caps length", () => {
  assert.equal(wakePreview("a\n\n b"), "a b");
  assert.equal(wakePreview("x".repeat(500)).length, 160);
});
