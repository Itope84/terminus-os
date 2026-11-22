/** POST /api/background/stop {chatId, label}: the phone's ✕ on a running job line. The line carries the
 *  job's name only, so the job is found by name among the chat's open starts; Claude jobs are stopped
 *  with Claude's own stop_task request. Other backends have no stop command, so they answer 409. */
import type { IncomingMessage, ServerResponse } from "node:http";
import { markStopped, runningByLabel } from "./background-log.js";
import { getAdapter } from "./backends/registry.js";
import { readBodyText, type Json } from "./util.js";

export async function handleBackgroundStop(req: IncomingMessage, res: ServerResponse, json: Json): Promise<void> {
  let body: Record<string, unknown>;
  try { body = JSON.parse(await readBodyText(req, 10_000)) as Record<string, unknown>; } catch { return json(res, 400, { error: "bad body" }); }
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  const label = typeof body.label === "string" ? body.label : "";
  if (!/^[A-Za-z0-9_:-]{1,80}$/.test(chatId) || !label.trim()) return json(res, 400, { error: "bad request" });
  const job = runningByLabel(chatId, label);
  if (!job) return json(res, 404, { error: "That job is no longer running." });
  const claude = getAdapter("claude") as unknown as { stopTask?: (c: string, t: string) => Promise<{ ok: boolean; reason?: string }> };
  if (typeof claude.stopTask !== "function") return json(res, 409, { error: "This backend can't stop jobs." });
  markStopped(job.id);
  const r = await claude.stopTask(chatId, job.id);
  console.log(`background stop chat=${chatId} id=${job.id} ok=${r.ok}${r.reason ? ` reason=${r.reason}` : ""}`);
  if (!r.ok) return json(res, 409, { error: r.reason ?? "refused" });
  json(res, 200, { ok: true, id: job.id });
}
