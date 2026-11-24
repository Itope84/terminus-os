#!/usr/bin/env node
if (typeof (Promise as any).withResolvers === "undefined") {
  (Promise as any).withResolvers = function <T>() {
    let resolve!: (value: T | PromiseLike<T>) => void;
    let reject!: (reason?: any) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
  };
}
import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { safeNavUrl } from "./nav-guard.js";
import { readFile } from "node:fs/promises";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { json, requireAuth, readJsonBody } from "./http.js";
import { tailnetIp, localIp, mediaBase, port } from "./net.js";
import { getPage, handleOpen, handleScrape, handleAct, handleCookies, handleProfileEnsure, handleProfileDelete, handleProfileList, closeBrowsers, startProfileReaper } from "./browser.js";
import { handleBackendFork, handleBackendCommand } from "./backend-admin.js";

import { execSync } from "node:child_process";

/** App builds seen via /health?v= (one log line each). */
const seenAppBuilds = new Set<string>();


import { bearerMatches, mintLinkToken, queryTokenOk } from "./auth.js";
import { handlePbStream, handlePbReply, handlePbCmd, handlePbStatus, noteAppBuild, setAgentBusyProbe, setRunningChatsProbe } from "./phone-browser.js";
import { handleApprovalRequest, handleApprovalDecide } from "./approvals.js";



/** Media gallery lives in ./media.ts (same process/port/auth).
 *  server.ts keeps browser automation + routing only. */
import { handleMedia, uploadAuthed, debugChatHtml, debugListHtml, handleRepoTree, handleRepoFile } from "./media.js";
import { handleBackends, handleModels, handleChat, handleCommands, handleSessions, handleBindings, handleStop, handleSteer, handleQueuedCancel, handleCompact, handleContext, handleSessionDelete, handleFork, handleTitle, handleTitleSet, handleProgress, handleHistory, handleTurn, handleSync, handleStream, handleDefaultsGet, handleDefaultsPost, backendOk } from "./opencode-proxy.js";
import { getDescriptor, parseBackendId } from "./backends/registry.js";
import { handleInboxPost, handleInboxGet, handleUnread, streamChats } from "./inbox.js";
import { handleWake } from "./wake.js";
import { handleLocationGet, handleLocationReply } from "./location.js";
import { handleTile } from "./map-tiles.js";
import { handleListen } from "./listen.js";
import { busyTurns } from "./opencode-proxy.js";
import { handleGoalArm, handleGoalControl, handleGoalGet } from "./goals.js";
import { handleBackgroundGet } from "./background-log.js";
import { handleBackgroundStop } from "./background-stop.js";
import { setSharePassword, openShareSession, shareSessionFolder, shareCookieName, sharePathAllowed, validFolderName, hasShareFolder, shareUnlockHtml, shareFolderHtml } from "./share.js";
/** Commit the running server was built from (best-effort): stamped into
 *  the debug mirror so staleness is visible — compare with HEAD to know
 *  whether the mirror needs rebuilding. */
const serverBuildSha = (() => {
  try {
    const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
    return execSync("git rev-parse --short HEAD", { cwd: repo, encoding: "utf8" }).trim() || "unknown";
  } catch {
    return "unknown";
  }
})();

const handler = async (req: IncomingMessage, res: ServerResponse) => {
  const rawPath = (req.url || "").split("?")[0];
  // Gallery lives at /terminus only.
  const reqPath = rawPath.replace(/\/+$/, "") || "/";
  if (req.method === "OPTIONS") {
    res.writeHead(204, { "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null", "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS", "Access-Control-Allow-Headers": "Authorization,Content-Type" });
    return res.end();
  }
  if (req.method === "GET" && (req.url === "/health" || req.url?.startsWith("/health?"))) {
    // The app supplies its active chat backend so its online dot reflects
    // both this server and the backend that will receive the next message.
    // ?v=<app versionCode>: one log line per build, so the operator can
    // see which APK is talking without asking.
    try {
      const v = new URL(req.url || "/health", "http://x").searchParams.get("v")?.trim() ?? "";
      if (v && !seenAppBuilds.has(v)) {
        seenAppBuilds.add(v);
        console.log(`app build seen: ${v}`);
      }
      if (v) noteAppBuild(v);
    } catch { /* ignore */ }
    const params = new URL(req.url || "/health", "http://x").searchParams;
    const requestedBackend = params.get("backend") || "opencode";
    let backend = "unavailable";
    try {
      const id = parseBackendId(requestedBackend);
      if (id === "opencode") backend = (await backendOk()) ? "ok" : "unavailable";
      else {
        const descriptor = getDescriptor(id);
        backend = descriptor.available && descriptor.authenticated ? "ok" : "unavailable";
      }
    } catch { /* unknown or unavailable backend remains unavailable */ }
    return json(res, 200, { ok: true, mediaUrl: `http://${localIp()}:${port}/terminus/`, backend });
  }
  if ((rawPath === "/" || rawPath === "") && req.method === "GET") {
    res.writeHead(302, { Location: "/terminus", "Access-Control-Allow-Origin": "*" });
    return res.end();
  }
  // The gallery shell is intentionally public so a normal browser can load
  // the token-entry UI. Its API calls remain protected below.
  if (reqPath === "/terminus" && req.method === "GET" && (req.headers.accept || "").includes("text/html")) {
    // public/gallery.html is the current viewer; public/index.html stays as the classic one (?classic=1, or the
    // fallback when gallery.html is absent), so the new page can never leave the tab without a viewer.
    const publicDir = join(dirname(fileURLToPath(import.meta.url)), "..", "public");
    const classic = /[?&]classic=1(?:&|$)/.test(req.url || "");
    try {
      let html = "";
      if (!classic) { try { html = await readFile(join(publicDir, "gallery.html"), "utf8"); } catch { /* fall back */ } }
      if (!html) html = await readFile(join(publicDir, "index.html"), "utf8");
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null" });
      return res.end(html);
    } catch {
      return json(res, 404, { error: "viewer unavailable" });
    }
  }
  // Device upload pass: the recorder app's X-Upload-Token skips the bearer
  // gate for chunk + upload POSTs only. Everything else needs the bearer.
  if (req.method === "POST" && uploadAuthed(req) &&
      (reqPath === "/terminus" || reqPath === "/echo")) {
    if (await handleMedia(req, res, reqPath, { json, baseUrl: mediaBase() })) return;
  }
  // Debug chat-stream mirror shell. The HTML itself carries no data; every
  // chat/stream call made by the page still requires the bearer token.
  if (reqPath === "/debug/chat" && req.method === "GET" && (req.headers.accept || "").includes("text/html")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null" });
    return res.end(debugChatHtml(serverBuildSha));
  }
  // Debug session-list mirror shell (same merge rule as the app list).
  if (reqPath === "/debug/list" && req.method === "GET" && (req.headers.accept || "").includes("text/html")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null" });
    return res.end(debugListHtml(serverBuildSha));
  }
  // Partner shared folders: bearer-gated password set; public unlock page;
  // cookie-scoped read-only GETs under one folder prefix only. Passwords
  // never logged, never returned, never in links.
  if (reqPath === "/api/share-set" && req.method === "POST") {
    if (!requireAuth(req, res)) return;
    const parsed = await readJsonBody(req, 4000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    const folder = typeof parsed.value.folder === "string" ? parsed.value.folder : "";
    const password = typeof parsed.value.password === "string" ? parsed.value.password : "";
    const r = setSharePassword(folder, password);
    if (!r.ok) return json(res, 400, { error: r.error });
    return json(res, 200, { ok: true, folder, url: `/share/${folder}` });
  }
  // Map tiles for chat map cards (map-tiles.ts): master or link token, like the gallery's images.
  if (reqPath.startsWith("/terminus/_tiles/") && req.method === "GET") {
    if (!requireAuth(req, res)) return;
    return handleTile(reqPath, res);
  }
  {
    const parts = reqPath.split("/").filter(Boolean);
    if (parts[0] === "share" && parts.length >= 2 && validFolderName(parts[1])) {
      const folder = parts[1];
      // In-app WebViews carry the master token in the URL (?token=), not
      // the share cookie (their jar never reliably keeps it). Token is
      // strictly more privileged than the folder password, so it opens
      // the listing too — the unlock page stays password-only.
      let tokenOk = false;
      try {
        const t = new URL(req.url || "", "http://x").searchParams.get("token") || "";
        tokenOk = t.length > 0 && queryTokenOk(t);
      } catch { /* no token */ }
      if (parts.length === 2 && req.method === "GET") {
        if (shareSessionFolder(req) === folder || tokenOk) {
          res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
          return res.end(await shareFolderHtml(folder));
        }
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
        return res.end(shareUnlockHtml(folder));
      }
      if (parts.length === 3 && parts[2] === "unlock" && req.method === "POST") {
        const parsed = await readJsonBody(req, 2000);
        if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
        const sid = openShareSession(folder, typeof parsed.value.password === "string" ? parsed.value.password : "");
        if (!sid) {
          await new Promise((r) => setTimeout(r, 400));
          return json(res, 401, { error: "wrong password" });
        }
        // Continue-to-file target, strictly same-folder (no open redirect).
        const rawThen = typeof parsed.value.then === "string" ? parsed.value.then : "";
        const then = rawThen.startsWith(`/terminus/${folder}/`) ? rawThen : "";
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Set-Cookie": `${shareCookieName(folder)}=${sid}; HttpOnly; Path=/; SameSite=Lax; Max-Age=2592000`,
          "Cache-Control": "no-store",
        });
        return res.end(JSON.stringify({ ok: true, then }));
      }
    }
  }
  // Scoped partner reads: a valid share cookie opens GET file bytes under
  // its folder prefix and nothing else (no list root, no API, no uploads,
  // no deletes — those fall through to the bearer gate below). In-app
  // WebViews send the master ?token= instead of the cookie (see above):
  // same bytes. A browser hitting a shared file with no session goes to
  // the password page (carrying ?then=), never a bare 401 — API-style
  // callers keep the 401.
  if (req.method === "GET") {
    const sf = shareSessionFolder(req);
    let tokenFolder: string | null = null;
    try {
      const u = new URL(req.url || "", "http://x");
      const t = u.searchParams.get("token") || "";
      const seg = u.pathname.split("/").filter(Boolean);
      if (t && queryTokenOk(t) && seg[0] === "terminus" && seg[1] && validFolderName(seg[1]) && hasShareFolder(seg[1])) {
        tokenFolder = seg[1];
      }
    } catch { /* no token */ }
    const folder = sf || tokenFolder;
    if (folder) {
      if (sharePathAllowed(reqPath, folder)) {
        if (await handleMedia(req, res, reqPath, { json, baseUrl: mediaBase() })) return;
      } else if (reqPath === `/terminus/${folder}`) {
        res.writeHead(302, { Location: `/share/${folder}` });
        return res.end();
      }
    } else {
      const seg = reqPath.startsWith("/terminus/") ? reqPath.split("/")[2] : "";
      if (seg && hasShareFolder(seg) &&
        (sharePathAllowed(reqPath, seg) || reqPath === `/terminus/${seg}`) &&
        (req.headers.accept || "").includes("text/html")) {
        // ...unless it carries the master token (in-app WebView): the
        // scoped read above already served it; this is only the bounce
        // for browsers with neither cookie nor token.
        let hasToken = false;
        try {
          const t = new URL(req.url || "", "http://x").searchParams.get("token") || "";
          hasToken = !!t && queryTokenOk(t);
        } catch { /* no token */ }
        if (!hasToken) {
          res.writeHead(302, { Location: `/share/${seg}?then=${encodeURIComponent(reqPath)}` });
          return res.end();
        }
      }
    }
  }
  // Browser login: exchange a pasted token for a persistent login cookie,
  // so shared gallery links never need ?token=. Wrong tokens get a 401
  // and no cookie. Sits before the auth gate by design: it IS the gate.
  if (reqPath === "/terminus-login" && req.method === "POST") {
    const parsed = await readJsonBody(req, 2000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    const token = typeof parsed.value.token === "string" ? parsed.value.token : "";
    if (!token || !bearerMatches(token)) {
      await new Promise((r) => setTimeout(r, 400));
      return json(res, 401, { error: "wrong token" });
    }
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "Set-Cookie": `headless-token=${encodeURIComponent(token)}; HttpOnly; Path=/; SameSite=Lax; Max-Age=2592000`,
    });
    return res.end(JSON.stringify({ ok: true }));
  }
  if (!requireAuth(req, res)) return;
  // Short-lived, GET-only stand-in for the master token in WebView URLs. Master token required to mint.
  if (reqPath === "/api/link-token" && req.method === "POST") {
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    return res.end(JSON.stringify(mintLinkToken()));
  }
  if (reqPath === "/api/approvals/request" && req.method === "POST") return handleApprovalRequest(req, res, json, readJsonBody);
  if (reqPath === "/api/approvals/decide" && req.method === "POST") return handleApprovalDecide(req, res, json, readJsonBody);
  if (reqPath === "/api/pb/stream" && req.method === "GET") return handlePbStream(req, res);
  if (reqPath === "/api/pb/reply" && req.method === "POST") return handlePbReply(req, res, json, readJsonBody);
  if (reqPath === "/api/pb/cmd" && req.method === "POST") return handlePbCmd(req, res, json, readJsonBody);
  if (reqPath === "/api/pb/status" && req.method === "GET") return handlePbStatus(req, res, json);
  if (reqPath === "/api/backends" && req.method === "GET") return handleBackends(req, res, json);
  if (reqPath === "/api/models" && req.method === "GET") return handleModels(req, res, json);
  if (reqPath === "/api/commands" && req.method === "GET") return handleCommands(req, res, json);
  if (reqPath === "/api/sessions" && req.method === "GET") return handleSessions(req, res, json);
  if (reqPath === "/api/bindings" && req.method === "GET") return handleBindings(req, res, json);
  if (reqPath === "/api/defaults" && req.method === "GET") return handleDefaultsGet(req, res, json);
  if (reqPath === "/api/defaults" && req.method === "POST") return handleDefaultsPost(req, res, json);
  if (reqPath === "/api/chat" && req.method === "POST") return handleChat(req, res, json);
  if (reqPath === "/api/stream" && req.method === "GET") return handleStream(req, res);
  if (reqPath === "/api/inbox" && req.method === "POST") return handleInboxPost(req, res, json);
  if (reqPath === "/api/inbox" && req.method === "GET") return handleInboxGet(req, res, json);
  if (reqPath === "/api/unread" && req.method === "GET") return handleUnread(req, res, json);
  if (reqPath === "/api/streams" && req.method === "GET") return json(res, 200, { chats: streamChats() });
  if (reqPath === "/api/wake" && req.method === "GET") return handleWake(req, res);
  if (reqPath === "/api/location" && req.method === "GET") return handleLocationGet(req, res, json);
  if (reqPath === "/api/location/reply" && req.method === "POST") {
    const parsed = await readJsonBody(req, 2000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    return handleLocationReply(res, json, parsed.value);
  }
  if (reqPath === "/api/listen" && req.method === "POST") return handleListen(req, res, json);
  if (reqPath === "/api/listen/stop" && req.method === "POST") return handleListen(req, res, json, true);
  if (reqPath === "/api/busy" && req.method === "GET") return json(res, 200, { turns: busyTurns() });
  if (reqPath === "/api/stop" && req.method === "POST") return handleStop(req, res, json);
  if (reqPath === "/api/title" && req.method === "GET") return handleTitle(req, res, json);
  if (reqPath === "/api/title" && req.method === "POST") return handleTitleSet(req, res, json);
  if (reqPath === "/api/compact" && req.method === "POST") return handleCompact(req, res, json);
  if (reqPath === "/api/context" && req.method === "GET") return handleContext(req, res, json);
  if (reqPath === "/api/session-delete" && req.method === "POST") return handleSessionDelete(req, res, json);
  if (reqPath === "/api/fork" && req.method === "POST") return handleFork(req, res, json);
  if (reqPath === "/api/steer" && req.method === "POST") return handleSteer(req, res, json);
  if (reqPath === "/api/queued-cancel" && req.method === "POST") return handleQueuedCancel(req, res, json);
  if (reqPath === "/api/backend-fork" && req.method === "POST") return handleBackendFork(req, res, json);
  if (reqPath === "/api/backend-command" && req.method === "POST") return handleBackendCommand(req, res, json);
  if (reqPath === "/api/goals/arm" && req.method === "POST") return handleGoalArm(req, res, json);
  if (reqPath === "/api/goals/pause" && req.method === "POST") return handleGoalControl(req, res, json, "pause");
  if (reqPath === "/api/goals/resume" && req.method === "POST") return handleGoalControl(req, res, json, "resume");
  if (reqPath === "/api/goals/remove" && req.method === "POST") return handleGoalControl(req, res, json, "remove");
  if (reqPath === "/api/goals" && req.method === "GET") return handleGoalGet(req, res, json);
  if (reqPath === "/api/background" && req.method === "GET") return handleBackgroundGet(req, res, json);
  if (reqPath === "/api/background/stop" && req.method === "POST") return handleBackgroundStop(req, res, json);
  if (reqPath === "/api/progress" && req.method === "GET") return handleProgress(req, res, json);
  if (reqPath === "/api/turn" && req.method === "GET") return handleTurn(req, res, json);
  if (reqPath === "/api/sync" && req.method === "GET") return handleSync(req, res, json);
  if (reqPath === "/api/history" && req.method === "GET") return handleHistory(req, res, json);
  if (reqPath === "/api/repo-tree" && req.method === "GET") return handleRepoTree(req, res, { json, baseUrl: mediaBase() });
  if (reqPath.startsWith("/repo/") && req.method === "GET") {
    if (await handleRepoFile(req, res, reqPath.replace("/repo/", ""))) return;
  }
  if (reqPath === "/open" && req.method === "POST") return handleOpen(req, res);
  if (reqPath === "/scrape" && req.method === "POST") return handleScrape(req, res);
  if (reqPath === "/act" && req.method === "POST") return handleAct(req, res);
  if (reqPath === "/cookies" && req.method === "POST") return handleCookies(req, res);
  if (reqPath === "/profiles" && req.method === "GET") return handleProfileList(res);
  if (reqPath === "/profile" && req.method === "POST") return handleProfileEnsure(req, res);
  if (reqPath.startsWith("/profile") && req.method === "DELETE") return handleProfileDelete(req, res);
  if (await handleMedia(req, res, reqPath, { json, baseUrl: mediaBase() })) return;
  if (req.url?.startsWith("/screenshot") && req.method === "GET") {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const target = url.searchParams.get("url");
    const profile = url.searchParams.get("profile") || undefined;
    if (!target) return json(res, 400, { error: "missing ?url=" });
    const nav = safeNavUrl(target);
    if (!nav) return json(res, 400, { error: "url must be http(s)" });
    let holder: Awaited<ReturnType<typeof getPage>>;
    try {
      holder = await getPage(profile);
    } catch (e) { return json(res, 400, { error: String(e) }); }
    const { page } = holder;
    try {
      await page.goto(nav, { waitUntil: "networkidle", timeout: 30000 });
      const buf = await page.screenshot({ fullPage: true });
      res.writeHead(200, { "Content-Type": "image/png", "Content-Length": buf.length });
      res.end(buf);
    } catch (e) { json(res, 500, { error: String(e) }); } finally { await holder.done(); }
    return;
  }
  if (req.url?.startsWith("/scrape") && req.method === "GET") {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const target = url.searchParams.get("url");
    if (!target) return json(res, 400, { error: "missing ?url=" });
    // delegate to handleScrape via fake body
    const fakeReq = { [Symbol.asyncIterator]: async function* () { yield JSON.stringify({ url: target }); } } as unknown as IncomingMessage;
    (fakeReq as any).headers = {};
    return handleScrape(fakeReq as IncomingMessage, res);
  }
  json(res, 404, { error: "not found, use POST /open, POST /scrape, POST /act, POST /cookies, GET /profiles, POST /profile, GET /terminus or GET /screenshot?url=" });
};


/** Where to listen. Default: the Tailscale address when Tailscale is up, otherwise loopback only, so the
 *  server is never reachable from the local network by accident. Set HOST=0.0.0.0 to listen everywhere. */
const bindHost = process.env.HOST || tailnetIp() || "127.0.0.1";

const server = createServer(handler);

/** Listen, retrying while a previous instance still holds the port (a restart overlaps its predecessor's
 *  shutdown by a few seconds). Without this the new process dies on EADDRINUSE and the supervisor, which
 *  checks once a minute, leaves the server down until its next pass. Other listen errors still surface. */
function listenRetry(srv: ReturnType<typeof createServer>, host: string, onUp: () => void): void {
  let tries = 40;
  const attempt = () => srv.listen(port, host);
  srv.on("error", (e: NodeJS.ErrnoException) => {
    if (e.code === "EADDRINUSE" && tries-- > 0) { setTimeout(attempt, 500); return; }
    throw e;
  });
  srv.once("listening", onUp);
  attempt();
}

listenRetry(server, bindHost, () => {
  console.log(`server listening on http://${bindHost}:${port} — bearer authentication enabled`);
  if (bindHost === "127.0.0.1" && !process.env.HOST) {
    console.log("Tailscale not detected: listening on loopback only. Set HOST=0.0.0.0 to accept connections from other devices.");
  }
  if (!process.env.HEADLESS_TOKEN) console.log(`bearer token loaded from file (not printed)`);
  // Background jobs are logged per chat even with no phone connected (background-log.ts).
  void import("./opencode-proxy.js").then((m) => m.watchOpencodeBackground());
  // The phone keeps an idle browser session alive while any agent turn is pending.
  void import("./opencode-proxy.js").then((m) => {
    setAgentBusyProbe(() => {
      for (const l of m.pendingByChat.values()) if (l.length) return true;
      return false;
    });
    setRunningChatsProbe(() => [...m.pendingByChat.entries()].filter(([, l]) => l.length > 0).map(([id]) => id));
  }).catch(() => {});
  // M9: warm backend auth flags once at boot (catalog-only, no model spend)
  // so /api/backends reports honest state instead of "login required"
  // until first use. Failures stay lazy (first real call reports them).
  void (async () => {
    try {
      const { getAdapter } = await import("./backends/registry.js");
      const { locationDir } = await import("./opencode-proxy.js");
      const codex = getAdapter("codex");
      await codex.listModels({ chatId: "", backend: "codex", remoteSessionId: null, cwd: locationDir(), model: null });
      console.log("backends: codex auth warm");
    } catch {
      console.log("backends: codex warmup skipped (first call will report)");
    }
  })();
});
// Loopback listener for Tailscale Serve (its proxy can't hairpin to the node's
// own tailnet IP reliably). Same handler, same auth.
if (bindHost !== "127.0.0.1") {
  listenRetry(createServer(handler), "127.0.0.1", () =>
    console.log(`loopback listener on http://127.0.0.1:${port}`));
}

async function shutdown() {
  // Codex app-server and in-flight `claude -p` / `agy` turns (plus their tool grandchildren) are our
  // children: end their process trees, or a restart leaves them running. Bounded so exit never hangs.
  try {
    const { getAdapter } = await import("./backends/registry.js");
    const stops = (["codex", "claude", "agy"] as const).map((id) =>
      (getAdapter(id) as { shutdown?: () => Promise<void> }).shutdown?.().catch(() => {}));
    await Promise.race([Promise.all(stops), new Promise((r) => setTimeout(r, 5000))]);
  } catch { /* exiting anyway */ }
  // Children that die with this process get an explicit "server restarted" outcome, and the results file
  // is flushed, before we exit (the app would otherwise only learn it through "outcome unknown").
  await settleAndFlush("The server restarted while this was running. Send it again.");
  await closeBrowsers();
  process.exit(0);
}

/** Record failures for turns that die with this process and wait (bounded) for the results file. */
async function settleAndFlush(reason: string): Promise<void> {
  try {
    const m = await import("./opencode-proxy.js");
    const n = m.settleDeadOnShutdown(reason);
    if (n > 0) console.log(`shutdown: settled ${n} turn(s) as interrupted by the restart`);
    await Promise.race([m.flushPersistedResults(), new Promise((r) => setTimeout(r, 2000))]);
  } catch { /* exiting anyway */ }
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

// An unhandled rejection in one fire-and-forget path must not take every turn and stream down with it:
// log it (name and message only) and carry on. An uncaught exception leaves state we can't trust, so
// settle what we can, flush, and exit at once so the supervisor relaunches without delay.
process.on("unhandledRejection", (reason) => {
  console.log(`unhandledRejection: ${String((reason as { message?: unknown })?.message ?? reason).slice(0, 300)}`);
});
let crashing = false;
process.on("uncaughtException", (err) => {
  console.log(`uncaughtException: ${String((err as { message?: unknown })?.message ?? err).slice(0, 300)}`);
  if (crashing) return;
  crashing = true;
  setTimeout(() => process.exit(1), 6000).unref();
  void (async () => {
    await settleAndFlush("The server hit an internal error and restarted. Send it again.");
    process.exit(1);
  })();
});

startProfileReaper();
