import { test } from "node:test";
import assert from "node:assert/strict";
import { scheduleRowsFrom } from "./media.js";

test("scheduleRowsFrom maps jobs to their threads, ms times, retired without next", () => {
  const rows = scheduleRowsFrom([
    { name: "morning-brief", cron: "0 7 * * *", next_run: 1795849200 },
    { name: "pinged", at: "2026-12-01 09:00", chat: "s-abc", next_run: 1796115600 },
    { name: "old", retired_at: "2026-09-10T12:00:36", next_run: 1 },
  ]);
  assert.deepEqual(rows.find((r) => r.chat === "schedule:morning-brief"),
    { chat: "schedule:morning-brief", name: "morning-brief", retired: false, cron: "0 7 * * *", next: 1795849200000 });
  assert.equal(rows.find((r) => r.chat === "s-abc")?.at, "2026-12-01 09:00");
  assert.deepEqual(rows.find((r) => r.name === "old"), { chat: "schedule:old", name: "old", retired: true });
});

test("scheduleRowsFrom: jobs sharing a thread keep the soonest live one", () => {
  const rows = scheduleRowsFrom([
    { name: "a", chat: "t", every_min: 60, next_run: 2000 },
    { name: "b", chat: "t", every_min: 10, next_run: 1000 },
    { name: "c", chat: "t", retired_at: "x" },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].name, "b");
});
