import { test } from "node:test";
import assert from "node:assert/strict";
import { approvalPromptText } from "./approval-text.js";

test("command: shows the command and why it was held", () => {
  const r = approvalPromptText("item/commandExecution/requestApproval", { command: "cat ~/.config/terminus/secrets/x" }, "deny marker /secrets/");
  assert.equal(r.title, "Run a command");
  assert.match(r.detail, /^cat ~\/\.config\/terminus\/secrets\/x\n\nProtected: \/secrets\/$/);
});

test("file change: lists paths, caps at 6", () => {
  const fcs = Array.from({ length: 9 }, (_, i) => ({ path: `/a/${i}` }));
  const r = approvalPromptText("item/fileChange/requestApproval", { fileChanges: fcs }, "deny marker .env");
  assert.equal(r.title, "Change files");
  assert.equal(r.detail.split("\n\n")[0].split("\n").length, 6);
});

test("unknown shape still yields a safe prompt; long commands are clipped", () => {
  assert.equal(approvalPromptText("x/approval", null, "unknown kind").title, "Action needs approval");
  assert.ok(approvalPromptText("c/commandApproval", { command: "x".repeat(5000) }, "r").detail.length < 450);
});
