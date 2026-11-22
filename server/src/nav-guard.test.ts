import test from "node:test";
import assert from "node:assert/strict";
import { linkTokenPathOk, safeNavUrl } from "./nav-guard.js";

test("only http(s) URLs may be navigated to", () => {
  assert.equal(safeNavUrl("https://example.com/a?b=1"), "https://example.com/a?b=1");
  assert.equal(safeNavUrl("http://localhost:3000"), "http://localhost:3000/");
  for (const bad of ["file:///root/.config/terminus/browser/token", "chrome://settings", "view-source:https://a.b", "data:text/html,x", "javascript:alert(1)", "", "  ", "not a url", 5, null, undefined])
    assert.equal(safeNavUrl(bad), null, String(bad));
});

test("link tokens reach gallery and repo files only", () => {
  assert.equal(linkTokenPathOk("/terminus/pic.png?token=l1.x.y"), true);
  assert.equal(linkTokenPathOk("/repo/server/src/a.ts?token=l1.x.y"), true);
  assert.equal(linkTokenPathOk("/repo/agentbook/claude/s-1.md?token=l1.x.y"), false);
  for (const bad of ["/screenshot?url=file:///x&token=l1.x.y", "/scrape?url=https://a.b", "/profiles", "/ops", "/notes", "/notes/", "/notes-profile", "/notes-version", "/terminus", "/api/chat"])
    assert.equal(linkTokenPathOk(bad), false, bad);
});
