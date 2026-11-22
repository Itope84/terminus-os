/** Smoke tests for the media gallery (tonight's 404 + streaming bugs).
 *  Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mimeFor, safeParts, parseRange, debugChatHtml, notesHtml, profileMarkdownHtml, isRepoPath } from "../dist/media.js";

describe("mimeFor", () => {
  it("serves video/audio with streamable types", () => {
    assert.equal(mimeFor("clip.mp4"), "video/mp4");
    assert.equal(mimeFor("clip.WEBM"), "video/webm");
    assert.equal(mimeFor("song.mp3"), "audio/mpeg");
    assert.equal(mimeFor("shot.png"), "image/png");
    assert.equal(mimeFor("weird.bin"), "application/octet-stream");
  });
});

describe("safeParts", () => {
  it("accepts day/file pairs", () => {
    assert.deepEqual(safeParts("2026-09-20/shot-1.png"), ["2026-09-20", "shot-1.png"]);
  });
  it("rejects traversal and paths deeper than folder/sub/file", () => {
    assert.equal(safeParts("../token"), null);
    assert.equal(safeParts("a/b/c/d"), null);
    assert.equal(safeParts(""), null);
  });
  it("accepts one nested level (e.g. shared/data/file)", () => {
    assert.deepEqual(safeParts("shared/data/holiday-report.html"), ["shared", "data", "holiday-report.html"]);
  });
});

describe("tonight's filename bug", () => {
  it("percent-encoded gallery names decode back to the real file", () => {
    const real = "2026-09-20/Sample Show ｜ Part One + More.mp4";
    const listed = real.split("/").map(encodeURIComponent).join("/");
    assert.equal(decodeURIComponent(listed), real);
    assert.deepEqual(safeParts(decodeURIComponent(listed)), ["2026-09-20", "Sample Show ｜ Part One + More.mp4"]);
  });
});

describe("parseRange", () => {
  it("parses prefix ranges with clamp", () => {
    assert.deepEqual(parseRange("bytes=0-99", 1000), { start: 0, end: 99 });
    assert.deepEqual(parseRange("bytes=900-", 1000), { start: 900, end: 999 });
  });
  it("rejects junk", () => {
    assert.equal(parseRange(undefined, 1000), null);
    assert.equal(parseRange("bytes=9999-", 1000), null);
    assert.equal(parseRange("items=0-1", 1000), null);
  });
});

describe("debug chat mirror", () => {
  it("observes the app stream contract without persisting the token", () => {
    const html = debugChatHtml();
    assert.match(html, /\/api\/stream\?chatId=/);
    assert.match(html, /Authorization/);
    assert.match(html, /drop next done/);
    assert.match(html, /rendered reply/);
    assert.match(html, /resend same turnId/);
    assert.match(debugChatHtml("abc1234"), /Mirror build: abc1234/);
    assert.doesNotMatch(html, /localStorage|sessionStorage/);
  });
});

describe("notebook user details", () => {
  it("loads behind an explicit user-icon action", () => {
    const html = notesHtml();
    assert.match(html, /id="profile-button"/);
    assert.match(html, /id="profile-dialog"/);
    assert.match(html, /fetch\("\/notes-profile"/);
    assert.doesNotMatch(html, /notebook\/user\.md/);
  });

  it("renders profile markdown while escaping embedded HTML", () => {
    const html = profileMarkdownHtml("# Profile\n## Group\n- Member **one**\n- <script>alert(1)</script>");
    assert.match(html, /<h3>Group<\/h3>/);
    assert.match(html, /<strong>one<\/strong>/);
    assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.doesNotMatch(html, /<script>/);
  });
});

describe("isRepoPath (read-only repo browser)", () => {
  const tracked = new Set(["AGENTS.md", "android/app/build.gradle.kts", "server/src/media.ts"]);
  it("accepts exact tracked paths, nested included", () => {
    assert.equal(isRepoPath("AGENTS.md", tracked), true);
    assert.equal(isRepoPath("android/app/build.gradle.kts", tracked), true);
  });
  it("rejects traversal, absolutes, untracked, and blanks", () => {
    assert.equal(isRepoPath("../secrets/x", tracked), false);
    assert.equal(isRepoPath("/etc/passwd", tracked), false);
    assert.equal(isRepoPath("a//b", tracked), false);
    assert.equal(isRepoPath("WIP.md", tracked), false);
    assert.equal(isRepoPath("", tracked), false);
    assert.equal(isRepoPath("server/src/auth.ts", tracked), false);
  });
});
