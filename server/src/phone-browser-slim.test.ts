import test from "node:test";
import assert from "node:assert/strict";
import { slimReply } from "./phone-browser.js";

test("pb replies drop console chatter but keep console errors and real events", () => {
  const warn = (i: number) => ({ seq: i, type: "console", level: "warn", text: "Feature-Policy" });
  const reply = {
    ok: true,
    report: { events: [warn(1), warn(2), { seq: 3, type: "console", level: "error", text: "boom" }, { seq: 4, type: "navigation", url: "x" }] },
    reports: [{ events: Array.from({ length: 20 }, (_, i) => ({ seq: i, type: "dialog" })) }],
  };
  slimReply(reply);
  assert.deepEqual(reply.report.events.map((e) => e.seq), [3, 4]);
  assert.equal((reply.report as Record<string, unknown>).consoleDropped, 2);
  assert.equal(reply.reports[0].events.length, 8);
  assert.equal((reply.reports[0] as Record<string, unknown>).consoleDropped, undefined);
});
