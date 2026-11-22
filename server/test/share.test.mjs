/** Tests for partner shared folders (pure, no servers).
 *  Run: npm test (builds first, then node --test)
 *
 *  Contract: password-scoped, read-only, single-folder. Scrypt hashes only
 *  (0600 file); nothing secret is logged or returned. Uses an isolated
 *  SHARE_FILE + cleans its folder; never touches real shares.
 */
import { describe, it, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const scratch = mkdtempSync(join(tmpdir(), "share-test-"));
process.env.SHARE_FILE = join(scratch, "shares.json");
const { setSharePassword, openShareSession, shareSessionFolder, sharePathAllowed, validFolderName } =
  await import("../dist/share.js");
import { repoRoot } from "../dist/paths.js";

after(() => {
  rmSync(scratch, { recursive: true, force: true });
  rmSync(join(repoRoot(), "artifacts", "test-tmp-a"), { recursive: true, force: true });
});

const pw = `pw-test-${Math.random().toString(36).slice(2)}`;

describe("share folders", () => {
  it("rejects bad folder names and short passwords", () => {
    assert.equal(setSharePassword("../x", pw).ok, false);
    assert.equal(setSharePassword("OK", pw).ok, false);
    assert.equal(setSharePassword("test-tmp-a", "abc").ok, false);
  });

  it("roundtrips password to session, rejects wrong", () => {
    assert.equal(setSharePassword("test-tmp-a", pw).ok, true);
    const sid = openShareSession("test-tmp-a", pw);
    assert.ok(sid && /^[0-9a-f]{64}$/.test(sid));
    assert.equal(openShareSession("test-tmp-a", `${pw}-wrong`), null);
    assert.equal(openShareSession("nope", pw), null);
  });

  it("cookie maps to its folder only", () => {
    const sid = openShareSession("test-tmp-a", pw);
    assert.ok(sid);
    const req = { headers: { cookie: `share_test-tmp-a=${sid}` } };
    assert.equal(shareSessionFolder(req), "test-tmp-a");
    assert.equal(shareSessionFolder({ headers: { cookie: "share_test-tmp-a=00" } }), null);
    assert.equal(shareSessionFolder({ headers: {} }), null);
  });

  it("path scoping is prefix-tight", () => {
    assert.equal(sharePathAllowed("/terminus/test-tmp-a/f.pdf", "test-tmp-a"), true);
    assert.equal(sharePathAllowed("/terminus/test-tmp-a", "test-tmp-a"), false);
    assert.equal(sharePathAllowed("/terminus/other/f.pdf", "test-tmp-a"), false);
    assert.equal(sharePathAllowed("/terminus/test-tmp-a../f.pdf", "test-tmp-a"), false);
    assert.equal(sharePathAllowed("/api/turn?x=1", "test-tmp-a"), false);
  });

  it("validFolderName matrix", () => {
    assert.equal(validFolderName("shared"), true);
    assert.equal(validFolderName("a"), true);
    assert.equal(validFolderName("A"), false);
    assert.equal(validFolderName("../x"), false);
    assert.equal(validFolderName(""), false);
  });
});
