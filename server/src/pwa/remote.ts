/** The agents' Chrome on the phone: a live picture of one tab (CDP screencast) and taps, typing,
 *  scrolling and navigation sent back. Used for sign-ins the agents can't do themselves.
 *  Talks to the Chrome DevTools endpoint on loopback (TERMINUS_CHROME_DEVTOOLS, default
 *  http://127.0.0.1:9222; tools/chrome-agent). Nothing here is reachable without the server's auth.
 *
 *  GET  /api/remote/tabs                 → {tabs: [{id, title, url}]}
 *  POST /api/remote/open   {url}         → {tab}
 *  POST /api/remote/close  {target}
 *  GET  /api/remote/stream?target=&w=&h= SSE: frame {data, w, h}, nav {url, title}, gone
 *  POST /api/remote/input  {target, kind: tap|text|key|scroll|go|back|forward|reload, x?, y?, text?, key?, dy?, url?}
 *        x, y are fractions (0–1) of the picture; the phone doesn't need to know the page's size.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { json, readJsonBody } from "../http.js";
import type { Route } from "../routes.js";

const devtools = (): string => (process.env.TERMINUS_CHROME_DEVTOOLS || "http://127.0.0.1:9222").replace(/\/+$/, "");

interface TargetInfo { id: string; type: string; title: string; url: string; webSocketDebuggerUrl?: string }

async function targets(): Promise<TargetInfo[]> {
  const r = await fetch(`${devtools()}/json/list`, { signal: AbortSignal.timeout(4000) });
  if (!r.ok) throw new Error(`devtools ${r.status}`);
  return ((await r.json()) as TargetInfo[]).filter((t) => t.type === "page" && !t.url.startsWith("chrome-extension://") && !t.url.startsWith("chrome://omnibox"));
}

/** One CDP connection to a page, shared by its stream and input; closed when idle. */
class PageLink {
  private ws: WebSocket;
  private seq = 0;
  private waiting = new Map<number, { ok: (v: unknown) => void; fail: (e: Error) => void }>();
  private listeners = new Set<(method: string, params: Record<string, unknown>) => void>();
  readonly ready: Promise<void>;
  closed = false;
  /** Size of the last frame's page in CSS pixels (input maps fractions onto it). */
  view = { w: 390, h: 844 };
  streams = 0;
  idle: ReturnType<typeof setTimeout> | null = null;

  constructor(readonly target: string, url: string) {
    this.ws = new WebSocket(url);
    this.ready = new Promise((ok, fail) => {
      this.ws.addEventListener("open", () => ok(), { once: true });
      this.ws.addEventListener("error", () => fail(new Error("devtools connection failed")), { once: true });
    });
    this.ws.addEventListener("message", (ev) => {
      let m: { id?: number; result?: unknown; error?: { message: string }; method?: string; params?: Record<string, unknown> };
      try { m = JSON.parse(String(ev.data)); } catch { return; }
      if (m.id != null) {
        const w = this.waiting.get(m.id);
        this.waiting.delete(m.id);
        if (m.error) w?.fail(new Error(m.error.message)); else w?.ok(m.result);
      } else if (m.method) {
        for (const fn of this.listeners) fn(m.method, m.params ?? {});
      }
    });
    this.ws.addEventListener("close", () => {
      this.closed = true;
      for (const w of this.waiting.values()) w.fail(new Error("closed"));
      this.waiting.clear();
      for (const fn of this.listeners) fn("Link.closed", {});
      links.delete(this.target);
    });
  }

  async send<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    await this.ready;
    const id = ++this.seq;
    return new Promise<T>((ok, fail) => {
      this.waiting.set(id, { ok: ok as (v: unknown) => void, fail });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => { if (this.waiting.delete(id)) fail(new Error(`${method} timed out`)); }, 10_000).unref?.();
    });
  }

  on(fn: (method: string, params: Record<string, unknown>) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  touch(): void {
    if (this.idle) clearTimeout(this.idle);
    this.idle = setTimeout(() => { if (this.streams === 0) this.close(); }, 120_000);
    this.idle.unref?.();
  }

  close(): void {
    try { this.ws.close(); } catch { /* gone */ }
    links.delete(this.target);
  }
}

const links = new Map<string, PageLink>();

async function link(target: string): Promise<PageLink> {
  const have = links.get(target);
  if (have && !have.closed) { have.touch(); return have; }
  const t = (await targets()).find((x) => x.id === target);
  if (!t?.webSocketDebuggerUrl) throw Object.assign(new Error("That tab is gone."), { status: 404 });
  const l = new PageLink(target, t.webSocketDebuggerUrl);
  links.set(target, l);
  await l.ready;
  l.touch();
  return l;
}

/** Pure: a tap at fractions of the picture → CSS pixels on the page. */
export function tapPoint(x: unknown, y: unknown, view: { w: number; h: number }): { x: number; y: number } | null {
  const fx = Number(x), fy = Number(y);
  if (!Number.isFinite(fx) || !Number.isFinite(fy) || fx < 0 || fx > 1 || fy < 0 || fy > 1) return null;
  return { x: Math.round(fx * view.w), y: Math.round(fy * view.h) };
}

const KEYS: Record<string, { code: string; vk: number; text?: string }> = {
  Enter: { code: "Enter", vk: 13, text: "\r" },
  Backspace: { code: "Backspace", vk: 8 },
  Tab: { code: "Tab", vk: 9 },
  Escape: { code: "Escape", vk: 27 },
  ArrowDown: { code: "ArrowDown", vk: 40 },
  ArrowUp: { code: "ArrowUp", vk: 38 },
};

async function handleStream(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const q = new URL(req.url || "", "http://x").searchParams;
  const target = q.get("target") || "";
  // The phone's own screen size, so the page lays out for a phone while you're looking at it.
  const w = Math.min(1000, Math.max(320, Number(q.get("w")) || 390));
  const h = Math.min(2000, Math.max(480, Number(q.get("h")) || 760));
  let l: PageLink;
  try { l = await link(target); } catch (e) { return json(res, (e as { status?: number }).status ?? 502, { error: (e as Error).message }); }
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive", "X-Accel-Buffering": "no" });
  const send = (event: string, data: unknown): void => { try { res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`); } catch { /* closed */ } };
  l.streams++;
  const off = l.on((method, p) => {
    if (method === "Page.screencastFrame") {
      const meta = p.metadata as { deviceWidth?: number; deviceHeight?: number } | undefined;
      if (meta?.deviceWidth && meta.deviceHeight) l.view = { w: meta.deviceWidth, h: meta.deviceHeight };
      send("frame", { data: p.data, w: l.view.w, h: l.view.h });
      void l.send("Page.screencastFrameAck", { sessionId: p.sessionId }).catch(() => {});
    } else if (method === "Page.frameNavigated" && !(p.frame as { parentId?: string })?.parentId) {
      const f = p.frame as { url: string };
      send("nav", { url: f.url });
    } else if (method === "Link.closed") {
      send("gone", {});
      res.end();
    }
  });
  try {
    await l.send("Page.enable");
    await l.send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 2, mobile: true });
    await l.send("Page.bringToFront").catch(() => {});
    await l.send("Page.startScreencast", { format: "jpeg", quality: 65, maxWidth: w * 2, maxHeight: h * 2, everyNthFrame: 1 });
    const t = (await targets()).find((x) => x.id === target);
    if (t) send("nav", { url: t.url, title: t.title });
  } catch (e) {
    send("error", { error: (e as Error).message });
  }
  const beat = setInterval(() => { try { res.write(`: ping\n\n`); } catch { /* closed */ } }, 20_000);
  beat.unref?.();
  req.on("close", () => {
    clearInterval(beat);
    off();
    l.streams = Math.max(0, l.streams - 1);
    if (l.streams === 0 && !l.closed) {
      void l.send("Page.stopScreencast").catch(() => {});
      void l.send("Emulation.clearDeviceMetricsOverride").catch(() => {});
    }
    l.touch();
  });
}

async function handleInput(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const parsed = await readJsonBody(req, 20_000);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  const b = parsed.value as Record<string, unknown>;
  let l: PageLink;
  try { l = await link(String(b.target ?? "")); } catch (e) { return json(res, (e as { status?: number }).status ?? 502, { error: (e as Error).message }); }
  try {
    switch (b.kind) {
      case "tap": {
        const pt = tapPoint(b.x, b.y, l.view);
        if (!pt) return json(res, 400, { error: "bad point" });
        await l.send("Input.dispatchMouseEvent", { type: "mouseMoved", ...pt });
        await l.send("Input.dispatchMouseEvent", { type: "mousePressed", ...pt, button: "left", clickCount: 1 });
        await l.send("Input.dispatchMouseEvent", { type: "mouseReleased", ...pt, button: "left", clickCount: 1 });
        break;
      }
      case "text":
        if (typeof b.text !== "string" || !b.text) return json(res, 400, { error: "no text" });
        await l.send("Input.insertText", { text: b.text.slice(0, 2000) });
        break;
      case "key": {
        const k = KEYS[String(b.key)];
        if (!k) return json(res, 400, { error: "bad key" });
        const base = { key: String(b.key), code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk };
        await l.send("Input.dispatchKeyEvent", { type: "keyDown", ...base, ...(k.text ? { text: k.text } : {}) });
        await l.send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
        break;
      }
      case "scroll": {
        const pt = tapPoint(b.x ?? 0.5, b.y ?? 0.5, l.view) ?? { x: 10, y: 10 };
        await l.send("Input.dispatchMouseEvent", { type: "mouseWheel", ...pt, deltaX: 0, deltaY: Math.max(-3000, Math.min(3000, Number(b.dy) || 0)) });
        break;
      }
      case "go": {
        const url = String(b.url ?? "").trim();
        if (!/^https?:\/\//i.test(url)) return json(res, 400, { error: "http(s) only" });
        await l.send("Page.navigate", { url });
        break;
      }
      case "back":
      case "forward": {
        const h = await l.send<{ currentIndex: number; entries: { id: number }[] }>("Page.getNavigationHistory");
        const e = h.entries[h.currentIndex + (b.kind === "back" ? -1 : 1)];
        if (e) await l.send("Page.navigateToHistoryEntry", { entryId: e.id });
        break;
      }
      case "reload":
        await l.send("Page.reload");
        break;
      default:
        return json(res, 400, { error: "bad kind" });
    }
    return json(res, 200, { ok: true });
  } catch (e) {
    return json(res, 502, { error: (e as Error).message });
  }
}

/** Opens a tab in the agents' Chrome (used by the sign-in requests too). */
export async function openTab(url: string): Promise<{ id: string; title: string; url: string }> {
  const r = await fetch(`${devtools()}/json/new?${encodeURI(url)}`, { method: "PUT", signal: AbortSignal.timeout(5000) });
  if (!r.ok) throw new Error(`devtools ${r.status}`);
  const t = (await r.json()) as TargetInfo;
  return { id: t.id, title: t.title, url: t.url };
}

export const remoteRoutes: readonly Route[] = [
  { method: "GET", path: "/api/remote/tabs", handle: async ({ res }) => {
    try {
      return json(res, 200, { tabs: (await targets()).map((t) => ({ id: t.id, title: t.title, url: t.url })) });
    } catch {
      return json(res, 503, { error: "The agents’ Chrome isn’t running on the Mac." });
    }
  } },
  { method: "POST", path: "/api/remote/open", handle: async ({ req, res }) => {
    const parsed = await readJsonBody(req, 20_000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    const url = String((parsed.value as Record<string, unknown>).url ?? "about:blank");
    if (url !== "about:blank" && !/^https?:\/\//i.test(url)) return json(res, 400, { error: "http(s) only" });
    try { return json(res, 200, { tab: await openTab(url) }); } catch (e) { return json(res, 503, { error: (e as Error).message }); }
  } },
  { method: "POST", path: "/api/remote/close", handle: async ({ req, res }) => {
    const parsed = await readJsonBody(req, 20_000);
    if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
    const id = String((parsed.value as Record<string, unknown>).target ?? "");
    if (!/^[A-F0-9]{16,64}$/i.test(id)) return json(res, 400, { error: "bad target" });
    const r = await fetch(`${devtools()}/json/close/${id}`, { signal: AbortSignal.timeout(4000) }).catch(() => null);
    return json(res, r?.ok ? 200 : 404, { ok: !!r?.ok });
  } },
  { method: "GET", path: "/api/remote/stream", handle: ({ req, res }) => handleStream(req, res) },
  { method: "POST", path: "/api/remote/input", handle: ({ req, res }) => handleInput(req, res) },
];
