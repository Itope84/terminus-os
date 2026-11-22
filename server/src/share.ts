/** Partner shared folders: password-scoped, read-only, single-folder access.
 *
 *  One folder (e.g. "shared") gets its own password, set once over the
 *  bearer-gated API. The partner opens /share/<folder>, enters the password
 *  on the public unlock page, and receives an HttpOnly cookie session that
 *  opens GETs under that folder prefix only — no chat, no API, no sibling
 *  folders, no uploads, no deletes. No expiry unless set.
 *
 *  Secrets discipline: only salt+hash persist (0600 file); passwords are
 *  never logged, never returned, never embedded in links. Cookie values are
 *  random session ids, also 0600-persisted so a restart doesn't log
 *  everyone out.
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { repoRoot } from "./paths.js";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { IncomingMessage } from "node:http";

const SHARE_FILE = process.env.SHARE_FILE || join(repoRoot(), ".run", "shares.json");

function runDir(): string {
  return SHARE_FILE.slice(0, SHARE_FILE.lastIndexOf("/"));
}

type ShareRec = { salt: string; hash: string; createdAt: number; expiresAt: number | null };
type ShareDB = { folders: Record<string, ShareRec>; sessions: Record<string, { folder: string; at: number }> };

function loadDB(): ShareDB {
  try {
    const d = JSON.parse(readFileSync(SHARE_FILE, "utf8")) as Partial<ShareDB>;
    if (d && typeof d === "object") {
      return { folders: d.folders ?? {}, sessions: d.sessions ?? {} };
    }
  } catch { /* first use */ }
  return { folders: {}, sessions: {} };
}

function saveDB(db: ShareDB): void {
  mkdirSync(dirname(SHARE_FILE), { recursive: true });
  writeFileSync(SHARE_FILE, JSON.stringify(db), { mode: 0o600 });
}

/** Single path segment, lowercase: safe for cookie names, URLs, and disk. */
export function validFolderName(name: string): boolean {
  return /^[a-z0-9][a-z0-9-]{0,40}$/.test(name);
}

function hashPassword(password: string, saltHex: string): Buffer {
  return scryptSync(password, Buffer.from(saltHex, "hex"), 32, { N: 16384, r: 8, p: 1 });
}

/** Set (or rotate) a folder's password. Creates the folder. Never logs. */
export function setSharePassword(folder: string, password: string, expiresAt: number | null = null): { ok: boolean; error?: string } {
  if (!validFolderName(folder)) return { ok: false, error: "bad folder" };
  if (typeof password !== "string" || password.length < 4 || password.length > 128) {
    return { ok: false, error: "bad password" };
  }
  mkdirSync(join(repoRoot(), "artifacts", folder), { recursive: true });
  const db = loadDB();
  const salt = randomBytes(16).toString("hex");
  db.folders[folder] = {
    salt,
    hash: hashPassword(password, salt).toString("hex"),
    createdAt: Date.now(),
    expiresAt,
  };
  // Rotating the password invalidates existing sessions for that folder.
  for (const [sid, s] of Object.entries(db.sessions)) {
    if (s.folder === folder) delete db.sessions[sid];
  }
  saveDB(db);
  return { ok: true };
}

/** Verify a password; on success mint a cookie session id (null otherwise).
 *  Always hashes (dummy compare for unknown folders) so timing reveals
 *  nothing about which folders exist. */
export function openShareSession(folder: string, password: string): string | null {
  const db = loadDB();
  const rec = db.folders[folder];
  const salt = rec?.salt ?? randomBytes(16).toString("hex");
  const want = rec ? Buffer.from(rec.hash, "hex") : randomBytes(32);
  let got: Buffer;
  try {
    got = hashPassword(typeof password === "string" ? password : "", salt);
  } catch {
    return null;
  }
  if (rec && rec.expiresAt !== null && Date.now() > rec.expiresAt) return null;
  if (got.length !== want.length || !timingSafeEqual(got, want)) return null;
  const sid = randomBytes(32).toString("hex");
  db.sessions[sid] = { folder, at: Date.now() };
  saveDB(db);
  return sid;
}

export function shareCookieName(folder: string): string {
  return `share_${folder}`;
}

/** Folder has password sharing configured (and unexpired)? Public-safe
 *  boolean — reveals nothing secret. */
export function hasShareFolder(folder: string): boolean {
  if (!validFolderName(folder)) return false;
  const rec = loadDB().folders[folder];
  if (!rec) return false;
  return rec.expiresAt === null || Date.now() <= rec.expiresAt;
}
/** Folder unlocked by this request's share cookie, or null. Sessions carry
 *  no rights beyond their folder; path scoping is the caller's job. */
export function shareSessionFolder(req: IncomingMessage): string | null {
  const cookie = req.headers.cookie || "";
  const m = cookie.match(/share_([a-z0-9-]{1,41})=([0-9a-f]{64})/);
  if (!m) return null;
  const [, folder, sid] = m;
  const db = loadDB();
  const s = db.sessions[sid];
  if (!s || s.folder !== folder) return null;
  const rec = db.folders[folder];
  if (!rec) return null;
  if (rec.expiresAt !== null && Date.now() > rec.expiresAt) return null;
  return folder;
}

/** Is this GET path inside the shared folder (bytes only, never the list
 *  root, never anything else)? Pure, unit-tested. */
export function sharePathAllowed(reqPath: string, folder: string): boolean {
  return reqPath.startsWith(`/terminus/${folder}/`);
}

function escHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** Shared page chrome: Terminus cream, one style block, no dark mode. */
const SHARE_CSS = `<style>
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#F5F1E6;color:#262220;margin:0;padding:0 20px;-webkit-text-size-adjust:100%}
.wrap{max-width:440px;margin:64px auto}
.wrap.wide{max-width:640px;margin:36px auto}
.brand{font-size:15px;letter-spacing:2px;opacity:.55;margin-bottom:6px}
h1{font-size:24px;margin:0 0 4px}
.sub{opacity:.65;margin:0 0 20px;font-size:14px}
.field{position:relative}
input[type=password],input[type=text]{width:100%;padding:12px 44px 12px 12px;font-size:17px;border:1px solid #D8D1C0;border-radius:10px;background:#fff;color:#262220;box-sizing:border-box;outline:none;transition:border-color .15s,box-shadow .15s}
input:focus{border-color:#262220;box-shadow:0 0 0 3px rgba(38,34,32,.12)}
.toggle{position:absolute;right:6px;top:50%;transform:translateY(-50%);border:0;background:none;font-size:13px;color:#6B645A;padding:8px;cursor:pointer}
button.go{display:block;width:100%;margin-top:14px;padding:13px;font-size:17px;font-weight:600;background:#262220;color:#F5F1E6;border:0;border-radius:10px;cursor:pointer;transition:opacity .15s}
button.go:active{opacity:.75}
button.go:disabled{opacity:.5}
.err{color:#A15C48;min-height:22px;margin:10px 0 0;font-size:14px}
ul.files{list-style:none;padding:0;margin:4px 0 0}
ul.files li{margin:0;border-bottom:1px solid #E4DCCB}
ul.files li:last-child{border-bottom:0}
ul.files a{display:flex;justify-content:space-between;align-items:baseline;gap:12px;padding:13px 2px;color:#1B5FAB;text-decoration:none;font-size:16px}
ul.files a:active{background:#EDE7D6}
ul.files .meta{opacity:.6;font-size:13px;white-space:nowrap;color:#262220}
.empty{opacity:.6;padding:20px 2px}
</style>`;

/** Public unlock page: no data, posts the password as JSON, then
 *  continues to a validated ?then= target or the listing. */
export function shareUnlockHtml(folder: string): string {
  const f = escHtml(folder);
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>shared folder · ${f}</title>${SHARE_CSS}</head>` +
    `<body><div class="wrap"><div class="brand">◆ TERMINUS</div>` +
    `<h1>${f}</h1><p class="sub">Shared folder · enter the password to view and download.</p>` +
    `<div class="field"><input id="pw" type="password" autocomplete="current-password" placeholder="Password" />` +
    `<button class="toggle" id="tg" type="button">show</button></div>` +
    `<button class="go" id="go">open</button>` +
    `<p class="err" id="err"></p></div>` +
    `<script>(function(){var p=document.getElementById("pw"),e=document.getElementById("err"),` +
    `g=document.getElementById("go"),tg=document.getElementById("tg");` +
    `tg.onclick=function(){var show=p.type==="password";p.type=show?"text":"password";tg.textContent=show?"hide":"show";};` +
    `var then=new URLSearchParams(location.search).get("then")||"";` +
    `function go(){g.disabled=true;e.textContent="";fetch("/share/${encodeURIComponent(folder)}/unlock",{method:"POST",headers:{"Content-Type":"application/json"},` +
    `body:JSON.stringify({password:p.value,then:then})}).then(function(r){return r.json().then(function(j){return{ok:r.ok,j:j};});}).then(function(x){if(x.ok){location.href=x.j.then||location.pathname;}else{g.disabled=false;e.textContent="wrong password — try again";p.select();}});}` +
    `g.onclick=go;p.onkeydown=function(ev){if(ev.key==="Enter")go();};p.focus();})();</script>` +
    `</body></html>`;
}

/** Scoped listing: this folder only, newest first, one nested level shown
 *  as sections (e.g. shared/data). File links are relative (the share
 *  cookie authenticates them). */
export async function shareFolderHtml(folder: string): Promise<string> {
  const { readdir, stat } = await import("node:fs/promises");
  const dir = join(repoRoot(), "artifacts", folder);
  type Row = { name: string; href: string; size: number; mtime: number };
  const fileRow = async (rel: string, label: string): Promise<Row | null> => {
    try {
      const st = await stat(join(dir, rel));
      if (st.isDirectory()) return null;
      return { name: label, href: `/terminus/${encodeURIComponent(folder)}/${rel.split("/").map(encodeURIComponent).join("/")}`, size: st.size, mtime: st.mtimeMs };
    } catch { return null; }
  };
  let rows: Row[] = [];
  let sections: { title: string; rows: Row[] }[] = [];
  try {
    const names = await readdir(dir);
    for (const n of names) {
      try {
        const st = await stat(join(dir, n));
        if (st.isDirectory()) {
          const sub: Row[] = [];
          for (const g of await readdir(join(dir, n))) {
            const r = await fileRow(`${n}/${g}`, g);
            if (r) sub.push(r);
          }
          sub.sort((a, b) => b.mtime - a.mtime);
          if (sub.length) sections.push({ title: n, rows: sub });
        } else {
          const r = await fileRow(n, n);
          if (r) rows.push(r);
        }
      } catch { /* ignore */ }
    }
  } catch { /* empty */ }
  rows.sort((a, b) => b.mtime - a.mtime);
  const fmtSize = (b: number) => (b < 1048576 ? `${(b / 1024).toFixed(0)} KB` : `${(b / 1048576).toFixed(1)} MB`);
  const fmtTime = (ms: number) => {
    const d = new Date(ms);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ` +
      `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  };
  const lis = rows
    .map((r) => `<li><a href="${r.href}"><span>${escHtml(r.name)}</span><span class="meta">${fmtSize(r.size)} · ${fmtTime(r.mtime)}</span></a></li>`)
    .join("");
  const secs = sections
    .map((s) => `<h1 style="font-size:18px;margin:22px 0 0">${escHtml(s.title)}</h1>` +
      `<ul class="files">${s.rows.map((r) => `<li><a href="${r.href}"><span>${escHtml(r.name)}</span><span class="meta">${fmtSize(r.size)} · ${fmtTime(r.mtime)}</span></a></li>`).join("")}</ul>`)
    .join("");
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>shared · ${escHtml(folder)}</title>${SHARE_CSS}</head>` +
    `<body><div class="wrap wide"><div class="brand">◆ TERMINUS</div>` +
    `<h1>${escHtml(folder)}</h1><p class="sub">Shared files · newest first</p>` +
    `<ul class="files">${lis || '<li class="empty">nothing here yet</li>'}</ul>${secs}</div></body></html>`;
}
