import test from "node:test";
import assert from "node:assert/strict";
import { apksToPrune } from "./media.js";

test("apksToPrune keeps the newest Terminus builds and ignores other files", () => {
  const files = [
    { path: "/a/2026-11-18/terminus-all-in-20261118-v1267.apk", mtimeMs: 1 },
    { path: "/a/2026-11-18/terminus-all-in-20261118-v1268.apk", mtimeMs: 2 },
    { path: "/a/2026-11-19/terminus-all-in-20261119-v1269.apk", mtimeMs: 3 },
    { path: "/a/2026-11-20/terminus-chat-1270.apk", mtimeMs: 4 },
    { path: "/a/2026-11-20/someone-else.apk", mtimeMs: 0 },
    { path: "/a/2026-11-20/notes.pdf", mtimeMs: 0 },
  ];
  assert.deepEqual(apksToPrune(files, 3), ["/a/2026-11-18/terminus-all-in-20261118-v1267.apk"]);
  assert.deepEqual(apksToPrune(files, 10), []);
});
