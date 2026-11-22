import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { _setBackgroundFile, noteBackground, backgroundAfter, openStarts, STALE_START_MS, runningByLabel, markStopped } from "./background-log.js";

const fresh = () => _setBackgroundFile(join(mkdtempSync(join(tmpdir(), "bglog-")), "bg.json"));

test("finishes pair with their start by id, elapsed from the server clock", () => {
  fresh();
  noteBackground("c1", { status: "started", id: "a", label: "build" }, 1000);
  noteBackground("c1", { status: "started", id: "b", label: "test" }, 2000);
  const b = noteBackground("c1", { status: "done", id: "b" }, 5000)!;
  assert.equal(b.elapsedMs, 3000, "b finished first and still gets its own start");
  const a = noteBackground("c1", { status: "failed", id: "a" }, 9000)!;
  assert.equal(a.elapsedMs, 8000);
  assert.equal(backgroundAfter("c1", 0, 9000).running, 0);
});

test("ids that differ at both ends fall back to the oldest open start", () => {
  fresh();
  noteBackground("c1", { status: "started", id: "call_1" }, 1000);
  assert.equal(noteBackground("c1", { status: "done", id: "shell_9" }, 4000)!.elapsedMs, 3000);
});

test("a redelivered finish is logged once; catch-up returns only what is after the seq", () => {
  fresh();
  noteBackground("c1", { status: "started", id: "a" }, 1000);
  assert.ok(noteBackground("c1", { status: "done", id: "a" }, 2000));
  assert.equal(noteBackground("c1", { status: "done", id: "a" }, 2100), null);
  const r = backgroundAfter("c1", 1, 3000);
  assert.deepEqual(r.events.map((e) => e.status), ["done"]);
  assert.equal(r.seq, 2);
  assert.deepEqual(backgroundAfter("nobody", 0), { events: [], seq: 0, running: 0 });
});

test("a start with no finish stops counting as running after the stale window", () => {
  fresh();
  const s = noteBackground("c1", { status: "started", id: "x" }, 0)!;
  assert.equal(openStarts([s], 1000).length, 1);
  assert.equal(openStarts([s], STALE_START_MS + 1).length, 0);
  assert.equal(noteBackground("c1", { status: "nonsense" }), null);
});

test("a finish keeps its start's name; the backend's closing summary moves to the detail", () => {
  fresh();
  noteBackground("c1", { status: "started", id: "j1", label: "Build v1304 and commit" }, 1000);
  const f = noteBackground("c1", { status: "done", id: "j1", label: 'Background command "Build v1304 and commit" completed (exit code 0)' }, 5000)!;
  assert.equal(f.label, "Build v1304 and commit");
  assert.match(f.detail, /completed \(exit code 0\)/);
  assert.equal(f.elapsedMs, 4000);
  // no label at the finish: start's name, no junk detail
  noteBackground("c1", { status: "started", id: "j2", label: "sleep 5" }, 6000);
  const g = noteBackground("c1", { status: "done", id: "j2" }, 7000)!;
  assert.equal(g.label, "sleep 5");
  assert.equal(g.detail, "");
});

test("the phone's ✕ finds the open job by its shown name; a stopped finish says stopped", () => {
  fresh();
  const now = Date.now();
  noteBackground("c1", { status: "started", id: "t1", label: "count  to ten" }, now - 3000);
  noteBackground("c1", { status: "started", id: "t2", label: "build" }, now - 2000);
  noteBackground("c1", { status: "done", id: "t2" }, now - 1000);
  assert.equal(runningByLabel("c1", "count to ten", now)?.id, "t1");
  assert.equal(runningByLabel("c1", "build", now), null, "finished jobs can't be stopped");
  assert.equal(runningByLabel("c2", "count to ten", now), null);
  markStopped("t1");
  const fin = noteBackground("c1", { status: "failed", id: "t1", label: "Background command killed", detail: "partial output", full: "x".repeat(300) }, now)!;
  assert.equal(fin.detail, "stopped");
  assert.equal(fin.full, undefined);
  assert.equal(fin.label, "count to ten");
  assert.equal(runningByLabel("c1", "count to ten", now), null);
});
