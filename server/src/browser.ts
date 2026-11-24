import type { IncomingMessage, ServerResponse } from "node:http";
import { chromium, Browser, BrowserContext, Page } from "playwright";
import { readFile, mkdir, readdir, rm } from "node:fs/promises";
import { join } from "node:path";
import { homedir } from "node:os";
import { safeNavUrl } from "./nav-guard.js";
import { json, readJsonBody } from "./http.js";
import { mediaBase } from "./net.js";
import { saveShot } from "./media.js";

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
export async function getPage(profile?: string) {
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

export async function handleOpen(req: IncomingMessage, res: ServerResponse) {
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

export async function handleScrape(req: IncomingMessage, res: ServerResponse) {
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

export async function handleAct(req: IncomingMessage, res: ServerResponse) {
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
export async function handleCookies(req: IncomingMessage, res: ServerResponse) {
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
export async function handleProfileEnsure(req: IncomingMessage, res: ServerResponse) {
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

export async function handleProfileDelete(req: IncomingMessage, res: ServerResponse) {
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

export async function handleProfileList(res: ServerResponse) {
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

// Idle browser profiles: a context nobody has used for PROFILE_IDLE_MS (default 5 min, 0 = off)
// is closed once it has been unused for PROFILE_IDLE_MS (default 5 min). Headed windows get longer
// (PROFILE_HEADED_IDLE_MS, default 15 min) and trusted human input refreshes their idle clock. An
// in-progress browser request pins only the profile it uses. Logins live on disk, so the next use relaunches it.
export function startProfileReaper(): void {
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
}

/** Close every browser context and the shared browser (server shutdown). */
export async function closeBrowsers(): Promise<void> {
  for (const [, p] of profiles) await p.ctx.close().catch(() => {});
  profiles.clear();
  if (browser) await browser.close();
}
