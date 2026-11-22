import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { claudeCommandDescription, claudeCommandList, skillCommands } from "./claude-commands.js";

test("description: front matter wins, folded values take the first line, body is the fallback", () => {
  assert.equal(claudeCommandDescription("---\ndescription: \"Review the diff\"\nallowed-tools: Bash\n---\nbody", "x"), "Review the diff");
  assert.equal(claudeCommandDescription("---\nname: a\ndescription: >\n  Check the logs\n  and report\n---\n", "x"), "Check the logs");
  assert.equal(claudeCommandDescription("# Deploy it\n\nmore", "x"), "Deploy it");
  assert.equal(claudeCommandDescription("", "fallback"), "fallback");
});

test("lists project and user commands and skills; the project wins a name clash; bad names are skipped", () => {
  const root = mkdtempSync(join(tmpdir(), "cc-"));
  const proj = join(root, "proj");
  const home = join(root, "home");
  mkdirSync(join(proj, ".claude", "commands"), { recursive: true });
  mkdirSync(join(home, ".claude", "commands"), { recursive: true });
  mkdirSync(join(home, ".claude", "skills", "tidy"), { recursive: true });
  mkdirSync(join(home, ".claude", "skills", "bad name"), { recursive: true });
  writeFileSync(join(proj, ".claude", "commands", "ship.md"), "---\ndescription: project ship\n---\n");
  writeFileSync(join(home, ".claude", "commands", "ship.md"), "---\ndescription: user ship\n---\n");
  writeFileSync(join(home, ".claude", "commands", "notes.md"), "Summarise notes\n");
  writeFileSync(join(home, ".claude", "commands", "bad name.md"), "x");
  writeFileSync(join(home, ".claude", "commands", "readme.txt"), "x");
  writeFileSync(join(home, ".claude", "skills", "tidy", "SKILL.md"), "---\nname: tidy\ndescription: Tidy the repo\n---\n");
  writeFileSync(join(home, ".claude", "skills", "bad name", "SKILL.md"), "x");
  const out = claudeCommandList(proj, home, undefined);
  assert.deepEqual(out.map((c) => c.name), ["/ship", "/notes", "/tidy"]);
  assert.equal(out[0].description, "project ship");
  assert.equal(out[1].description, "Summarise notes");
  assert.ok(out.every((c) => c.available));
});

test("commands Claude itself reported decide the list; folders only add descriptions; clear is hidden", () => {
  const root = mkdtempSync(join(tmpdir(), "cc-"));
  const home = join(root, "home");
  mkdirSync(join(home, ".claude", "commands"), { recursive: true });
  writeFileSync(join(home, ".claude", "commands", "ship.md"), "---\ndescription: Ship it\n---\n");
  writeFileSync(join(home, ".claude", "commands", "unlisted.md"), "x");
  const out = claudeCommandList(join(root, "proj"), home, undefined, ["clear", "compact", "ship", "context"]);
  assert.deepEqual(out.map((c) => c.name), ["/compact", "/ship", "/context"]);
  assert.equal(out[1].description, "Ship it");
  assert.equal(out[0].description, "Summarise the conversation to free up context");
});

test("nothing there is an empty list, not an error", () => {
  const root = mkdtempSync(join(tmpdir(), "cc-"));
  assert.deepEqual(claudeCommandList(join(root, "none"), join(root, "nohome"), undefined), []);
});

test("skillCommands lists Terminus skills as kind=skill, skipping hidden and unnamed ones", () => {
  const dir = mkdtempSync(join(tmpdir(), "skills-"));
  for (const d of ["notebook", "secret", "bad name", "empty"]) mkdirSync(join(dir, d), { recursive: true });
  writeFileSync(join(dir, "notebook", "SKILL.md"), "---\nname: notebook\ndescription: Search the notebook\n---\n");
  writeFileSync(join(dir, "secret", "SKILL.md"), "---\nname: secret\ndescription: x\nuser-invocable: false\n---\n");
  writeFileSync(join(dir, "bad name", "SKILL.md"), "---\ndescription: x\n---\n");
  const got = skillCommands(dir);
  assert.deepEqual(got, [{ name: "/notebook", description: "Search the notebook", available: true, kind: "skill" }]);
  assert.deepEqual(skillCommands(join(dir, "missing")), []);
});
