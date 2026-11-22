/** Tests for backend attachment helpers (pure pieces, no I/O).
 *  Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { safeAttachName, attachmentNote } from "../dist/backends/attachments.js";

describe("safeAttachName", () => {
  it("strips directories and keeps the extension", () => {
    assert.equal(safeAttachName("/tmp/a/photo.png", 0), "photo.png");
    assert.equal(safeAttachName("..\\..\\secret.txt", 1), "secret.txt");
  });
  it("cleans hostile characters and never returns empty", () => {
    assert.equal(safeAttachName("a b:c?.jpg", 0), "a_b_c_.jpg");
    assert.equal(safeAttachName("...", 2), "file-3");
    assert.equal(safeAttachName("", 0), "file-1");
  });
});

describe("attachmentNote", () => {
  it("is empty when nothing was saved", () => {
    assert.equal(attachmentNote([]), "");
  });
  it("names each saved path with mime", () => {
    const s = attachmentNote([{ path: "/m/chat-attach/t/1-a.png", mime: "image/png", name: "a.png" }]);
    assert.match(s, /Attached files/);
    assert.match(s, /\/m\/chat-attach\/t\/1-a\.png \(image\/png\)/);
  });
});
