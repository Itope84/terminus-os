import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { SHOWS, TOOLS, appFeaturesBlock } from "./app-features.js";
import { repoRoot } from "./paths.js";

const renderer = (): string | null => {
  try { return readFileSync(join(repoRoot(), "android/app/src/main/java/com/terminus/rec/ChatMarkdown.kt"), "utf8"); } catch { return null; }
};

test("every renderer in the app has a line the agents see", (t) => {
  const src = renderer();
  if (src === null) { t.skip("no android checkout"); return; }
  const ids = new Set(SHOWS.map((f) => f.id));
  const found = new Set<string>();
  // Fenced blocks the app draws as cards: lang == "map" → map.
  for (const m of src.matchAll(/lang == "([a-z0-9-]+)"/g)) found.add(m[1]);
  // terminus:// links drawn as cards (alarm, browser).
  for (const m of src.matchAll(/startsWith\("terminus:\/\/([a-z]+)/g)) found.add(m[1]);
  if (/isImageUrl\(/.test(src)) found.add("image");
  if (/isVideoUrl\(/.test(src)) found.add("video");
  assert.ok(found.size >= 4, `renderer scan found ${[...found]}`);
  for (const f of found) assert.ok(ids.has(f), `the app renders "${f}" but app-features.ts has no SHOWS line for it`);
});

test("block lists shows and tools, skips absent skills, fills the gallery URL", () => {
  const b = appFeaturesBlock(() => true, "http://host:9009/terminus");
  assert.match(b, /^The app shows these in a reply:/);
  assert.match(b, /http:\/\/host:9009\/terminus\/<day>\/<file>/);
  assert.match(b, /```map/);
  assert.match(b, /skills\/location\/where\.py/);
  assert.doesNotMatch(b, /\{gallery\}/);
  const none = appFeaturesBlock(() => false, "http://h/terminus");
  assert.doesNotMatch(none, /```map|where\.py/);
  assert.match(none, /Alarm card/);
});

test("ids are unique and lines stay one line", () => {
  const all = [...SHOWS, ...TOOLS];
  assert.equal(new Set(all.map((f) => f.id)).size, all.length);
  for (const f of all) { assert.ok(!f.line.includes("\n")); assert.ok(f.line.length <= 260, f.id); }
});
