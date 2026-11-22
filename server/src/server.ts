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
import { chromium, Browser, BrowserContext, Page } from "playwright";
import { readFile, writeFile, mkdir, readdir, rm } from "node:fs/promises";
import { createReadStream, existsSync, statSync } from "node:fs";import { join, basename, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir, homedir } from "node:os";
import { networkInterfaces } from "node:os";
import type { Json } from "./util.js";

import { execSync } from "node:child_process";

let browser: Browser | null = null;

async function getBrowser() {
  if (!browser) browser = await chromium.launch();
  return browser;
}

/** Persistent per-site login profiles. Cookies/storage live on disk under
 *  ~/.config/terminus/browser/profiles/<name>/ — never in the repo.
 *  Log in once (headed window), fetch with the session forever. */
const PROFILES_DIR = join(homedir(), ".config", "terminus", "browser", "profiles");
const CLONES_DIR = join(homedir(), ".config", "terminus", "browser", "clones");
type Prof = { ctx: BrowserContext; headed: boolean; usedAt: number; activeOperations: number; closing?: boolean };
const profiles = new Map<string, Prof>();

/** App builds seen via /health?v= (one log line each). */
const seenAppBuilds = new Set<string>();

function profileName(name: string): string {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(name || "")) throw new Error("bad profile name (a-z, 0-9, -, _)");
  return name;
}

/** profileKey splits back to the bare name for close/delete-by-name. */
function ctxsFor(name: string): Array<[string, Prof]> {
  return [...profiles.entries()].filter(([k]) => k.split("|")[0] === name);
}

async function getProfile(name: string, headed = false, opts: { channel?: string; clone?: string } = {}): Promise<BrowserContext> {
  const key = profileName(name);
  const channel = opts.channel === "chrome" ? "chrome" : "chromium";
  let userDir = join(PROFILES_DIR, key);
  if (opts.clone) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(opts.clone)) throw new Error("bad clone name (a-z, 0-9, -, _)");
    userDir = join(CLONES_DIR, opts.clone);
  }
  const cacheKey = `${key}|${headed ? "h" : ""}|${channel}|${userDir}`;
  const cached = profiles.get(cacheKey);
  if (cached) {
    if (cached.closing) throw new Error(`browser profile "${key}" is closing; retry shortly`);
    cached.usedAt = Date.now();
    return cached.ctx;
  }
  // Never pull the rug: an open, recently used, or actively serving profile
  // stays alive and the conflicting launch is refused instead of killing it.
  // Stale contexts recycle so a params change isn't stuck forever.
  for (const [k, p] of ctxsFor(key)) {
    let pages = 0;
    try { pages = p.ctx.pages().length; } catch { /* unreadable: treat as live */ pages = 1; }
    const busy = p.closing || pages > 0 || p.activeOperations > 0 || Date.now() - p.usedAt < 5 * 60_000;
    if (busy) throw new Error(`browser profile "${key}" is in use by the agent — close it first or wait`);
    await p.ctx.close().catch(() => {});
    profiles.delete(k);
  }
  await mkdir(userDir, { recursive: true });
  const launchOpts: Record<string, unknown> = { headless: !headed };
  if (channel === "chrome") launchOpts.channel = "chrome";
  const ctx = await chromium.launchPersistentContext(userDir, launchOpts as Parameters<typeof chromium.launchPersistentContext>[1]);
  const prof: Prof = { ctx, headed, usedAt: Date.now(), activeOperations: 0 };
  try { await trackProfileActivity(prof); } catch (e) { await ctx.close().catch(() => {}); throw e; }
  profiles.set(cacheKey, prof);
  return ctx;
}

/** Keep headed profiles alive while a person is interacting with their pages.
 *  Only trusted browser input refreshes the idle clock; page scripts cannot
 *  keep abandoned windows alive by dispatching synthetic events. */
async function trackProfileActivity(prof: Prof): Promise<void> {
  await prof.ctx.exposeBinding("__terminusProfileActivity", () => { prof.usedAt = Date.now(); });
  const script = `(() => {
    if (window.__terminusActivityInstalled) return;
    window.__terminusActivityInstalled = true;
    let last = 0;
    const touch = (event) => {
      if (!event.isTrusted) return;
      const now = Date.now();
      if (now - last < 5000) return;
      last = now;
      window.__terminusProfileActivity();
    };
    for (const name of ['pointerdown', 'keydown', 'touchstart', 'wheel'])
      window.addEventListener(name, touch, { capture: true, passive: true });
  })()`;
  await prof.ctx.addInitScript({ content: script });
  for (const page of prof.ctx.pages()) await page.evaluate(script).catch(() => {});
}

/** Page from the shared browser, or from a named login profile.
 *  Reuses a cached profile context as-is (headed included) so the agent
 *  can drive a visible window the human is watching; creates headless
 *  only when nothing is cached. */
async function getPage(profile?: string) {
  if (!profile) {
    const b = await getBrowser();
    const page = await b.newPage();
    return { page, done: () => page.close() };
  }
  const key = profileName(profile);
  let hit: [string, Prof] | undefined = ctxsFor(key)[0];
  if (hit?.[1].closing) throw new Error(`browser profile "${key}" is closing; retry shortly`);
  const ctx = hit ? hit[1].ctx : await getProfile(key);
  hit = ctxsFor(key).find(([, p]) => p.ctx === ctx);
  const prof = hit?.[1];
  if (prof) {
    prof.usedAt = Date.now();
    prof.activeOperations++;
  }
  let page: Page;
  try { page = await ctx.newPage(); }
  catch (e) { if (prof) prof.activeOperations--; throw e; }
  let finished = false;
  return { page, done: async () => {
    if (finished) return;
    finished = true;
    try { await page.close(); } finally { if (prof) prof.activeOperations = Math.max(0, prof.activeOperations - 1); }
  } };
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "Content-Type": "application/json", "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null" });
  res.end(JSON.stringify(body));
}

import { authorized, bearerMatches, mintLinkToken, pbScopedAuthorized, queryTokenOk } from "./auth.js";
import { handlePbStream, handlePbReply, handlePbCmd, handlePbStatus, noteAppBuild, setAgentBusyProbe, setRunningChatsProbe } from "./phone-browser.js";
import { handleApprovalRequest, handleApprovalDecide } from "./approvals.js";
function requireAuth(req: IncomingMessage, res: ServerResponse): boolean {
  if (authorized(req) || pbScopedAuthorized(req)) return true;
  json(res, 401, { error: "unauthorized" });
  return false;
}

/** Capped JSON body reader for automation endpoints (DoS guard): these
 *  payloads are small control messages, never uploads. */
async function readJsonBody(req: IncomingMessage, max = 200_000): Promise<{ ok: true; value: Record<string, unknown> } | { ok: false; status: number; error: string }> {
  let size = 0;
  const chunks: Buffer[] = [];
  try {
    for await (const c of req) {
      const b = Buffer.isBuffer(c) ? c : Buffer.from(c as Uint8Array);
      size += b.length;
      if (size > max) return { ok: false, status: 413, error: "body too large" };
      chunks.push(b);
    }
  } catch {
    return { ok: false, status: 400, error: "unreadable body" };
  }
  try {
    const v: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
    if (!v || typeof v !== "object" || Array.isArray(v)) return { ok: false, status: 400, error: "invalid json" };
    return { ok: true, value: v as Record<string, unknown> };
  } catch {
    return { ok: false, status: 400, error: "invalid json" };
  }
}

async function handleOpen(req: IncomingMessage, res: ServerResponse) {
  const body = await readJsonBody(req);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const payload = body.value as { url?: string; profile?: string; fullPage?: boolean; selector?: string; waitUntil?: "load" | "networkidle" | "domcontentloaded" };
  if (!payload.url) return json(res, 400, { error: "missing url" });
  const nav = safeNavUrl(payload.url);
  if (!nav) return json(res, 400, { error: "url must be http(s)" });
  let holder: Awaited<ReturnType<typeof getPage>>;
  try {
    holder = await getPage(payload.profile);
  } catch (e) { return json(res, 400, { error: String(e) }); }
  const { page } = holder;
  try {
    await page.goto(nav, { waitUntil: payload.waitUntil || "networkidle", timeout: 30000 });
    if (payload.selector) {
      await page.waitForSelector(payload.selector, { timeout: 10000 });
      const el = await page.$(payload.selector);
      if (!el) return json(res, 404, { error: "selector not found" });
      const buf = await el.screenshot();
      res.writeHead(200, { "Content-Type": "image/png", "Content-Length": buf.length, "Access-Control-Allow-Origin": "*" });
      res.end(buf);
    } else {
      const buf = await page.screenshot({ fullPage: payload.fullPage ?? true });
      const url = await saveShot(buf, mediaBase());
      console.log(`served ${url}`);
      res.writeHead(200, { "Content-Type": "image/png", "Content-Length": buf.length, "Access-Control-Allow-Origin": "*" });
      res.end(buf);
    }
  } catch (e) {
    json(res, 500, { error: String(e) });
  } finally { await holder.done(); }
}

async function handleScrape(req: IncomingMessage, res: ServerResponse) {
  const body = await readJsonBody(req);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const payload = body.value as { url?: string; profile?: string; waitUntil?: "load" | "networkidle" | "domcontentloaded"; extract?: Record<string, string>; screenshot?: boolean };
  if (!payload.url) return json(res, 400, { error: "missing url" });
  const nav = safeNavUrl(payload.url);
  if (!nav) return json(res, 400, { error: "url must be http(s)" });
  let holder: Awaited<ReturnType<typeof getPage>>;
  try {
    holder = await getPage(payload.profile);
  } catch (e) { return json(res, 400, { error: String(e) }); }
  const { page } = holder;
  try {
    await page.goto(nav, { waitUntil: payload.waitUntil || "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(2500);
    const result = await page.evaluate((extract) => {
      const getText = (sel: string) => {
        const el = document.querySelector(sel);
        return el ? (el.textContent || "").trim().slice(0, 2000) : null;
      };
      const extracted: Record<string, string | null> = {};
      if (extract) {
        for (const [k, sel] of Object.entries(extract)) extracted[k] = getText(sel as string);
      }
      return {
        url: location.href,
        title: document.title.slice(0, 500),
        text: document.body.innerText.slice(0, 12000),
        html: document.documentElement.outerHTML.slice(0, 20000),
        extracted,
      };
    }, payload.extract || null);
    let screenshotUrl: string | null = null;
    if (payload.screenshot) {
      const buf = await page.screenshot({ fullPage: true });
      screenshotUrl = await saveShot(buf, mediaBase());
    }
    json(res, 200, { ok: true, ...result, screenshotUrl });
  } catch (e) {
    json(res, 500, { error: String(e) });
  } finally { await holder.done(); }
}

/** Form driving: POST /act {url?, profile?, waitUntil?, screenshot?=true,
 *  actions: [{type:"goto",url}
 *   | {type:"fill",selector,text} | {type:"fillSecret",selector,file}
 *   | {type:"click",selector} | {type:"press",selector?,key}
 *   | {type:"select",selector,value} | {type:"wait",ms} | {type:"waitFor",selector}]}
 *  Secrets are NEVER passed inline: fillSecret reads a 0600 file from
 *  ~/.config/terminus/browser/secrets/<file> (strict name, no traversal).
 *  Action text is never logged. Returns JSON {ok,url,title,screenshotUrl}. */
const SECRETS_DIR = join(homedir(), ".config", "terminus", "browser", "secrets");

async function handleAct(req: IncomingMessage, res: ServerResponse) {
  const body = await readJsonBody(req);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const payload = body.value as { url?: string; profile?: string; waitUntil?: "load" | "networkidle" | "domcontentloaded"; screenshot?: boolean; actions?: Array<Record<string, unknown>> };
  const actions = payload.actions;
  if (!Array.isArray(actions) || !actions.length) return json(res, 400, { error: "missing actions[]" });
  if (actions.length > 50) return json(res, 400, { error: "too many actions (max 50)" });
  let holder: Awaited<ReturnType<typeof getPage>> | undefined;
  let lastEvalResult: unknown = undefined;
  try {
    holder = await getPage(payload.profile ? profileName(payload.profile) : undefined);
  } catch (e) { return json(res, 400, { error: String(e) }); }
  const { page } = holder;
  try {
    if (payload.url) {
      const first = safeNavUrl(payload.url);
      if (!first) throw new Error("url must be http(s)");
      await page.goto(first, { waitUntil: payload.waitUntil || "networkidle", timeout: 30000 });
    }
    for (const [i, a] of actions.entries()) {
      const type = a.type;
      try {
        if (type === "goto") {
          const goUrl = safeNavUrl(a.url);
          if (!goUrl) throw new Error("goto: only http(s) URLs are allowed");
          await page.goto(goUrl, { waitUntil: payload.waitUntil || "networkidle", timeout: 30000 });
        } else if (type === "fill") {
          // .first: login-style pages have one visible input; pass a precise selector otherwise
          await page.locator(String(a.selector || "")).first().fill(String(a.text ?? ""), { timeout: 15000 });
        } else if (type === "fillSecret") {
          const file = String(a.file || "");
          if (!/^[A-Za-z0-9_-]{1,64}$/.test(file)) throw new Error("bad secret file name");
          const text = (await readFile(join(SECRETS_DIR, file), "utf8")).trim();
          await page.locator(String(a.selector || "")).first().fill(text, { timeout: 15000 });
        } else if (type === "click") {
          await page.locator(String(a.selector || "")).first().click({ timeout: 15000 });
        } else if (type === "press") {
          if (a.selector) await page.locator(String(a.selector)).first().press(String(a.key || "Enter"), { timeout: 15000 });
          else await page.keyboard.press(String(a.key || "Enter"));
        } else if (type === "select") {
          await page.locator(String(a.selector || "")).first().selectOption(String(a.value ?? ""), { timeout: 15000 });
        } else if (type === "wait") {
          await page.waitForTimeout(Math.min(Number(a.ms || 1000), 30000));
        } else if (type === "waitFor") {
          await page.waitForSelector(String(a.selector || "body"), { timeout: 20000 });
        } else if (type === "eval") {
          const code = String(a.code || "");
          if (code.length > 200_000) throw new Error("eval code too long");
          lastEvalResult = await page.evaluate((c) => new Function("return (" + c + ")")(), code);
        } else {
          throw new Error(`unknown action: ${type}`);
        }
      } catch (e) {
        const title = await page.title().catch(() => "");
        return json(res, 422, { ok: false, failedAction: i, failedType: String(a.type ?? "?"), url: page.url(), title: title.slice(0, 200), error: String(e).slice(0, 500) });
      }
    }
    const title = await page.title().catch(() => "");
    let screenshotUrl: string | null = null;
    if (payload.screenshot !== false) {
      const buf = await page.screenshot({ fullPage: false });
      screenshotUrl = await saveShot(buf, mediaBase());
    }
    json(res, 200, { ok: true, url: page.url(), title: title.slice(0, 500), screenshotUrl, evalResult: lastEvalResult });
  } catch (e) {
    json(res, 500, { error: String(e) });
  } finally {
    await holder.done();
  }
}

/** Cookie import: POST /cookies {profile, file, url?} loads a browser cookie
 *  export the human dropped in ~/.config/terminus/browser/secrets/<file>.
 *  Accepts Netscape cookies.txt or a JSON array (EditThisCookie et al).
 *  Counts are logged; values never are. Returns {ok,imported,skipped}. */
async function handleCookies(req: IncomingMessage, res: ServerResponse) {
  const body = await readJsonBody(req);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const payload = body.value as { profile?: string; file?: string; url?: string };
  const file = String(payload.file || "");
  if (!/^[A-Za-z0-9_.-]{1,80}$/.test(file) || file.includes("..")) return json(res, 400, { error: "bad file name" });
  let raw: string;
  try {
    raw = (await readFile(join(SECRETS_DIR, file), "utf8")).slice(0, 2_000_000);
  } catch { return json(res, 404, { error: "file not found in secrets dir" }); }
  type Jar = { name: string; value: string; domain?: string; path?: string; secure?: boolean; httpOnly?: boolean; expires?: number; sameSite?: "Lax" | "Strict" | "None" };
  const jars: Jar[] = [];
  const trimmed = raw.trim();
  try {
    if (trimmed.startsWith("[")) {
      const arr = JSON.parse(trimmed) as Array<Record<string, unknown>>;
      for (const c of arr.slice(0, 2000)) {
        if (!c || typeof c.name !== "string" || typeof c.value !== "string") continue;
        const ss = String(c.sameSite || "lax").toLowerCase();
        jars.push({
          name: c.name, value: c.value,
          domain: typeof c.domain === "string" ? c.domain : undefined,
          path: typeof c.path === "string" ? c.path : "/",
          secure: !!c.secure, httpOnly: !!c.httpOnly,
          expires: typeof c.expirationDate === "number" ? c.expirationDate : undefined,
          sameSite: ss === "strict" ? "Strict" : ss === "no_restriction" || ss === "none" ? "None" : "Lax",
        });
      }
    } else {
      for (const line of trimmed.split("\n").slice(0, 2000)) {
        const t = line.trim();
        if (!t || t.startsWith("#") && !t.startsWith("#HttpOnly_")) continue;
        const parts = t.replace(/^#HttpOnly_/, "").split("\t");
        if (parts.length < 7) continue;
        const [domain, , path, secure, expires, name, ...rest] = parts;
        if (!name) continue;
        jars.push({
          name, value: rest.join("\t"),
          domain: domain || undefined, path: path || "/",
          secure: secure === "TRUE",
          expires: Number(expires) ? Number(expires) : undefined,
          sameSite: "Lax",
        });
      }
    }
  } catch { return json(res, 400, { error: "unparseable cookie file (need cookies.txt or JSON array)" }); }
  const withTarget = jars.map((c) => {
    if (c.domain) return c;
    if (payload.url) { const { url, ...rest } = c as Jar & { url?: string }; void url; return { ...rest, url: payload.url }; }
    return null;
  }).filter(Boolean) as Array<Jar & { url?: string }>;
  if (!withTarget.length) return json(res, 400, { error: "no usable cookies (need domains or pass url)" });
  try {
    const key = profileName(payload.profile || "shop");
    const cached = profiles.get(key);
    const ctx = cached ? cached.ctx : await getProfile(key);
    await ctx.addCookies(withTarget as Parameters<BrowserContext["addCookies"]>[0]);
    console.log(`cookies imported into ${key}: ${withTarget.length}/${jars.length}`);
    return json(res, 200, { ok: true, profile: key, imported: withTarget.length, skipped: jars.length - withTarget.length });
  } catch (e) { return json(res, 500, { error: String(e).slice(0, 300) }); }
}

/** Login profiles: POST /profile {name, headed?, channel?, clone?} ensures a
 *  persistent profile (headed window for typing logins; channel "chrome" drives
 *  the real Chrome binary; clone runs on a prepared copy under clones/<name>);
 *  GET /profiles lists them; DELETE /profile?name=x[&wipe=1] closes one
 *  (wipe deletes PROFILES_DIR data only — never a clone source). */
async function handleProfileEnsure(req: IncomingMessage, res: ServerResponse) {
  const body = await readJsonBody(req);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const payload = body.value as { name?: string; headed?: boolean; channel?: string; clone?: string };
  try {
    const channel = payload.channel === "chrome" ? "chrome" : undefined;
    const ctx = await getProfile(profileName(payload.name || ""), !!payload.headed, { channel, clone: payload.clone });
    const pages = ctx.pages().length;
    return json(res, 200, { ok: true, name: payload.name, headed: !!payload.headed, channel: channel || "chromium", clone: payload.clone || null, openPages: pages });
  } catch (e) { return json(res, 400, { error: String(e) }); }
}

async function handleProfileDelete(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url || "", `http://${req.headers.host}`);
  const name = url.searchParams.get("name") || "";
  const wipe = url.searchParams.get("wipe") === "1";
  try { profileName(name); } catch (e) { return json(res, 400, { error: String(e) }); }
  const hits = ctxsFor(name);
  for (const [k, p] of hits) {
    await p.ctx.close().catch(() => {});
    profiles.delete(k);
  }
  if (wipe) await rm(join(PROFILES_DIR, name), { recursive: true, force: true });
  return json(res, 200, { ok: true, name, closed: hits.length > 0, wiped: wipe });
}

async function handleProfileList(res: ServerResponse) {
  const names = await readdir(PROFILES_DIR).catch(() => [] as string[]);
  const loaded = new Map<string, { headed: boolean }>();
  for (const [k, p] of profiles) {
    const bare = k.split("|")[0];
    if (!loaded.has(bare)) loaded.set(bare, { headed: p.headed });
  }
  return json(res, 200, {
    ok: true,
    profiles: names.map((n) => ({ name: n, loaded: loaded.has(n), headed: loaded.get(n)?.headed ?? null })),
  });
}

/** This machine's Tailscale IPv4 address, or null when Tailscale is not running. Never a LAN address. */
function tailnetIp(): string | null {
  try {
    const { execSync } = require("node:child_process") as typeof import("node:child_process");
    const out = execSync("tailscale ip -4 2>/dev/null", { encoding: "utf8" }).trim().split("\n")[0]?.trim();
    if (out && /^100\./.test(out)) return out;
  } catch {}
  const all = Object.values(networkInterfaces()).flat().filter(Boolean) as import("node:os").NetworkInterfaceInfoIPv4[];
  return all.find((i) => i.family === "IPv4" && i.address.startsWith("100."))?.address ?? null;
}
/** The address advertised to clients (media URLs): Tailscale first, then the LAN, then loopback. */
function localIp(): string {
  const tail = tailnetIp();
  if (tail) return tail;
  const all = Object.values(networkInterfaces()).flat().filter(Boolean) as import("node:os").NetworkInterfaceInfoIPv4[];
  const lan = all.find((i) => i.family === "IPv4" && !i.internal);
  return lan?.address || "127.0.0.1";
}
/** Media gallery lives in ./media.ts (same process/port/auth).
 *  server.ts keeps browser automation + routing only. */
import { saveShot, handleMedia, mimeFor, uploadAuthed, debugChatHtml, debugListHtml, handleRepoTree, handleRepoFile } from "./media.js";
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
const mediaBase = () => `http://${localIp()}:${port}`;
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

async function handleBackendFork(req: IncomingMessage, res: ServerResponse, json: Json) {
  const parsed = await readJsonBody(req);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  const body = parsed.value;
  const backend = body.backend;
  const chatId = typeof body.chatId === "string" ? body.chatId : "";
  const remoteSessionId = typeof body.sessionID === "string" ? body.sessionID : "";
  if ((backend !== "codex" && backend !== "claude") || !chatId || !remoteSessionId) {
    return json(res, 400, { error: "bad backend fork request" });
  }
  try {
    const { getAdapter } = await import("./backends/registry.js");
    const adapter = getAdapter(backend);
    if (typeof adapter.fork !== "function") {
      return json(res, 502, { error: "backend does not support fork" });
    }
    const forked = await adapter.fork({ chatId, remoteSessionId });
    return json(res, 200, { ok: true, sessionID: forked.remoteSessionId });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Codex fork failed";
    return json(res, 502, { error: message.slice(0, 240) });
  }
}

async function handleBackendCommand(req: IncomingMessage, res: ServerResponse, json: Json) {
  const parsed = await readJsonBody(req);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  const body = parsed.value;
  const command = typeof body.command === "string" ? body.command.trim().toLowerCase() : "";
  const argument = typeof body.argument === "string" ? body.argument.slice(0, 200) : "";
  if (body.backend !== "codex" || !["/usage", "/resets", "/redeem"].includes(command)) {
    return json(res, 400, { error: "unsupported backend command" });
  }
  try {
    const { getAdapter } = await import("./backends/registry.js");
    const adapter = getAdapter("codex") as typeof getAdapter extends (...args: never[]) => infer A ? A & { executeCommand?: (name: string, argument: string) => Promise<string> } : never;
    if (typeof adapter.executeCommand !== "function") return json(res, 501, { error: "Codex commands unavailable" });
    const text = await adapter.executeCommand(command, argument);
    return json(res, 200, { backend: "codex", command, text });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Codex command failed";
    return json(res, 502, { error: message.slice(0, 240) });
  }
}

const port = Number(process.env.PORT || process.env.HEADLESS_PORT || 9009);
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
  for (const [, p] of profiles) await p.ctx.close().catch(() => {});
  profiles.clear();
  if (browser) await browser.close();
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

// Idle browser profiles: a context nobody has used for PROFILE_IDLE_MS (default 5 min, 0 = off)
// is closed once it has been unused for PROFILE_IDLE_MS (default 5 min). Headed windows get longer
// (PROFILE_HEADED_IDLE_MS, default 15 min) and trusted human input refreshes their idle clock. An
// in-progress browser request pins only the profile it uses. Logins live on disk, so the next use relaunches it.
const PROFILE_IDLE_MS = Number(process.env.PROFILE_IDLE_MS ?? 5 * 60_000);
const PROFILE_HEADED_IDLE_MS = Number(process.env.PROFILE_HEADED_IDLE_MS ?? 15 * 60_000);
if (PROFILE_IDLE_MS > 0) {
  setInterval(() => {
    void (async () => {
      try {
        const { idleProfileKeys } = await import("./lifecycle.js");
        const uses: Array<[string, { headed: boolean; usedAt: number; activeOperations: number }]> = [];
        for (const [k, p] of profiles) {
          if (p.activeOperations > 0) continue;
          uses.push([k, { headed: p.headed, usedAt: p.usedAt, activeOperations: p.activeOperations }]);
        }
        for (const k of idleProfileKeys(uses, Date.now(), PROFILE_IDLE_MS, PROFILE_HEADED_IDLE_MS)) {
          const p = profiles.get(k);
          if (!p) continue;
          const timeout = p.headed ? PROFILE_HEADED_IDLE_MS : PROFILE_IDLE_MS;
          if (p.closing || p.activeOperations > 0 || Date.now() - p.usedAt < timeout) continue;
          p.closing = true;
          await p.ctx.close().catch(() => {});
          if (profiles.get(k) === p) profiles.delete(k);
          console.log("browser profile idle, closed (relaunches on next use)");
        }
      } catch { /* reaping is best effort */ }
    })();
  }, 1 * 60_000).unref();
}
