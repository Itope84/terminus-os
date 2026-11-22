import test from "node:test";
import assert from "node:assert/strict";
import { markdownHtml, plainMd } from "./md-lite.js";

test("lists, bold, code and headings render", () => {
  const h = markdownHtml("### Plan\n- one **bold**\n- two `x`\n\n1. first\n2. second\ntail");
  assert.equal(h, "<h3>Plan</h3><ul><li>one <strong>bold</strong></li><li>two <code>x</code></li></ul><ol><li>first</li><li>second</li></ol><p>tail</p>");
});

test("markup in the text can never become markup", () => {
  const h = markdownHtml('<script>alert(1)</script> [x](javascript:alert(1)) [ok](https://a.b/c?d=1&e=2)');
  assert.ok(!h.includes("<script"));
  assert.ok(!h.includes('href="javascript'));
  assert.ok(h.includes('<a href="https://a.b/c?d=1&amp;e=2" rel="noopener">ok</a>'));
});

test("plain preview drops the markup", () => {
  assert.equal(plainMd("- a **b**\n1. c `d`\n[l](https://x.y)"), "a b\nc d\nl");
});
