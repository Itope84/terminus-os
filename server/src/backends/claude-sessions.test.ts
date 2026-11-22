import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { claudeSessionCwd, claudeSessionDirs, claudeSessionFile } from "./claude.js";

const ID = "1155c350-1298-4d52-a3d8-14abd1ec33f4";

function fakeHome(folders: string[]): string {
  const home = mkdtempSync(join(tmpdir(), "claude-sessions-"));
  for (const f of folders) mkdirSync(join(home, ".claude", "projects", f), { recursive: true });
  return home;
}

test("session dirs cover every project folder, terminus first", () => {
  const home = fakeHome(["-Users-x-Desktop", "-Users-x-Desktop-Drift", "-Users-x", "-private-tmp"]);
  const dirs = claudeSessionDirs(home, "/home/user/work/terminus").map((d) => d.split("/").pop());
  assert.equal(dirs[0], "-home-user-work-terminus");
  assert.deepEqual(new Set(dirs.slice(1)), new Set(["-Users-x-Desktop", "-Users-x-Desktop-Drift", "-Users-x", "-private-tmp"]));
});

test("session file is found in whichever folder holds it", () => {
  const home = fakeHome(["-Users-x-Desktop-Drift", "-private-tmp"]);
  const holder = join(home, ".claude", "projects", "-private-tmp", `${ID}.jsonl`);
  writeFileSync(holder, "{}\n");
  const dirs = claudeSessionDirs(home, "/home/user/work/terminus");
  assert.equal(claudeSessionFile(ID, dirs), holder);
  assert.equal(claudeSessionFile("00000000-0000-4000-8000-000000000000", dirs), null);
});

test("session cwd comes from the first line that carries one, skipping a cut-off head", () => {
  const dir = mkdtempSync(join(tmpdir(), "claude-cwd-"));
  const file = join(dir, `${ID}.jsonl`);
  writeFileSync(file, [
    '{"type":"queue-operation"}',
    '{"type":"user","cwd":"/Users/x/Desktop/Drift","message":{}}',
    "",
  ].join("\n"));
  assert.equal(claudeSessionCwd(file), "/Users/x/Desktop/Drift");
  const bare = join(dir, "bare.jsonl");
  writeFileSync(bare, '{"type":"mode"}\n');
  assert.equal(claudeSessionCwd(bare), "");
});
