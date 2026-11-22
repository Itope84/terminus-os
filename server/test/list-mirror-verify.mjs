/** Manual live verification for the session list (same endpoints as the app).
 *
 *  This is NOT a unit test and must not match test/*.test.mjs. Run it by hand:
 *    TERMINUS_BEARER=$(cat ~/.config/terminus/browser/token) \
 *      node test/list-mirror-verify.mjs
 *  or let it read ~/.config/terminus/browser/token (never printed).
 *
 *  It walks the app's listing lifecycle on throwaway sessions: create →
 *  adopt → fork → delete (must leave NO orphan) → delete, asserting the
 *  visible list at each step and that the id set returns to baseline.
 *  One harmless prompt is enqueued (never waited on).
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";

const BASE = process.env.TERMINUS_BASE || "http://127.0.0.1:9009";
const TOKEN =
  process.env.TERMINUS_BEARER ||
  (() => {
    try {
      return readFileSync(`${homedir()}/.config/terminus/browser/token`, "utf8").trim();
    } catch {
      return "";
    }
  })();
if (!TOKEN) {
  console.error("missing bearer (set TERMINUS_BEARER)");
  process.exit(2);
}

const chatId = `list-verify-${Date.now().toString(36)}`;
const created = new Set();
const log = [];

async function api(path, opts = {}) {
  const r = await fetch(BASE + path, {
    ...opts,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(opts.headers || {}) },
  });
  const text = await r.text().catch(() => "");
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text.slice(0, 200) };
  }
  return { status: r.status, body };
}

async function listIds() {
  const r = await api("/api/sessions");
  if (r.status !== 200 || !Array.isArray(r.body?.sessions)) throw new Error("sessions list failed");
  return new Set(r.body.sessions.map((s) => s.id));
}

/** The app's import filter, mirrored: owned + buried ids never show. */
function imports(ids, owned, buried = new Set()) {
  return [...ids].filter((id) => !owned.has(id) && !buried.has(id));
}

function check(name, cond) {
  log.push(`${cond ? "PASS" : "FAIL"} ${name}`);
  if (!cond) throw new Error(`check failed: ${name}`);
}

try {
  const baseline = await listIds();

  // Create: blank sessionID mints + seeds; reply adopts the id.
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "list verify A", text: "Reply with exactly LIST-VERIFY-OK" }),
  });
  check("create accepted", post.status === 200 && post.body?.accepted === true);
  const A = post.body?.sessionID;
  check("create returns sessionID", typeof A === "string" && A.startsWith("ses_"));
  created.add(A);

  // Same turnId retried adopts the same session (no orphan twin).
  const retry = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "list verify A", text: "Reply with exactly LIST-VERIFY-OK", turnId: post.body.turnId }),
  });
  check("idempotent retry adopts same session", retry.status === 200 && retry.body?.sessionID === A);

  let ids = await listIds();
  check("new session listed", ids.has(A));
  check("owned session hidden from imports", !imports(ids, new Set([A])).includes(A));
  check("unowned session offered as import", imports(ids, new Set()).includes(A));

  // Fork: may need the background turn to land first ("empty session").
  let F = "";
  for (let i = 0; i < 24 && !F; i++) {
    const fork = await api("/api/fork", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionID: A }),
    });
    if (fork.status === 200 && fork.body?.sessionID) F = fork.body.sessionID;
    else if (i < 23) await new Promise((r) => setTimeout(r, 5000));
  }
  check("fork returns a distinct session", F !== "" && F !== A);
  created.add(F);
  ids = await listIds();
  check("fork listed", ids.has(F));

  // Delete the first session: the retired id must NOT linger as an import.
  const restart = await api("/api/session-delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionID: A }),
  });
  check("delete ok", restart.status === 200 && restart.body?.ok === true);
  created.delete(A);
  ids = await listIds();
  check("deleted session gone, not orphaned", !ids.has(A));
  const ghost = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, sessionID: A, text: "should 404" }),
  });
  check("dead session 404s", ghost.status === 404);

  // Delete the fork; the set returns to baseline.
  const del = await api("/api/session-delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionID: F }),
  });
  check("fork deleted", del.status === 200 && del.body?.ok === true);
  created.delete(F);
  ids = await listIds();
  check("fork gone", !ids.has(F));
  check(
    "baseline restored",
    ids.size === baseline.size && [...baseline].every((id) => ids.has(id)),
  );
  // Delete-race window probe (supports the client tombstone-before-refresh
  // rule): fire a delete without awaiting, list immediately, and report
  // whether the session was still visible mid-delete. Log-only — timing
  // dependent by nature; the tombstone exists precisely because this
  // window is real.
  const mk = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId: `list-race-${Date.now().toString(36)}`, title: "list race", text: "Reply with exactly LIST-RACE-OK" }),
  });
  const R = mk.body?.sessionID;
  if (typeof R === "string" && R) {
    created.add(R);
    const firing = api("/api/session-delete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionID: R }),
    });
    const mid = await listIds();
    const done = await firing;
    created.delete(R);
    const after = await listIds();
    log.push(`INFO race-window-visible-mid-delete=${mid.has(R)} delete-ok=${done.status === 200} gone-after=${!after.has(R)}`);
  }
} finally {
  for (const sid of created) {
    try {
      await api("/api/session-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionID: sid }),
      });
    } catch { /* best-effort */ }
  }
}

console.log(JSON.stringify({ chatId, steps: log }));
console.log("list verify: ok");
