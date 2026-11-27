/** The server's authenticated HTTP API as data.
 *
 *  One entry per endpoint, in the order they are tried (first match wins). The request handler in server.ts
 *  runs this table only after the credential check, so every route listed here needs a valid token; the few
 *  public routes live in server.ts ahead of that check and are pinned by test/route-contract.test.mjs.
 *  Adding an endpoint is one line here plus its handler.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { json, readJsonBody } from "./http.js";
import { mediaBase } from "./net.js";
import { handleApprovalDecide, handleApprovalRequest } from "./approvals.js";
import { mintLinkToken } from "./auth.js";
import { handleBackendCommand, handleBackendFork } from "./backend-admin.js";
import { handleBackgroundGet } from "./background-log.js";
import { handleBackgroundStop } from "./background-stop.js";
import { handleAct, handleCookies, handleOpen, handleProfileDelete, handleProfileEnsure, handleProfileList, handleScrape, handleScrapeGet, handleScreenshotGet } from "./browser.js";
import { handleGoalArm, handleGoalControl, handleGoalGet } from "./goals.js";
import { handleInboxGet, handleInboxPost, handleUnread, streamChats } from "./inbox.js";
import { handleListen } from "./listen.js";
import { handleLocationGet, handleLocationReply } from "./location.js";
import { handleRepoFile, handleRepoTree } from "./media.js";
import { busyTurns, handleBackends, handleBindings, handleChat, handleCommands, handleCompact, handleContext, handleDefaultsGet, handleDefaultsPost, handleFork, handleHistory, handleModels, handleProgress, handleQueuedCancel, handleSessionDelete, handleSessions, handleSteer, handleStop, handleStream, handleSync, handleTitle, handleTitleSet, handleTurn } from "./opencode-proxy.js";
import { handlePbCmd, handlePbReply, handlePbStatus, handlePbStream } from "./phone-browser.js";
import { handleWake } from "./wake.js";
import { pwaRoutes } from "./pwa/index.js";
import { remoteRoutes } from "./pwa/remote.js";

export interface RouteContext {
  req: IncomingMessage;
  res: ServerResponse;
  /** The request path without its query string or trailing slashes. */
  path: string;
}

export type Method = "GET" | "POST" | "DELETE";

export interface Route {
  method: Method;
  path: string;
  /** Match every path that starts with `path`, not only `path` itself. */
  prefix?: boolean;
  handle: (ctx: RouteContext) => unknown;
  /** The handler may resolve to `false` to say the request is not its to answer; dispatch then keeps looking. */
  mayDecline?: boolean;
}

type Handler = Route["handle"];
const get = (path: string, handle: Handler): Route => ({ method: "GET", path, handle });
const post = (path: string, handle: Handler): Route => ({ method: "POST", path, handle });

export function routeMatches(route: Route, method: string | undefined, path: string): boolean {
  return route.method === method && (route.prefix ? path.startsWith(route.path) : path === route.path);
}

/** Run the first route that matches and answers. Resolves true when a route handled the request. */
export async function dispatch(routes: readonly Route[], ctx: RouteContext): Promise<boolean> {
  for (const route of routes) {
    if (!routeMatches(route, ctx.req.method, ctx.path)) continue;
    const result = await route.handle(ctx);
    if (route.mayDecline && result === false) continue;
    return true;
  }
  return false;
}

/** Authenticated endpoints answered by this server's own handlers. */
export const apiRoutes: readonly Route[] = [
  // Short-lived, GET-only stand-in for the master token in WebView URLs. Master token required to mint.
  post("/api/link-token", ({ res }) => {
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    res.end(JSON.stringify(mintLinkToken()));
  }),
  post("/api/approvals/request", ({ req, res }) => handleApprovalRequest(req, res, json, readJsonBody)),
  post("/api/approvals/decide", ({ req, res }) => handleApprovalDecide(req, res, json, readJsonBody)),
  get("/api/pb/stream", ({ req, res }) => handlePbStream(req, res)),
  post("/api/pb/reply", ({ req, res }) => handlePbReply(req, res, json, readJsonBody)),
  post("/api/pb/cmd", ({ req, res }) => handlePbCmd(req, res, json, readJsonBody)),
  get("/api/pb/status", ({ req, res }) => handlePbStatus(req, res, json)),
  get("/api/backends", ({ req, res }) => handleBackends(req, res, json)),
  get("/api/models", ({ req, res }) => handleModels(req, res, json)),
  get("/api/commands", ({ req, res }) => handleCommands(req, res, json)),
  get("/api/sessions", ({ req, res }) => handleSessions(req, res, json)),
  get("/api/bindings", ({ req, res }) => handleBindings(req, res, json)),
  get("/api/defaults", ({ req, res }) => handleDefaultsGet(req, res, json)),
  post("/api/defaults", ({ req, res }) => handleDefaultsPost(req, res, json)),
  post("/api/chat", ({ req, res }) => handleChat(req, res, json)),
  get("/api/stream", ({ req, res }) => handleStream(req, res)),
  post("/api/inbox", ({ req, res }) => handleInboxPost(req, res, json)),
  get("/api/inbox", ({ req, res }) => handleInboxGet(req, res, json)),
  get("/api/unread", ({ req, res }) => handleUnread(req, res, json)),
  get("/api/streams", ({ res }) => json(res, 200, { chats: streamChats() })),
  get("/api/wake", ({ req, res }) => handleWake(req, res)),
  get("/api/location", ({ req, res }) => handleLocationGet(req, res, json)),
  post("/api/location/reply", async ({ req, res }) => {
    const parsed = await readJsonBody(req, 2000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    return handleLocationReply(res, json, parsed.value);
  }),
  post("/api/listen", ({ req, res }) => handleListen(req, res, json)),
  post("/api/listen/stop", ({ req, res }) => handleListen(req, res, json, true)),
  get("/api/busy", ({ res }) => json(res, 200, { turns: busyTurns() })),
  post("/api/stop", ({ req, res }) => handleStop(req, res, json)),
  get("/api/title", ({ req, res }) => handleTitle(req, res, json)),
  post("/api/title", ({ req, res }) => handleTitleSet(req, res, json)),
  post("/api/compact", ({ req, res }) => handleCompact(req, res, json)),
  get("/api/context", ({ req, res }) => handleContext(req, res, json)),
  post("/api/session-delete", ({ req, res }) => handleSessionDelete(req, res, json)),
  post("/api/fork", ({ req, res }) => handleFork(req, res, json)),
  post("/api/steer", ({ req, res }) => handleSteer(req, res, json)),
  post("/api/queued-cancel", ({ req, res }) => handleQueuedCancel(req, res, json)),
  post("/api/backend-fork", ({ req, res }) => handleBackendFork(req, res, json)),
  post("/api/backend-command", ({ req, res }) => handleBackendCommand(req, res, json)),
  post("/api/goals/arm", ({ req, res }) => handleGoalArm(req, res, json)),
  post("/api/goals/pause", ({ req, res }) => handleGoalControl(req, res, json, "pause")),
  post("/api/goals/resume", ({ req, res }) => handleGoalControl(req, res, json, "resume")),
  post("/api/goals/remove", ({ req, res }) => handleGoalControl(req, res, json, "remove")),
  get("/api/goals", ({ req, res }) => handleGoalGet(req, res, json)),
  get("/api/background", ({ req, res }) => handleBackgroundGet(req, res, json)),
  post("/api/background/stop", ({ req, res }) => handleBackgroundStop(req, res, json)),
  get("/api/progress", ({ req, res }) => handleProgress(req, res, json)),
  get("/api/turn", ({ req, res }) => handleTurn(req, res, json)),
  get("/api/sync", ({ req, res }) => handleSync(req, res, json)),
  get("/api/history", ({ req, res }) => handleHistory(req, res, json)),
  get("/api/repo-tree", ({ req, res }) => handleRepoTree(req, res, { json, baseUrl: mediaBase() })),
  // Read-only files of the checkout. A path it does not serve falls through to the routes below.
  { method: "GET", path: "/repo/", prefix: true, mayDecline: true,
    handle: ({ req, res, path }) => handleRepoFile(req, res, path.replace("/repo/", "")) },
  post("/open", ({ req, res }) => handleOpen(req, res)),
  post("/scrape", ({ req, res }) => handleScrape(req, res)),
  post("/act", ({ req, res }) => handleAct(req, res)),
  post("/cookies", ({ req, res }) => handleCookies(req, res)),
  get("/profiles", ({ res }) => handleProfileList(res)),
  post("/profile", ({ req, res }) => handleProfileEnsure(req, res)),
  { method: "DELETE", path: "/profile", prefix: true, handle: ({ req, res }) => handleProfileDelete(req, res) },
  ...pwaRoutes,
  ...remoteRoutes,
];

/** Tried after the gallery and media routes, which also answer GETs for some of these paths. */
export const lateRoutes: readonly Route[] = [
  { method: "GET", path: "/screenshot", prefix: true, handle: ({ req, res }) => handleScreenshotGet(req, res) },
  { method: "GET", path: "/scrape", prefix: true, handle: ({ req, res }) => handleScrapeGet(req, res) },
];
