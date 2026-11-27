/** Route contract: which requests the server lets in without a token, and that everything else is denied.
 *
 *  Boots the compiled server (dist/server.js) on an ephemeral port with a throwaway HOME and an unreachable
 *  OpenCode, so it never touches real state. These tests pin the behaviour of the gate in server.ts: a route
 *  is public only when it is deliberately listed in PUBLIC below, and every other path (known or not) answers
 *  401 without a credential. Adding a public route is therefore a reviewed change to this file.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { apiRoutes, lateRoutes, routeMatches } from "../dist/routes.js";

const here = dirname(fileURLToPath(import.meta.url));
const TOKEN = "contract-test-token-0123456789";
const FALLTHROUGH_404 = "not found, use POST /open";

/** Every route that needs a credential and is answered by the dispatcher (method, example path). */
const GATED = [
  ["POST", "/api/link-token"], ["POST", "/api/approvals/request"], ["POST", "/api/approvals/decide"],
  ["GET", "/api/pb/stream"], ["POST", "/api/pb/reply"], ["POST", "/api/pb/cmd"], ["GET", "/api/pb/status"],
  ["GET", "/api/backends"], ["GET", "/api/models"], ["GET", "/api/commands"], ["GET", "/api/sessions"],
  ["GET", "/api/bindings"], ["GET", "/api/defaults"], ["POST", "/api/defaults"], ["POST", "/api/chat"],
  ["GET", "/api/stream"], ["POST", "/api/inbox"], ["GET", "/api/inbox"], ["GET", "/api/unread"],
  ["GET", "/api/streams"], ["GET", "/api/wake"], ["GET", "/api/location"], ["POST", "/api/location/reply"],
  ["POST", "/api/listen"], ["POST", "/api/listen/stop"], ["GET", "/api/busy"], ["POST", "/api/stop"],
  ["GET", "/api/title"], ["POST", "/api/title"], ["POST", "/api/compact"], ["GET", "/api/context"],
  ["POST", "/api/session-delete"], ["POST", "/api/fork"], ["POST", "/api/steer"], ["POST", "/api/queued-cancel"],
  ["POST", "/api/backend-fork"], ["POST", "/api/backend-command"], ["POST", "/api/goals/arm"],
  ["POST", "/api/goals/pause"], ["POST", "/api/goals/resume"], ["POST", "/api/goals/remove"],
  ["GET", "/api/goals"], ["GET", "/api/background"], ["POST", "/api/background/stop"], ["GET", "/api/progress"],
  ["GET", "/api/turn"], ["GET", "/api/sync"], ["GET", "/api/history"], ["GET", "/api/repo-tree"],
  ["GET", "/repo/README.md"], ["POST", "/open"], ["POST", "/scrape"], ["POST", "/act"], ["POST", "/cookies"],
  ["GET", "/profiles"], ["POST", "/profile"], ["DELETE", "/profile/demo"],
  // The web app (pwa/index.ts)
  ["GET", "/api/pwa/chats"], ["POST", "/api/pwa/chats"], ["POST", "/api/pwa/chats/read"], ["POST", "/api/pwa/chats/delete"],
  ["GET", "/api/pwa/events"], ["POST", "/api/pwa/presence"], ["GET", "/api/pwa/approvals"], ["GET", "/api/pwa/push"],
  ["POST", "/api/pwa/push/subscribe"], ["POST", "/api/pwa/push/unsubscribe"], ["POST", "/api/pwa/push/test"],
  ["POST", "/api/pwa/transcribe"],
];

/** Gated routes served by the media module and the two late browser GETs. */
const GATED_OTHER = [
  ["GET", "/terminus-config"], ["POST", "/terminus-config"], ["GET", "/api/schedule-rows"], ["GET", "/ops"],
  ["POST", "/echo"], ["GET", "/notes"], ["GET", "/skills"], ["GET", "/notes-profile"], ["GET", "/notes-version"],
  ["POST", "/terminus"], ["GET", "/terminus/anything.png"], ["DELETE", "/terminus/anything.png"],
  ["GET", "/screenshot?url=https://example.com"], ["GET", "/scrape?url=https://example.com"],
];

/** These start a browser or write outside the temp HOME when called with a valid token, so only their gate is tested. */
const NO_AUTHED_PROBE = new Set(["/open", "/scrape", "/act", "/cookies", "/profile", "/profile/demo"]);

let child; let base; let home;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.listen(0, "127.0.0.1", () => { const { port } = s.address(); s.close(() => resolve(port)); });
    s.on("error", reject);
  });
}

before(async () => {
  home = mkdtempSync(join(tmpdir(), "route-contract-"));
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, [join(here, "..", "dist", "server.js")], {
    env: {
      ...process.env, PORT: String(port), HOST: "127.0.0.1", HEADLESS_TOKEN: TOKEN,
      TERMINUS_DIR: home, HOME: home, OPENCODE_BASE_URL: "http://127.0.0.1:9", OPENCODE_PASS: "",
    },
    stdio: "ignore",
  });
  const deadline = Date.now() + 15_000;
  for (;;) {
    try { const r = await fetch(`${base}/health`, { signal: AbortSignal.timeout(1000) }); if (r.ok) break; } catch { /* not up yet */ }
    if (Date.now() > deadline) throw new Error("server did not start");
    await new Promise((r) => setTimeout(r, 100));
  }
});

after(() => {
  child?.kill("SIGTERM");
  if (home) rmSync(home, { recursive: true, force: true });
});

/** One request. Returns { status, body, headers }, or { aborted: true } when it was still open at the timeout
 *  (streams and long-polls are open on purpose: a response that starts is a route that exists). */
async function call(method, path, { auth, headers = {}, body, ms = 2500 } = {}) {
  const h = { ...headers };
  if (auth === true) h.authorization = `Bearer ${TOKEN}`;
  else if (typeof auth === "string") h.authorization = auth;
  if (body !== undefined) h["content-type"] = "application/json";
  try {
    const r = await fetch(base + path, {
      method, headers: h, body: body === undefined ? undefined : JSON.stringify(body),
      redirect: "manual", signal: AbortSignal.timeout(ms),
    });
    let text = "";
    try { text = await r.text(); } catch { /* stream cut by the timeout */ }
    let json; try { json = JSON.parse(text); } catch { /* not json */ }
    return { status: r.status, text, json, headers: r.headers };
  } catch (e) {
    if (e?.name === "TimeoutError" || e?.name === "AbortError") return { aborted: true };
    throw e;
  }
}

const describeRoute = ([m, p]) => `${m} ${p}`;

test("the route table is exactly the documented list", () => {
  // Every documented route is answered by exactly one table entry, and every table entry is documented.
  const table = [...apiRoutes];
  const unmatched = GATED.filter(([m, p]) => !table.some((r) => routeMatches(r, m, p))).map(describeRoute);
  assert.deepEqual(unmatched, [], "documented in this test but missing from routes.ts");
  const undocumented = table
    .filter((r) => !GATED.some(([m, p]) => routeMatches(r, m, p)))
    .map((r) => `${r.method} ${r.path}${r.prefix ? "*" : ""}`);
  assert.deepEqual(undocumented, [], "in routes.ts but not documented in this test");
  assert.equal(table.length, GATED.length);
  const late = lateRoutes.map((r) => `${r.method} ${r.path}${r.prefix ? "*" : ""}`);
  assert.deepEqual(late, ["GET /screenshot*", "GET /scrape*"]);
});

test("every protected route answers 401 without a credential", async () => {
  const failures = [];
  for (const r of [...GATED, ...GATED_OTHER]) {
    const res = await call(r[0], r[1], { body: r[0] === "POST" ? {} : undefined });
    if (res.status !== 401 || res.json?.error !== "unauthorized") failures.push(`${describeRoute(r)} -> ${res.status ?? "aborted"}`);
  }
  assert.deepEqual(failures, []);
});

test("a wrong token or a malformed Authorization header is rejected", async () => {
  for (const auth of ["Bearer not-the-token", "Bearer ", "Basic abc", TOKEN]) {
    for (const r of [["GET", "/api/sessions"], ["POST", "/api/chat"], ["GET", "/terminus/anything.png"]]) {
      const res = await call(r[0], r[1], { auth, body: r[0] === "POST" ? {} : undefined });
      assert.equal(res.status, 401, `${describeRoute(r)} with Authorization "${auth}"`);
    }
  }
});

test("unknown paths are denied by default, not 404", async () => {
  for (const r of [["GET", "/definitely-not-a-route"], ["POST", "/api/nope"], ["DELETE", "/x"], ["PUT", "/api/sessions"], ["GET", "/api"], ["GET", "/.env"], ["GET", "/../etc/passwd"]]) {
    const res = await call(r[0], r[1], { body: r[0] === "POST" ? {} : undefined });
    assert.equal(res.status, 401, describeRoute(r));
  }
});

test("a valid token reaches a real handler for every route (not the fall-through 404)", async () => {
  const missing = [];
  for (const r of GATED) {
    if (NO_AUTHED_PROBE.has(r[1])) continue;
    const res = await call(r[0], r[1], { auth: true, body: r[0] === "POST" ? {} : undefined });
    if (res.aborted) continue; // a stream or long-poll: it started, so the route exists
    if (res.status === 401 || (res.status === 404 && res.json?.error?.startsWith(FALLTHROUGH_404))) missing.push(`${describeRoute(r)} -> ${res.status}`);
  }
  assert.deepEqual(missing, []);
});

test("with a valid token an unknown path gets the 404 hint", async () => {
  const res = await call("GET", "/definitely-not-a-route", { auth: true });
  assert.equal(res.status, 404);
  assert.ok(res.json.error.startsWith(FALLTHROUGH_404));
});

/** The complete list of requests that work with no credential. Anything else must be added here on purpose. */
test("public routes", async () => {
  // health: liveness plus the backend's readiness for the app's online dot
  let res = await call("GET", "/health");
  assert.equal(res.status, 200); assert.equal(res.json.ok, true); assert.ok("backend" in res.json);
  // the bare root bounces to the gallery shell
  res = await call("GET", "/");
  assert.equal(res.status, 302); assert.equal(res.headers.get("location"), "/terminus");
  // the web app's files are public (pwa/static.ts); unbuilt here, so the page answers 503 with how to build it
  res = await call("GET", "/app/");
  assert.equal(res.status, 503);
  res = await call("GET", "/app/chat/s-1");
  assert.equal(res.status, 503);
  // logging out only clears the cookie
  res = await call("POST", "/terminus-logout", { body: {} });
  assert.equal(res.status, 200);
  assert.match(res.headers.get("set-cookie") ?? "", /Max-Age=0/);
  // the gallery shell (token entry UI) and the debug shells are public HTML; their data calls are protected
  for (const p of ["/terminus", "/debug/chat", "/debug/list"]) {
    res = await call("GET", p, { headers: { accept: "text/html" } });
    assert.equal(res.status, 200, p); assert.match(res.text, /<html|<!doctype/i, p);
  }
  // a browser login with a wrong token is refused and sets no cookie
  res = await call("POST", "/terminus-login", { body: { token: "wrong" }, ms: 5000 });
  assert.equal(res.status, 401); assert.equal(res.headers.get("set-cookie"), null);
  // the right token mints the login cookie
  res = await call("POST", "/terminus-login", { body: { token: TOKEN } });
  assert.equal(res.status, 200); assert.match(res.headers.get("set-cookie") ?? "", /^headless-token=/);
  // partner share pages: a public unlock page, and a wrong password is refused
  res = await call("GET", "/share/demo");
  assert.equal(res.status, 200); assert.match(res.text, /html/i);
  res = await call("POST", "/share/demo/unlock", { body: { password: "nope" }, ms: 5000 });
  assert.equal(res.status, 401);
  // CORS preflight never needs a credential
  res = await call("OPTIONS", "/api/chat");
  assert.equal(res.status, 204);
  // these two check the credential themselves
  assert.equal((await call("POST", "/api/share-set", { body: {} })).status, 401);
  assert.equal((await call("GET", "/terminus/_tiles/1/2/3.png")).status, 401);
});

test("the browser login cookie is accepted as a credential", async () => {
  const login = await call("POST", "/terminus-login", { body: { token: TOKEN } });
  const cookie = login.headers.get("set-cookie").split(";")[0];
  const res = await call("GET", "/api/busy", { headers: { cookie } });
  assert.equal(res.status, 200);
  assert.equal(res.json.turns, 0); // a count of unsettled turns; none on a fresh server
});

test("the commands endpoint lists a backend's commands instead of failing", async () => {
  // Claude's command list needs no login or running backend, so a 200 here proves the handler itself works.
  // (The list is empty in this throwaway HOME; what matters is that it is a list, not a server error.)
  const res = await call("GET", "/api/commands?backend=claude", { auth: true });
  assert.equal(res.status, 200, res.text);
  assert.equal(res.json.backend, "claude");
  assert.ok(Array.isArray(res.json.commands), "expected a command list");
});
