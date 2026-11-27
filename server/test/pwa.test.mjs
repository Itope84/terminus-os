/** Web app server side: chat index, push, static files, HTTPS config, Claude browser tiers.
 *  Run: npm test (builds first). Everything writes under a temp dir, never .run/ or ~/.config. */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const tmp = mkdtempSync(join(tmpdir(), "terminus-pwa-"));
process.env.TERMINUS_DIR = tmp;
process.env.HEADLESS_TOKEN = "test-token-abc";

const { ChatStore, previewOf } = await import("../dist/pwa/store.js");
const { Push } = await import("../dist/pwa/push.js");
const { appFileFor, handleApp } = await import("../dist/pwa/static.js");
const { tlsConfig } = await import("../dist/tls.js");
const { cleanTranscript } = await import("../dist/pwa/transcribe.js");
const { claudeBrowserArgs, claudeBrowserTools, claudeArgv } = await import("../dist/backends/claude.js");

describe("chat index", () => {
  it("creates, updates and lists newest first", () => {
    let t = 1000;
    const s = new ChatStore(join(tmp, "a", "chats.json"), () => t);
    s.upsert({ id: "s-1", title: "First", backend: "claude" });
    t = 2000;
    s.upsert({ id: "s-2", title: "Second", backend: "opencode" });
    assert.deepEqual(s.list().map((c) => c.id), ["s-2", "s-1"]);
    t = 3000;
    s.upsert({ id: "s-1", touch: true, pinned: true, bogus: "x" });
    assert.equal(s.list()[0].id, "s-1");
    assert.equal(s.get("s-1").pinned, true);
    assert.equal("bogus" in s.get("s-1"), false);
  });

  it("refuses bad ids and clears a field with null", () => {
    const s = new ChatStore(join(tmp, "b", "chats.json"));
    assert.equal(s.upsert({ id: "../etc" }), null);
    s.upsert({ id: "s-1", model: "sonnet" });
    s.upsert({ id: "s-1", model: null });
    assert.equal(s.get("s-1").model, undefined);
  });

  it("counts unread on landing and clears it on read", () => {
    const s = new ChatStore(join(tmp, "c", "chats.json"));
    s.upsert({ id: "s-1", title: "Chat" });
    s.landed("s-1", { preview: "**Hello**   there" });
    s.landed("s-1", { preview: "again", needsYou: true });
    assert.equal(s.get("s-1").unread, 2);
    assert.equal(s.get("s-1").needsYou, true);
    assert.equal(s.get("s-1").preview, "again");
    s.markRead("s-1");
    assert.equal(s.get("s-1").unread, 0);
    assert.equal(s.get("s-1").needsYou, false);
  });

  it("makes inbox threads but ignores chats it never made", () => {
    const s = new ChatStore(join(tmp, "d", "chats.json"));
    assert.equal(s.landed("s-android", { preview: "x" }), null);
    const c = s.landed("schedule:brief", { preview: "Morning", title: "brief", inbox: true });
    assert.equal(c.kind, "inbox");
    assert.equal(c.title, "brief");
  });

  it("brings an archived chat back when news lands", () => {
    const s = new ChatStore(join(tmp, "e", "chats.json"));
    s.upsert({ id: "s-1", archivedAt: 5 });
    s.landed("s-1", { preview: "news" });
    assert.equal(s.get("s-1").archivedAt, undefined);
  });

  it("survives a restart", () => {
    const file = join(tmp, "f", "chats.json");
    const s = new ChatStore(file);
    s.upsert({ id: "s-9", title: "Kept" });
    s.flush();
    assert.equal(new ChatStore(file).get("s-9").title, "Kept");
  });

  it("previews without markup", () => {
    assert.equal(previewOf("# Title\n\n**bold** and `code`"), "Title bold and code");
  });
});

describe("push", () => {
  const sub = { endpoint: "https://web.push.apple.com/abc", keys: { p256dh: "BNc", auth: "xyz" } };

  it("keeps subscriptions and sends to each", async () => {
    const sent = [];
    const p = new Push(join(tmp, "p1", "push.json"), join(tmp, "p1", "vapid.json"), async (s, body) => { sent.push([s.endpoint, JSON.parse(body)]); });
    assert.equal(p.subscribe(sub), true);
    assert.equal(p.subscribe({ endpoint: "http://insecure", keys: sub.keys }), false);
    const r = await p.notify({ title: "T", body: "B", url: "/app/chat/s-1" });
    assert.deepEqual(r, { devices: 1, sent: 1, errors: [] });
    assert.equal(sent[0][1].url, "/app/chat/s-1");
    // A second instance reads the same subscriptions and the same keys.
    const again = new Push(join(tmp, "p1", "push.json"), join(tmp, "p1", "vapid.json"), async () => {});
    assert.equal(again.count(), 1);
    assert.equal(again.publicKey, p.publicKey);
  });

  it("drops a subscription the push service says is gone", async () => {
    const p = new Push(join(tmp, "p2", "push.json"), join(tmp, "p2", "vapid.json"), async () => { throw Object.assign(new Error("gone"), { statusCode: 410 }); });
    p.subscribe(sub);
    const r = await p.notify({ title: "T", body: "B" });
    assert.equal(r.sent, 0);
    assert.equal(p.count(), 0);
    assert.match(r.errors[0], /expired/);
  });

  it("keeps a subscription on other errors", async () => {
    const p = new Push(join(tmp, "p3", "push.json"), join(tmp, "p3", "vapid.json"), async () => { throw Object.assign(new Error("boom"), { statusCode: 500 }); });
    p.subscribe(sub);
    await p.notify({ title: "T", body: "B" });
    assert.equal(p.count(), 1);
  });
});

describe("web app files", () => {
  const dist = join(tmp, "dist");
  mkdirSync(join(dist, "_expo", "static"), { recursive: true });
  writeFileSync(join(dist, "index.html"), "<html>app</html>");
  writeFileSync(join(dist, "_expo", "static", "a.js"), "1");

  it("never leaves dist", () => {
    assert.equal(appFileFor(dist, "/app/../../etc/passwd"), null);
    assert.equal(appFileFor(dist, "/app/%2e%2e/%2e%2e/etc/passwd"), null);
    assert.equal(appFileFor(dist, "/app/_expo/static/a.js"), join(dist, "_expo", "static", "a.js"));
  });

  const run = async (path) => {
    process.env.TERMINUS_WEB_DIST = dist;
    const out = { status: 0, headers: {}, body: "" };
    const res = {
      writeHead(s, h) { out.status = s; out.headers = h ?? {}; return this; },
      end(b) { if (b) out.body += b; },
      write(b) { out.body += b; },
      on() {}, once() {}, emit() {}, removeListener() {},
    };
    // createReadStream(...).pipe(res) needs a writable; collect through a tiny shim
    const { Writable } = await import("node:stream");
    const w = new Writable({ write(chunk, _e, cb) { out.body += chunk; cb(); } });
    w.writeHead = res.writeHead.bind(res);
    const done = new Promise((r) => w.on("finish", r));
    const handled = await handleApp({ method: "GET" }, w, path);
    if (out.status === 200) await done;
    return { handled, ...out };
  };

  it("serves deep links as the app page and bundles as immutable", async () => {
    const page = await run("/app/chat/s-1");
    assert.equal(page.status, 200);
    assert.equal(page.body, "<html>app</html>");
    assert.equal(page.headers["Cache-Control"], "no-cache");
    const js = await run("/app/_expo/static/a.js");
    assert.match(js.headers["Cache-Control"], /immutable/);
  });

  it("404s a missing file with an extension and ignores other paths", async () => {
    assert.equal((await run("/app/missing.js")).status, 404);
    assert.equal((await run("/terminus")).handled, false);
  });
});

describe("https config", () => {
  it("is off without both files and defaults the port", () => {
    assert.equal(tlsConfig({}), null);
    assert.equal(tlsConfig({ TERMINUS_TLS_CERT: "/c" }), null);
    assert.deepEqual(tlsConfig({ TERMINUS_TLS_CERT: "/c", TERMINUS_TLS_KEY: "/k" }), { cert: "/c", key: "/k", port: 9443, host: "0.0.0.0" });
  });
});

describe("claude browser tiers", () => {
  it("adds nothing by default", () => {
    assert.deepEqual(claudeBrowserArgs({}), []);
    assert.deepEqual(claudeBrowserTools({}), []);
  });

  it("adds the MCP config and Claude in Chrome when set", () => {
    const cfg = join(tmp, "mcp.json");
    writeFileSync(cfg, JSON.stringify({ mcpServers: { "chrome-devtools": {}, "bad name!": {} } }));
    const env = { TERMINUS_CLAUDE_MCP_CONFIG: cfg, TERMINUS_CLAUDE_CHROME: "1" };
    assert.deepEqual(claudeBrowserArgs(env), ["--mcp-config", cfg, "--chrome"]);
    assert.deepEqual(claudeBrowserTools(env, (p) => readFileSync(p, "utf8")), ["mcp__chrome-devtools__*", "mcp__claude-in-chrome__*"]);
  });

  it("puts the tools in the allowed list of a real argv", () => {
    process.env.TERMINUS_CLAUDE_CHROME = "1";
    try {
      const argv = claudeArgv({ prompt: "hi", yolo: false });
      const allowed = argv[argv.indexOf("--allowedTools") + 1];
      assert.match(allowed, /mcp__claude-in-chrome__\*/);
      assert.ok(argv.includes("--chrome"));
    } finally {
      delete process.env.TERMINUS_CLAUDE_CHROME;
    }
  });
});

describe("transcripts", () => {
  it("joins segments and drops blank markers", () => {
    assert.equal(cleanTranscript(" Hello there.\n [BLANK_AUDIO]\n  How are you?\n"), "Hello there. How are you?");
    assert.equal(cleanTranscript("[00:00:00.000 --> 00:00:02.000]  Hi\n"), "Hi");
    assert.equal(cleanTranscript("[BLANK_AUDIO]"), "");
  });
});
