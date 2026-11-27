/** Media gallery: persistent files under terminus/artifacts/ (day-folders inside).
 *  Same process as the browser automation server — one port, one auth —
 *  but all gallery logic lives here, not in server.ts.
 *
 *  Gallery root is the repo artifacts dir (<repo>/artifacts, see TERMINUS_DIR);
 *  the old $TMPDIR/terminus landing dir is still created for upload
 *  compatibility but the gallery never reads it.
 *
 *  Routes handled (call via handleMedia, returns true when handled):
 *  - GET /terminus            → JSON file list (also prunes > retention)
 *  - GET /terminus/<day>/<f>  → file bytes (Range/206 streaming, ?download=1)
 *  - DELETE /terminus/<day>/<f>
 *  Viewer shell (public/index.html) is served by server.ts directly.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { atomicWriteFile } from "./util.js";
import { createReadStream, createWriteStream, existsSync, statSync, readFileSync, readdirSync } from "node:fs";
import { mkdir, writeFile, readdir, rename, unlink } from "node:fs/promises";
import { once } from "node:events";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { homedir, freemem, totalmem, loadavg, uptime as hostUptime, cpus } from "node:os";
import { authorized, bearerMatches, uploadKeyMatches } from "./auth.js";
import { handleSysmon, startSysmon } from "./sysmon.js";
import { repoRoot } from "./paths.js";
import { AGENTBOOK_DIR } from "./agentbook.js";
import { markdownHtml, plainMd } from "./md-lite.js";

/** Notes console: read-only notebook weeklies as clean HTML.
 *  Parses ## Day YYYY-MM-DD HH:MM blocks into day-grouped entries.
 *  Auth-gated like everything else. */
/** Shared dark-mode overrides (follows system via prefers-color-scheme —
 *  no app changes needed, works in browsers too). */
const DARK_CSS =
  `@media (prefers-color-scheme: dark){` +
  `body{background:#1A1712!important;color:#ECE7DA!important}` +
  `h1,h2,.t{color:#ECE7DA!important}` +
  `.sub,.m,.htag,.day span,.sys{color:#A89E8C!important}` +
  `.week,.job,.entry,.fire,.sys,.next,.tile{background:#262220!important;border-color:#3A352E!important}` +
`.htag{background:#2E332A!important;border-color:#4A5245!important}` +
  `.tabs span,.tabs a,.seg span{background:#35302A!important;color:#ECE7DA!important;border-color:#3A352E!important}` +
  `.tabs .on,.seg span.on{background:#ECE7DA!important;color:#1A1712!important;border-color:#ECE7DA!important}` +
  `#search{background:#35302A!important;color:#ECE7DA!important;border-color:#3A352E!important}` +
  `.etime{color:#8A8478!important}` +
  `.dot.off{background:#3A352E!important}` +
  `.bar{background:#3A352E!important}.proc{border-color:#3A352E!important}` +
  `.when{background:#35302A!important;color:#C9C0AC!important}.when.soon{background:#6FCF97!important;color:#14201A!important}` +
  `.job.bad{background:#2E1D1A!important;border-color:#E08073!important}.job.bad .ic{color:#E08073!important}` +
  `.profile-button{background:#35302A!important;color:#ECE7DA!important;border-color:#3A352E!important}` +
  `#profile-dialog,.profile-head{background:#1A1712!important;color:#ECE7DA!important;border-color:#3A352E!important}` +
  `.profile-status{color:#A89E8C!important}` +
  `} `;

export function profileMarkdownHtml(markdown: string): string {
  return markdownHtml(markdown);
}

export function notesHtml(all = false): string {
  const esc = (s: unknown) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const nbDir = join(repoRoot(), "notebook");
  type Entry = { date: string; time: string; title: string; snippet: string; full: string; expandable: boolean; hint: string; nexts: string[]; dues: Due[]; facts: string };
  type Due = { date: string; label: string };
  type Week = { file: string; label: string; days: { label: string; entries: Entry[] }[]; count: number };
  const weeks: Week[] = [];
  let hasOlder = false;
  let loadErr = "";
  try {
    const files = readdirSync(nbDir).filter((f) => /^\d{4}-W\d{2}\.md$/.test(f)).sort().reverse();
    hasOlder = !all && files.length > 12;
    for (const file of all ? files : files.slice(0, 12)) {
      const raw = readFileSync(join(nbDir, file), "utf8");
      // Strip HTML comments first (read.py parity): commented-out drafts
      // must never render as ghost entries in another week's card.
      const text = raw.replace(/<!--[\s\S]*?-->/g, "");
      const blocks = text.split(/^## /m).slice(1);
      const byDay: Record<string, Entry[]> = {};
      for (const b of blocks) {
        const lines = b.split("\n");
        const head = lines[0].trim();
        const m = head.match(/^(\w{3}) (\d{4}-\d{2}-\d{2})(?: (\d{2}:\d{2}))?\s*(.*)$/);
        if (!m) continue;
        const [, , date, time, rest] = m;
        const body = lines.slice(1).join("\n").trim().split("\n").filter((l) => l.trim());
        const clean = (s: string) => s
          .replace(/Session:\s*ses_[A-Za-z0-9_]+\s*[·•-]?\s*/g, "")
          .replace(/\[hint\]/g, "")
          .replace(/\s+/g, " ").trim();
        const lines2 = body.map(clean).filter(Boolean).map((l) => l.replace(/^>+\s*/, "").replace(/^•\s*/, "- "));
        const fromFirstLine = !rest;
        if (fromFirstLine && lines2.length > 1) lines2.shift();
        // Topic/session role swap: a `Topic: x` line promotes to the header,
        // and the ## session slug drops into the body where the topic text
        // sat (stripped from body to avoid duplication, mirroring the
        // title-repeat strip below). No Topic line: unchanged behavior.
        let rawTitle = clean(rest || lines2[0] || "untitled").replace(/^#+\s*/, "").replace(/^[-*•>]+\s*/, "");
        const topicIdx = lines2.findIndex((l) => /^[-*]?\s*topic\s*:/i.test(l));
        if (topicIdx >= 0) {
          const topic = lines2[topicIdx].replace(/^[-*]?\s*topic\s*:\s*/i, "").trim().slice(0, 120);
          lines2.splice(topicIdx, 1);
          if (topic) {
            // Unless the title already came from that same Topic line
            // (no ## rest), demote the session slug into the body.
            const titleWasTopic = !rest;
            if (!titleWasTopic && rawTitle && !lines2.some((l) => l.toLowerCase() === rawTitle.toLowerCase())) lines2.unshift(rawTitle);
            rawTitle = topic;
          }
        }
        const title = rawTitle.slice(0, 120);
        // Structured lines: `Next:` and `Due: YYYY-MM-DD · label` are shown as
        // their own UI (next line, due pill), not as plain facts.
        const nexts: string[] = [];
        const dues: Due[] = [];
        const whys: string[] = [];
        for (let i = lines2.length - 1; i >= 0; i--) {
          const l = lines2[i];
          const dm = l.match(/^[-*]?\s*due\s*:\s*(\d{4}-\d{2}-\d{2})(?:\s*[·•-]\s*(.*))?$/i);
          if (dm) { dues.unshift({ date: dm[1], label: (dm[2] || "").trim() }); lines2.splice(i, 1); continue; }
          const nm = l.match(/^[-*]?\s*next\s*:\s*(.*)$/i);
          if (nm) { if (nm[1] && !/^no follow-?up/i.test(nm[1])) nexts.unshift(nm[1]); lines2.splice(i, 1); continue; }
          if (/^[-*]?\s*no follow-?up\.?$/i.test(l)) { lines2.splice(i, 1); continue; }
          const wm = l.match(/^[-*•]?\s*why it matters\s*:\s*(.*)$/i);
          if (wm) { if (wm[1].trim()) whys.unshift(wm[1].trim()); lines2.splice(i, 1); }
        }
        let full = lines2.join("\n").slice(0, 2000);
        let flat = plainMd(full).replace(/\s+/g, " ");
        // Body often restates the title verbatim ("Sample chat What: …"
        // under title "Sample chat") — strip that leading repeat so the
        // entry doesn't read twice.
        const tLower = title.toLowerCase();
        if (tLower && flat.toLowerCase().startsWith(tLower)) {
          const cut = flat.slice(title.length).replace(/^[\s:–—-]+/, "");
          if (cut) {
            flat = cut;
            const idx = full.search(/\n/);
            full = (idx > 0 ? full.slice(idx + 1).trimStart() : cut).slice(0, 2000);
          }
        }
        const snippet = flat.slice(0, 160);
        const expandable = flat.length > 165 || lines2.length > 1;
        const hintRaw = body.find((l) => /\[hint\]/.test(l)) || "";
        // "Why it matters" is meta, not body: it becomes the hint tag when
        // no [hint] line claims it, otherwise it stays out of the bullets.
        const hint = (clean(hintRaw) || whys[0] || "").slice(0, 90);
        (byDay[date] ||= []).push({ date, time: time || "", title, snippet, full, expandable, hint, nexts, dues, facts: full });
      }
      const dayKeys = Object.keys(byDay).sort().reverse();
      if (!dayKeys.length) continue;
      const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
      const dayLabel = (ds: string) => {
        const [y, mo, d] = ds.split("-").map(Number);
        const dt = new Date(y, mo - 1, d);
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
        const prefix = ds === today ? "Today" : dt.toLocaleDateString("en-GB", { weekday: "long" });
        return `${prefix} · ${d} ${months[mo - 1]}`;
      };
      const first = dayKeys[dayKeys.length - 1].split("-").map(Number);
      void first;
      // Week label from the filename's ISO week (entry spillover made it lie).
      const wm = file.match(/(\d{4})-W(\d{2})/);
      let monday: Date;
      if (wm) {
        const jan4 = new Date(Number(wm[1]), 0, 4);
        monday = new Date(jan4);
        monday.setDate(jan4.getDate() - ((jan4.getDay() + 6) % 7) + (Number(wm[2]) - 1) * 7);
      } else {
        const dt = new Date();
        monday = new Date(dt);
        monday.setDate(dt.getDate() - ((dt.getDay() + 6) % 7));
      }
      const label = `week of ${monday.getDate()} ${months[monday.getMonth()]}, ${monday.getFullYear()}`;
      weeks.push({
        file, label,
        days: dayKeys.map((k) => ({ label: dayLabel(k), entries: byDay[k] })),
        count: dayKeys.reduce((n, k) => n + byDay[k].length, 0),
      });
    }
  } catch (e) { if ((e as { code?: string })?.code !== "ENOENT") loadErr = "Couldn't read the notebook folder."; }
  const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const nowD = new Date();
  const todayUtc = Date.UTC(nowD.getFullYear(), nowD.getMonth(), nowD.getDate());
  const dueInfo = (ds: string) => {
    const [y, mo, d] = ds.split("-").map(Number);
    const days = Math.round((Date.UTC(y, mo - 1, d) - todayUtc) / 864e5);
    const text = days < 0 ? `${-days}d overdue` : days === 0 ? "today" : days === 1 ? "tomorrow" : days < 14 ? `in ${days}d` : `${d} ${MON[mo - 1]}`;
    return { days, text, cls: days < 0 ? "bad" : days <= 2 ? "soon" : "" };
  };
  const pill = (ds: string) => { const i = dueInfo(ds); return `<span class="due ${i.cls}">${esc(i.text)}</span>`; };
  // Topics: every entry of a topic across the loaded weeks. Newest entry's
  // Next wins; the Due comes from the newest entry that carries one (due.py parity).
  type Topic = { key: string; title: string; entries: Entry[]; dues: Due[] };
  const topicMap = new Map<string, Topic>();
  for (const w of weeks) for (const d of w.days) for (const e of d.entries) {
    const k = e.title.toLowerCase();
    let t = topicMap.get(k);
    if (!t) { t = { key: k, title: e.title, entries: [], dues: [] }; topicMap.set(k, t); }
    t.entries.push(e);
  }
  const stamp = (e: Entry) => `${e.date} ${e.time || "00:00"}`;
  const topics = [...topicMap.values()];
  for (const t of topics) {
    t.entries.sort((a, b) => stamp(b).localeCompare(stamp(a)));
    // Notebook deadlines expire after their date; past bookings are history, not overdue tasks.
    t.dues = (t.entries.find((e) => e.dues.length)?.dues || []).filter((x) => dueInfo(x.date).days >= 0);
  }
  topics.sort((a, b) => stamp(b.entries[0]).localeCompare(stamp(a.entries[0])));
  const dueRows = topics.flatMap((t) => t.dues.map((d) => ({ t, d, i: dueInfo(d.date) }))).sort((a, b) => a.i.days - b.i.days);
  const soon = dueRows.filter((r) => r.i.days <= 14).slice(0, 4);
  const searchOf = (e: Entry) => esc((e.title + " " + e.full + " " + e.hint + " " + e.nexts.join(" ") + " " + e.dues.map((d) => d.label).join(" ")).toLowerCase());
  const nextOf = (e: Entry) => e.nexts[0] || "";
  const rowHtml = (opts: { search: string; title: string; meta?: string; next: string; pills?: string; body: string }) => {
    const head = `<div class="rh"><span class="rt">${esc(opts.title)}</span>${opts.meta ? `<span class="rm">${esc(opts.meta)}</span>` : ""}${opts.pills || ""}</div>` +
      (opts.next ? `<div class="rn"><b>Next</b> ${esc(opts.next)}</div>` : "");
    return opts.body
      ? `<details class="row" data-search="${opts.search}"><summary>${head}</summary><div class="rb">${opts.body}</div></details>`
      : `<div class="row flat" data-search="${opts.search}"><div class="sum">${head}</div></div>`;
  };
  const factsHtml = (e: Entry) => e.facts ? `<div class="rf md">${markdownHtml(e.facts)}</div>` : "";
  const entryHtml = (e: Entry) => {
    // The hint tag repeats the body when the [hint] line restates a bullet —
    // show it only when it adds something the body doesn't already say.
    const flatBody = plainMd(e.full).replace(/\s+/g, " ").toLowerCase();
    const hintNew = e.hint && !flatBody.includes(e.hint.toLowerCase().replace(/\s+/g, " "));
    return `<div class="entry" data-search="${esc((e.title + " " + e.full + " " + e.hint + " " + e.nexts.join(" ") + " " + e.dues.map((d) => d.date + " " + d.label).join(" ")).toLowerCase())}" data-hint="${esc(e.hint)}">` +
    `<div class="etime">${esc(e.time)}</div><div class="ebody"><div class="t">${esc(e.title)}</div>` +
    (hintNew ? `<div><span class="htag">${esc(e.hint)}</span></div>` : "") +
    (e.expandable
      ? `<details class="xmore"><summary class="m trunc">${esc(e.snippet)} <span class="more"> …more</span></summary>` +
        `<div class="m full md">${markdownHtml(e.full)}<span class="less" onclick="this.closest('details').removeAttribute('open')"> ▴less</span></div></details>`
      : `<div class="m trunc">${esc(e.snippet)}</div>`) +
    `</div></div>`;
  };
  const topicHtml = (t: Topic) => {
    const latest = t.entries[0];
    const older = t.entries.slice(1).map((e) =>
      `<div class="hist"><span class="hd">${esc(e.date.slice(5).replace("-", "/"))}</span>${esc(plainMd(e.facts || e.nexts[0] || "").replace(/\s+/g, " ").slice(0, 140))}</div>`).join("");
    const body = factsHtml(latest) + (older ? `<div class="hl">Earlier</div>${older}` : "");
    return rowHtml({
      search: t.entries.map(searchOf).join(" "), title: t.title,
      meta: t.entries.length > 1 ? `${t.entries.length} notes` : latest.date.slice(5).replace("-", "/"),
      next: nextOf(latest), pills: t.dues.map((d) => pill(d.date)).join(""), body,
    });
  };
  const dueRowHtml = (r: (typeof dueRows)[number]) => {
    const [, mo, d] = r.d.date.split("-").map(Number);
    const e = r.t.entries[0];
    const full = e.full.replace(/\s+/g, " ").trim();
    return `<details class="duerow ${r.i.cls}" data-search="${esc((r.t.title + " " + (r.d.label || "") + " " + full).toLowerCase())}">` +
      `<summary><div class="dd"><b>${d}</b><span>${MON[mo - 1]}</span></div>` +
      `<div class="dm"><div class="rt">${esc(r.d.label || r.t.title)}</div><div class="rn">${esc(r.d.label ? r.t.title : "")}${r.d.label && e.nexts[0] ? " · " : ""}${esc(e.nexts[0] || "")}</div></div>` +
      `<span class="due ${r.i.cls}">${esc(r.i.text)}</span></summary>` +
      `<div class="dmfull md">${markdownHtml(e.full)}</div></details>`;
  };
  const soonCard = soon.length
    ? `<div class="soonc"><div class="sh">Due soon</div>${soon.map(dueRowHtml).join("")}</div>` : "";
  const weeksView = weeks.length ? weeks.map((w, i) =>
      `<details class="wk week grp"${i === 0 ? " open" : ""}><summary>${esc(w.label)}<div class="m">${w.count} notes</div></summary>` +
      w.days.map((d) => `<div class="day">${esc(d.label)}${d.entries.length > 1 ? `<span>${d.entries.length} items</span>` : ""}</div>` + d.entries.slice().reverse().map(entryHtml).join("")).join("") +
      `</details>`).join("") : `<div class="empty">${esc(loadErr || "no notes yet")}</div>`;
  const topicsView = topics.map(topicHtml).join("");
  const dueView = dueRows.length ? dueRows.map(dueRowHtml).join("") : `<div class="empty">no dated items — add one with <code>--due</code></div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>notebook</title><style>` +
    `body{background:#F5F1E6;color:#262220;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:0 auto;max-width:720px;padding:16px 16px 48px;font-size:13px;line-height:1.55}` +
    `h1{font-size:22px;margin:0;font-weight:700}.sub{font-size:12px;color:#6B645A;margin:0 0 14px}` +
    `.tabs{display:flex;gap:8px;margin-bottom:20px}` +
    `.tabs span,.tabs a{border:1px solid #D8D1C0;border-radius:20px;background:#F5F1E6;color:#262220;font-size:12px;font-family:-apple-system,sans-serif;padding:8px 16px;text-decoration:none}` +
    `.tabs .on{background:#262220;color:#ECE7DA;border-color:#262220}` +
    `.week{background:#F5F1E6;border:1px solid #D8D1C0;border-radius:16px;padding:14px;margin-bottom:12px}` +
    `.week summary{font-size:15px;font-weight:700;cursor:pointer;list-style:none;line-height:1.3}` +
    `.week summary .m{font-size:11px;color:#6B645A;margin-top:2px}` +
    `.day{font-size:13px;font-weight:600;margin:18px 0 8px;color:#262220}` +
    `.day span{color:#6B645A;font-size:11px;margin-left:8px;font-weight:400;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}` +
    `.entry{display:flex;gap:12px;align-items:flex-start;background:none;border:none;border-bottom:1px solid #E0D8C4;border-radius:0;padding:10px 0;margin-bottom:0}` +
    `.entry.x{cursor:pointer}` +
    `.entry>.etime{flex:0 0 15%;max-width:15%}` +
    `.entry>.ebody{flex:1;min-width:0}` +
    `.t{font-size:15.5px;font-weight:650;line-height:1.4;overflow-wrap:anywhere}.m{font-size:14px;color:#6B645A;margin-top:2px;line-height:1.55;overflow-wrap:anywhere}` +
    `.htag{display:inline-block;font-size:11px;font-style:italic;color:#5F7A5A;background:#E4EBDD;border:1px solid #C6D4B8;border-radius:10px;padding:1px 8px;margin-top:4px;overflow-wrap:anywhere}` +
    `.m.full{white-space:pre-wrap}.more,.less{color:#8A8272;font-style:italic;white-space:nowrap;cursor:pointer;padding:10px 14px 10px 0;display:inline-block}` +
    `details.xmore summary{list-style:none;cursor:pointer}` +
    `details.xmore summary::-webkit-details-marker{display:none}` +
    `details.xmore summary::marker{content:""}` +
    `details.xmore[open]>summary{display:none}` +
    `.etime{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#8A8272;font-variant-numeric:tabular-nums;flex:0 0 15%;padding-top:3px}` +
    `details.wk summary::-webkit-details-marker{display:none}` +
    `#search{width:100%;border:1px solid #D8D1C0;border-radius:12px;background:#EFE9DA;color:#262220;font-family:inherit;font-size:13px;padding:10px 14px;margin-bottom:12px}` +
    `.titlebar{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:14px}` +
    `.titlebar .sub{margin:0}` +
    `.profile-button{width:40px;height:40px;flex:0 0 40px;border:1px solid #D8D1C0;border-radius:50%;background:#EFE9DA;color:#262220;display:grid;place-items:center;cursor:pointer}` +
    `.profile-button svg{width:20px;height:20px}` +
    `#profile-dialog{width:min(680px,calc(100vw - 36px));max-height:82vh;padding:0;border:1px solid #D8D1C0;border-radius:16px;background:#F5F1E6;color:#262220;box-shadow:0 16px 48px #0004}` +
    `#profile-dialog::backdrop{background:#0007}` +
    `.profile-head{position:sticky;top:0;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:16px 18px;background:#F5F1E6;border-bottom:1px solid #D8D1C0}` +
    `.profile-head h2{font-size:17px;margin:0}` +
    `.profile-close{border:0;background:transparent;color:inherit;font-size:26px;line-height:1;cursor:pointer;padding:2px 6px}` +
    `.profile-content{overflow:auto;max-height:calc(82vh - 58px);padding:4px 18px 18px}` +
    `.profile-content h2{font-size:16px;margin:18px 0 8px}.profile-content h3{font-size:14px;margin:16px 0 6px}` +
    `.profile-content ul{padding-left:20px;margin:6px 0 12px}.profile-content li{margin:5px 0;overflow-wrap:anywhere}` +
    `.profile-content p{margin:8px 0;overflow-wrap:anywhere}.profile-content code{font-size:11px}` +
    `.profile-status{color:#6B645A;font-style:italic}` +
    `#ptr{display:none;text-align:center;font-size:12px;color:#6B645A;padding:8px}` +
    `#ptr.show{display:block}` +
    `.empty{color:#6B645A;font-size:14px;font-style:italic}` +
    `.more-weeks{text-align:center;margin:18px 0}.more-weeks a{color:#262220;font-size:14px;padding:12px 18px;border:1px solid #D8D1C0;border-radius:20px;text-decoration:none;display:inline-block}` +
    `@media (prefers-color-scheme: dark){.more-weeks a{color:#ECE7DA;border-color:#3A352E}}` +
    `.seg{display:flex;gap:6px;margin:4px 0 14px}.seg span{border:1px solid #D8D1C0;border-radius:18px;font:12px -apple-system,sans-serif;padding:7px 14px;cursor:pointer;color:#262220}` +
    `.seg i{font-style:normal;opacity:.55;margin-left:3px}.seg .on{background:#262220;color:#ECE7DA;border-color:#262220}` +
    `.view[hidden]{display:none}` +
    `.row{border-bottom:1px solid #E0D8C4;padding:10px 0}.row>summary{list-style:none;cursor:pointer}.row>summary::-webkit-details-marker{display:none}.row>summary{font-size:13px;font-weight:400;line-height:1.55}` +
    `.rh{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}.rt{font-size:15px;font-weight:650;line-height:1.35;overflow-wrap:anywhere}` +
    `.rm{font-size:11px;color:#8A8272;font-variant-numeric:tabular-nums}` +
    `.rn{font-size:13px;font-weight:400;color:#6B645A;margin-top:2px;line-height:1.45;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.rn b{font:600 10px -apple-system,sans-serif;text-transform:uppercase;letter-spacing:.05em;color:#8A8272;margin-right:4px}` +
    `.row[open] .rn{display:block}.rb{margin-top:8px;padding-left:10px;border-left:2px solid #D8D1C0}` +
    `.rf{font-size:13px;color:#4A443C;white-space:pre-wrap;line-height:1.55;overflow-wrap:anywhere}` +
    `.md{white-space:normal!important}.md p{margin:0 0 6px}.md ul,.md ol{margin:2px 0 6px;padding-left:20px}.md li{margin:2px 0}.md h2,.md h3{font-size:1em;margin:8px 0 3px}.md code{background:rgba(128,120,100,.18);padding:1px 4px;border-radius:4px;font-size:.92em}.md a{color:inherit;text-decoration:underline}.md>:last-child{margin-bottom:0;display:inline-block}` +
    `.hl{font:600 10px -apple-system,sans-serif;text-transform:uppercase;letter-spacing:.05em;color:#8A8272;margin:12px 0 4px}` +
    `.hist{font-size:12px;color:#6B645A;margin:3px 0;line-height:1.45}.hd{font-variant-numeric:tabular-nums;color:#8A8272;margin-right:8px}` +
    `.due{font:600 11px -apple-system,sans-serif;border-radius:10px;padding:2px 8px;background:#E7E0CE;color:#4A443C;white-space:nowrap;margin-left:auto}` +
    `.due.soon{background:#E8C27A;color:#3A2A08}.due.bad{background:#C0473A;color:#fff}` +
    `.soonc{background:#EFE9DA;border:1px solid #D8D1C0;border-radius:14px;padding:6px 14px;margin-bottom:14px}.sh{font:600 11px -apple-system,sans-serif;text-transform:uppercase;letter-spacing:.06em;color:#8A8272;padding:6px 0}` +
    `.duerow{display:block;padding:9px 0;border-top:1px solid #E0D8C4}.soonc .duerow:first-of-type{border-top:0}` +
    `details.duerow>summary{display:flex;align-items:center;gap:12px;list-style:none;cursor:pointer}details.duerow>summary::-webkit-details-marker{display:none}` +
    `.dmfull{margin:6px 0 2px 54px}.duerow[open] .due{outline:2px solid currentColor}` +
    `.dd{flex:0 0 42px;text-align:center;line-height:1.1}.dd b{display:block;font-size:19px}.dd span{font:11px -apple-system,sans-serif;color:#8A8272;text-transform:uppercase}` +
    `.dm{flex:1;min-width:0}.duerow .due{margin-left:0}.duerow.bad .dd b{color:#C0473A}` +
    `.hide{display:none!important}` +
    `@media (prefers-color-scheme: dark){.seg span{border-color:#3A352E;color:#ECE7DA}.row,.duerow{border-color:#3A352E!important}.rn,.hist{color:#A89E8C}.rf{color:#CFC6B2}.rb{border-color:#3A352E}.due{background:#3A352E;color:#ECE7DA}.due.soon{background:#E8C27A;color:#3A2A08}.due.bad{background:#E08073;color:#2A0F0B}.soonc{background:#262220;border-color:#3A352E}.duerow.bad .dd b{color:#E08073}}` +
    DARK_CSS + `</style></head><body>` +
    `<div class="titlebar"><div><h1>notebook</h1><div class="sub">a book of days, kept weekly</div></div>` +
    `<button id="profile-button" class="profile-button" type="button" aria-label="Show user details" title="User details">` +
    `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M4.8 20a7.2 7.2 0 0 1 14.4 0"/></svg></button></div>` +
    `<dialog id="profile-dialog" aria-labelledby="profile-title"><div class="profile-head"><h2 id="profile-title">User details</h2><button class="profile-close" type="button" aria-label="Close">×</button></div><div id="profile-content" class="profile-content"><p class="profile-status">Details load when opened.</p></div></dialog>` +
    `<div class="tabs"><a href="/terminus/">artifacts</a><a href="/ops">ops</a><span class="on">notes</span></div>` +
    `<input id="search" type="search" placeholder="search notes…" autocomplete="off">` +
    `<div id="soonwrap">${soonCard}</div>` +
    `<div class="seg" id="seg"><span data-v="weeks" class="on">Weeks</span><span data-v="topics">Topics <i>${topics.length}</i></span><span data-v="due">Due <i>${dueRows.length}</i></span></div>` +
    `<div id="v-weeks" class="view">${weeksView}</div>` +
    `<div id="v-topics" class="view" hidden>${topicsView || '<div class="empty">no topics yet</div>'}</div>` +
    `<div id="v-due" class="view" hidden>${dueView}</div>` +
    `<div id="nomatch" class="empty" style="display:none">no notes match</div>` +
    (hasOlder ? `<div class="more-weeks"><a href="#" id="older">show older weeks</a></div>` : "") +
    `<div id="ptr">↓ pull to refresh</div>` +
    `<script>
    if(new URLSearchParams(location.search).get("mobile")){document.querySelector(".tabs").style.display="none";window.__app=1}
    try{const qt=new URLSearchParams(location.search).get("token");if(qt)localStorage.setItem("terminus-token",qt.trim())}catch{}
    const profileDialog=document.getElementById("profile-dialog");
    const profileContent=document.getElementById("profile-content");
    let profileLoaded=false;
    document.getElementById("profile-button").addEventListener("click",async()=>{
      profileDialog.showModal();
      if(profileLoaded)return;
      profileContent.innerHTML='<p class="profile-status">Loading details…</p>';
      try{
        let token="";try{token=new URLSearchParams(location.search).get("token")||localStorage.getItem("terminus-token")||""}catch{}
        const headers=token?{Authorization:"Bearer "+token}:{};
        const response=await fetch("/notes-profile",{headers,cache:"no-store"});
        if(!response.ok)throw new Error("HTTP "+response.status);
        const data=await response.json();
        profileContent.innerHTML=data.html||'<p class="profile-status">No user details found.</p>';
        profileLoaded=true;
      }catch{profileContent.innerHTML='<p class="profile-status">Could not load user details. Close and try again.</p>'}
    });
    document.querySelector(".profile-close").addEventListener("click",()=>profileDialog.close());
    profileDialog.addEventListener("click",(e)=>{if(e.target===profileDialog)profileDialog.close()});
    var view="weeks";
    function setView(v){view=v;document.querySelectorAll(".seg span").forEach(function(x){x.classList.toggle("on",x.dataset.v===v)});["weeks","topics","due"].forEach(function(n){document.getElementById("v-"+n).hidden=n!==v});document.getElementById("search").oninput({target:document.getElementById("search")})}
    document.getElementById("seg").onclick=function(e){var v=e.target.closest("span");if(v&&v.dataset.v)setView(v.dataset.v)};
    document.getElementById("search").oninput=function(e){var q=e.target.value.toLowerCase().trim(),root=document.getElementById("v-"+view),n=0;
      document.getElementById("soonwrap").classList.toggle("hide",!!q);
      root.querySelectorAll(".row,.duerow,.entry").forEach(function(el){var ok=!q||el.dataset.search.indexOf(q)>=0;el.classList.toggle("hide",!ok);if(ok)n++});
      root.querySelectorAll("details.wk").forEach(function(d){var hit=d.querySelector(".row:not(.hide),.entry:not(.hide)");d.classList.toggle("hide",!!q&&!hit);if(q&&hit)d.open=true;d.querySelectorAll(".day").forEach(function(day){var x=day.nextElementSibling,h=false;while(x&&!x.classList.contains("day")){if((x.classList.contains("row")||x.classList.contains("entry"))&&!x.classList.contains("hide"))h=true;x=x.nextElementSibling}day.classList.toggle("hide",!!q&&!h)})});
      document.getElementById("nomatch").style.display=(q&&!n)?"block":"none"};

    (function(){var K="nb-state",ss=sessionStorage,hid=0;
      var ol=document.getElementById("older");if(ol)ol.onclick=function(e){e.preventDefault();var p=new URLSearchParams(location.search);p.set("all","1");location.search=p.toString()};
      try{var st=JSON.parse(ss.getItem(K)||"null");if(st){ss.removeItem(K);var q=document.getElementById("search");q.value=st.q||"";if(st.v)setView(st.v);if(q.value)q.oninput({target:q});
        document.querySelectorAll("details.wk").forEach(function(d,i){if(!q.value&&st.w&&i<st.w.length)d.open=!!st.w[i]});
        requestAnimationFrame(function(){scrollTo(0,st.y||0)})}}catch(e){}
      document.addEventListener("visibilitychange",function(){if(document.hidden){hid=Date.now();return}
        if(hid&&Date.now()-hid>120000){try{ss.setItem(K,JSON.stringify({v:view,y:scrollY,q:document.getElementById("search").value,w:Array.from(document.querySelectorAll("details.wk")).map(function(d){return d.open?1:0})}))}catch(e){}location.reload()}});
    })();
    let py=null;
    const ptr=document.getElementById("ptr");
    document.addEventListener("touchstart",(e)=>{if(!window.__app&&window.scrollY===0)py=e.touches[0].clientY},{passive:true});
    document.addEventListener("touchmove",(e)=>{if(py===null)return;const d=e.touches[0].clientY-py;if(d>70){ptr.classList.add("show");ptr.textContent="↓ release to refresh"}else if(d>10){ptr.classList.add("show");ptr.textContent="↓ pull to refresh"}},{passive:true});
    document.addEventListener("touchend",()=>{if(ptr.classList.contains("show")&&ptr.textContent.includes("release"))location.reload();ptr.classList.remove("show");py=null});
    </script></body></html>`;
}

type Skill = { name: string; desc: string; html: string };
/** Skills catalog: reads repo skills SKILL.md files (plus one nested level
 *  for third-party) for the ops hub. Frontmatter name/description for
 *  the list; body rendered to HTML for click-to-preview. */
function loadSkills(): Skill[] {
  const out: Skill[] = [];
  try {
    const root = join(repoRoot(), "skills");
    const dirs = readdirSync(root, { withFileTypes: true })
      .filter((d) => d.isDirectory() && !d.name.startsWith("."))
      .map((d) => d.name);
    const cand: string[] = [];
    for (const d of dirs) {
      cand.push(join(root, d, "SKILL.md"));
      try {
        for (const sub of readdirSync(join(root, d), { withFileTypes: true })
          .filter((s) => s.isDirectory() && !s.name.startsWith("."))
          .map((s) => s.name)) cand.push(join(root, d, sub, "SKILL.md"));
      } catch { /* no nested level */ }
    }
    for (const f of cand.sort()) {
      let text = "";
      try { text = readFileSync(f, "utf8"); } catch { continue; }
      const m = text.match(/^---\s*\n([\s\S]*?)\n---\s*\n?([\s\S]*)$/);
      const front = m ? m[1] : "";
      const body = (m ? m[2] : text).trim().slice(0, 8000);
      const nm = front.match(/^name:\s*(.+)$/m)?.[1]?.trim() || f.split("/").slice(-2, -1)[0];
      const ds = front.match(/^description:\s*(>?[\s\S]*?)(?=^\w+:|\n$)/m)?.[1]?.replace(/^>\s?/gm, "").replace(/\s+/g, " ").trim()
        || front.match(/^description:\s*(.+)$/m)?.[1]?.trim() || "";
      out.push({ name: nm.replace(/^["']|["']$/g, ""), desc: ds.replace(/^["']|["']$/g, "").slice(0, 300), html: profileMarkdownHtml(body) });
    }
  } catch { /* no skills dir */ }
  return out;
}

/** Per-thread schedule facts for the app's chat list (its third line: cadence and next run). Raw values
 *  only; the phone formats them in its own time zone. A job delivers into `chat` when set, else
 *  `schedule:<name>`; several jobs can share a thread, so the soonest live one wins. */
export type ScheduleRow = { chat: string; name: string; cron?: string; everyMin?: number; at?: string; next?: number; retired: boolean };
export function scheduleRowsFrom(jobs: Array<Record<string, unknown>>): ScheduleRow[] {
  const byChat = new Map<string, ScheduleRow>();
  for (const j of jobs) {
    const name = typeof j.name === "string" ? j.name : "";
    if (!name) continue;
    const chat = typeof j.chat === "string" && j.chat ? j.chat : `schedule:${name}`;
    const nextRaw = typeof j.next_run === "number" ? j.next_run : parseFloat(String(j.next_run ?? ""));
    const row: ScheduleRow = {
      chat, name, retired: !!j.retired_at,
      ...(typeof j.cron === "string" && j.cron ? { cron: j.cron } : {}),
      ...(typeof j.every_min === "number" ? { everyMin: j.every_min } : {}),
      ...(typeof j.at === "string" && j.at ? { at: j.at } : {}),
      ...(!j.retired_at && Number.isFinite(nextRaw) && nextRaw > 0 ? { next: Math.round(nextRaw > 1e12 ? nextRaw : nextRaw * 1000) } : {}),
    };
    const prev = byChat.get(chat);
    const better = !prev || (prev.retired && !row.retired) ||
      (!row.retired && row.next !== undefined && (prev.next === undefined || row.next < prev.next));
    if (better) byChat.set(chat, row);
  }
  return [...byChat.values()];
}

function scheduleRows(): ScheduleRow[] {
  try {
    const raw = readFileSync(join(homedir(), ".config/terminus/schedule/jobs.json"), "utf8");
    return scheduleRowsFrom((JSON.parse(raw).jobs || []) as Array<Record<string, unknown>>);
  } catch { return []; }
}

/** Ops console: read-only schedule + recent fired runs as clean HTML.
 *  Lives behind the same tailnet + bearer auth as everything else. */
function opsHtml(): string {
  const esc = (s: unknown) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  let jobs: Array<{ name?: string; title?: string; cron?: string; next_run?: string | null; last_run?: number | null; deliver?: string | null; retired_at?: string | null; retire_reason?: string | null; fires?: number }> = [];
  let loadErr = "";
  try {
    const raw = readFileSync(join(homedir(), ".config/terminus/schedule/jobs.json"), "utf8");
    jobs = (JSON.parse(raw).jobs || []) as typeof jobs;
  } catch (e) { if ((e as { code?: string })?.code !== "ENOENT") loadErr = "Couldn't read the schedule file."; }
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const fmtNext = (v: unknown): string => {
    if (v == null || v === "") return "";
    const n = typeof v === "number" ? v : parseFloat(String(v));
    if (!isNaN(n) && String(v).match(/^[0-9.]+$/)) {
      const d = new Date((n > 1e12 ? n : n * 1000));
      return `${d.getDate()} ${months[d.getMonth()]} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    }
    return String(v);
  };
  // Recent runs are events (job · time · channel · outcome), never bodies.
  // Tap a row to expand its one-line status from the fire log.
  const keyOf = (epoch: number): string => {
    const d = new Date(epoch > 1e12 ? epoch : epoch * 1000);
    const p = (n: number) => String(n).padStart(2, "0");
    return `[${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  };
  let logLines: string[] = [];
  try {
    logLines = readFileSync(join(homedir(), ".config/terminus/schedule/fired.log"), "utf8").split("\n");
  } catch { /* no log */ }
  const detailOf = (epoch: number): string => {
    const key = keyOf(epoch);
    for (let i = 0; i < logLines.length; i++) {
      if (logLines[i].trim().startsWith(key)) {
        const next = (logLines[i + 1] || "").trim();
        if (next && !/^---$/.test(next)) return next.slice(0, 200);
        return "";
      }
    }
    return "";
  };
  /** A fire-log line that reads as a failure (the runner writes "⚠ <title>: run failed|not delivered"). */
  const runFailed = (detail: string): boolean => /\u26a0|run failed|not delivered|SEALED FAIL|traceback/i.test(detail);
  const recent = jobs
    .filter((j) => j.last_run)
    .sort((a, b) => (b.last_run || 0) - (a.last_run || 0))
    .slice(0, 8);
  let runIx = 0;
  const runRow = (j: (typeof jobs)[number]) => {
    const id = `run${runIx++}`;
    const detail = j.last_run ? detailOf(j.last_run) : "";
    const bad = runFailed(detail);
    return `<div class="job${bad ? " bad" : ""}" onclick="document.getElementById('${id}').style.display=document.getElementById('${id}').style.display==='none'?'block':'none';this.classList.toggle('open')">` +
    `<span class="ic">${bad ? "✕" : "✓"}</span>` +
    `<div><div class="t">${esc(j.title || j.name || "untitled")}</div>` +
    `<div class="m">${esc(fmtNext(j.last_run))}${bad ? " · needs a look" : ""}${j.deliver ? " · " + esc(j.deliver) : ""}` +
    `${j.retire_reason ? " · " + esc(j.retire_reason) : j.fires ? " · ×" + j.fires : ""}</div>` +
    (detail ? `<div class="m" id="${id}" style="display:${bad ? "block" : "none"};margin-top:6px">${esc(detail)}</div>` : "") +
    `</div>${detail ? `<span class="m caret">⌄</span>` : ""}</div>`;
  };
  const toMs = (v: unknown): number => {
    if (v == null || v === "") return NaN;
    const n = typeof v === "number" ? v : parseFloat(String(v));
    if (!isNaN(n) && /^[0-9.]+$/.test(String(v))) return n > 1e12 ? n : n * 1000;
    return Date.parse(String(v));
  };
  const active = jobs.filter((j) => !j.retired_at).sort((a, b) => {
    const ta = toMs(a.next_run), tb = toMs(b.next_run);
    return (isNaN(ta) ? Infinity : ta) - (isNaN(tb) ? Infinity : tb);
  });
  const retired = jobs.filter((j) => j.retired_at);
  // Recurring (has a cron) apart from one-off reminders: a long run of one-offs used to bury the real routines.
  const recurring = active.filter((j) => (j.cron || "").trim());
  const oneOff = active.filter((j) => !(j.cron || "").trim());
  let jobIx = 0;
  const row = (j: (typeof jobs)[number], on: boolean) => {
    const id = `job${jobIx++}`;
    const detail = (j as { payload?: string }).payload || "";
    return `<div class="job" onclick="var d=document.getElementById('${id}');if(d)d.style.display=d.style.display==='none'?'block':'none';this.classList.toggle('open')">` +
    `<span class="ic">${on ? "◷" : "✓"}</span>` +
    `<div><div class="t">${esc(j.title || j.name || "untitled")}</div>` +
    `<div class="m">${esc(cronText(j.cron))}${!cronText(j.cron) && j.next_run ? esc(fmtNext(j.next_run)) : ""}` +
    `${j.fires ? " · ×" + j.fires : ""}` +
    `${j.retire_reason ? " · " + esc(j.retire_reason) : ""}</div>` +
    (detail ? `<div class="m" id="${id}" style="display:none;margin-top:6px;white-space:pre-wrap">${esc(detail.slice(0, 600))}</div>` : "") +
    `</div>${on && j.next_run ? `<span class="when${soon(toMs(j.next_run)) ? " soon" : ""}">${esc(whenLabel(toMs(j.next_run)))}</span>` : ""}` +
    `${detail ? `<span class="m caret">⌄</span>` : ""}</div>`;
  };
  // Plain-English schedule for the common cron shapes; anything else stays as written.
  const cronText = (c?: string): string => {
    const p = (c || "").trim().split(/\s+/);
    if (p.length !== 5) return c || "";
    const [mi, h, dom, mon, dow] = p;
    const two = (x: string) => x.padStart(2, "0");
    const every = mi.match(/^\*\/(\d+)$/);
    if (every && h === "*" && dom === "*" && mon === "*" && dow === "*") return `Every ${every[1]} min`;
    if (/^\d+$/.test(mi) && h === "*" && dom === "*" && mon === "*" && dow === "*") return mi === "0" ? "Hourly" : `Hourly at :${two(mi)}`;
    if (!(/^\d+$/.test(mi) && /^\d+$/.test(h)) || dom !== "*" || mon !== "*") return c || "";
    const at = `${two(h)}:${two(mi)}`;
    const names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    if (dow === "*") return `Daily ${at}`;
    if (dow === "1-5") return `Weekdays ${at}`;
    if (dow === "0,6" || dow === "6,0") return `Weekends ${at}`;
    if (/^[0-9]$/.test(dow)) return `Weekly ${names[Number(dow) % 7]} ${at}`;
    return c || "";
  };
  const until = (ms: number): string => {
    const m = Math.round(ms / 60000);
    return m < 60 ? `in ${Math.max(m, 1)} min` : m < 2880 ? `in ${Math.round(m / 60)} h` : `in ${Math.round(m / 1440)} days`;
  };
  const nowMs = Date.now();
  /** Short "when" for a pill: due / in 12 min / today 18:30 / tomorrow 09:00 / Mon 2 Nov. */
  const whenLabel = (ms: number): string => {
    if (isNaN(ms)) return "";
    if (ms <= nowMs) return "due";
    const d = new Date(ms), n = new Date(nowMs);
    const hhmm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    const mins = Math.round((ms - nowMs) / 60000);
    if (mins < 60) return `in ${Math.max(mins, 1)} min`;
    const day = (x: Date) => x.getFullYear() * 400 + x.getMonth() * 32 + x.getDate();
    if (day(d) === day(n)) return `today ${hhmm}`;
    if (day(d) === day(new Date(nowMs + 86400000))) return `tomorrow ${hhmm}`;
    const names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    return `${names[d.getDay()]} ${d.getDate()} ${months[d.getMonth()]}`;
  };
  const soon = (ms: number): boolean => !isNaN(ms) && ms - nowMs < 3600000;
  const upcoming = active.map((j) => ({ j, t: toMs(j.next_run) })).filter((x) => x.t > nowMs).sort((a, b) => a.t - b.t)[0];
  const nextHtml = upcoming
    ? `<div class="next"><div class="m">next up</div><div class="t">${esc(upcoming.j.title || upcoming.j.name || "untitled")}</div>` +
      `<div class="m">${esc(fmtNext(upcoming.j.next_run))} · ${until(upcoming.t - nowMs)}</div></div>`
    : "";
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>ops</title><style>` +
    `body{background:#ECE7DA;color:#262220;font-family:ui-monospace,Menlo,monospace;margin:0 auto;max-width:720px;padding:16px 16px 48px;font-size:15px;line-height:1.5}` +
    `h1{font-size:22px;margin:0}h2{font-size:13px;color:#6B645A;margin:20px 0 10px;font-weight:normal}` +
    `.sub{font-size:12px;color:#6B645A;margin:0 0 14px}` +
    `.seg{display:flex;gap:8px;margin-bottom:6px}` +
    `.seg span{border:1px solid #D8D1C0;border-radius:20px;background:#F5F1E6;font-size:12px;padding:8px 14px}` +
    `.seg span.on{background:#262220;color:#ECE7DA;border-color:#262220}` +
    `.tabs{display:flex;gap:8px;margin-bottom:14px}` +
    `.tabs span,.tabs a{border:1px solid #D8D1C0;border-radius:20px;background:#F5F1E6;color:#262220;font-size:12px;padding:8px 16px;text-decoration:none}` +
    `.tabs .on{background:#262220;color:#ECE7DA;border-color:#262220}` +
    `.job{display:flex;align-items:center;gap:10px;background:#F5F1E6;border:1px solid #D8D1C0;border-radius:12px;padding:12px;margin-bottom:8px;overflow:hidden}` +
    `.job>div{flex:1;min-width:0;overflow:hidden}` +
    `.t{font-size:15.5px;overflow-wrap:anywhere;word-break:break-word}` +
    `.m{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#6B645A;margin-top:2px;overflow-wrap:anywhere;word-break:break-word;white-space:pre-wrap}` +
    `.ic{font-size:16px;width:20px;text-align:center;flex:none;opacity:.8}` +
    `.when{flex:none;font-size:11.5px;padding:3px 10px;border-radius:999px;background:#E4DCCB;color:#4A453D;white-space:nowrap}.when.soon{background:#2E7D4F;color:#F5F1E6}` +
    `.job.bad{border-color:#B3261E;background:#F7E7E3}.job.bad .ic{color:#B3261E;opacity:1}` +
    `.dot{width:8px;height:8px;border-radius:50%;flex:none}` +
    `.dot.on{background:#2E7D4F}.dot.off{background:#D8D1C0}` +
    `.fire{background:#F5F1E6;border:1px solid #D8D1C0;border-radius:12px;padding:10px 12px;margin-bottom:8px;font-size:11px;white-space:pre-wrap}` +
    `details summary{font-size:13px;color:#6B645A;margin:20px 0 10px;cursor:pointer}` +
    `.sys{font-size:11px;color:#6B645A;background:#F5F1E6;border:1px solid #D8D1C0;border-radius:12px;padding:10px 12px}` +
    `.sbody h2{font-size:15px;color:#262220;margin:14px 0 6px}` +
    `.sbody h3{font-size:13px;color:#262220;margin:12px 0 4px}` +
    `.sbody ul{padding-left:20px;margin:6px 0 10px}` +
    `.sbody li{margin:4px 0}` +
    `.sbody p{margin:6px 0}` +
    `.sbody code{font-size:11px;background:#EFE9DA;padding:1px 4px;border-radius:4px}` +
    `.empty{color:#6B645A;font-size:13px}` +
    `.next,.tile{background:#F5F1E6;border:1px solid #D8D1C0;border-radius:12px;padding:12px 14px}.next{margin:0 0 12px 0}` +
    `.tiles{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:6px}.bar{height:8px;border-radius:4px;background:#D8D1C0;overflow:hidden;margin:2px 0 6px}.bar>i{display:block;height:100%;background:#2E7D4F}.bar.warn>i{background:#C58A2B}.bar.bad>i{background:#B3261E}.proc{display:flex;justify-content:space-between;align-items:baseline;gap:12px;padding:9px 0;border-bottom:1px solid #E0D8C4}.proc .t{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.proc .m{margin:0;white-space:nowrap}.caret{transition:transform .15s}.open .caret{transform:rotate(180deg)}` +
    `.bar{height:6px;border-radius:3px;background:rgba(128,128,128,.3);margin-top:6px;overflow:hidden}.bar>i{display:block;height:100%;background:#2E7D5B}.bar>i.hot{background:#B5463C}` +
    `.prow{display:flex;align-items:baseline;gap:10px;padding:7px 0;border-top:1px solid rgba(128,128,128,.18)}.prow .pn{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.prow .pv{font-variant-numeric:tabular-nums;opacity:.8;font-size:12px;white-space:nowrap}` + DARK_CSS + `</style></head><body>` +
    `<h1>ops</h1><div class="sub">schedules and runs</div>` +
    `<div class="tabs"><a href="/terminus/">artifacts</a><span class="on">ops</span><a href="/notes">notes</a></div>` +
    `<div class="seg" id="segbar"><span class="on" data-tab="jobs" onclick="showTab('jobs')">schedules</span><span data-tab="runs" onclick="showTab('runs')">recent runs</span><span data-tab="sys" onclick="showTab('sys')">system</span></div>` +
    `<div class="tabsec" id="tab-jobs"><h2 id="sec-jobs">scheduled jobs · ${active.length}</h2>` +
    (loadErr ? `<div class="empty">${esc(loadErr)}</div>` : active.length
      ? nextHtml +
        (recurring.length ? `<h2>recurring · ${recurring.length}</h2>` + recurring.map((j) => row(j, true)).join("") : "") +
        (oneOff.length ? `<h2>one-off reminders · ${oneOff.length}</h2>` + oneOff.map((j) => row(j, true)).join("") : "")
      : `<div class="empty">nothing scheduled</div>`) +
    (retired.length > 10
      ? `<details><summary>retired · ${retired.length} (show all)</summary>` + retired.map((j) => row(j, false)).join("") + `</details>`
      : (retired.length ? `<h2>retired</h2>` + retired.map((j) => row(j, false)).join("") : "")) +
    `</div>` +
    `<div class="tabsec" id="tab-runs" style="display:none"><h2 id="sec-runs">recent runs</h2>` + (recent.length ? recent.map(runRow).join("") : `<div class="empty">no runs yet</div>`) + `</div>` +

    `<div class="tabsec" id="tab-sys" style="display:none"><iframe id="sysframe" title="system monitor" style="width:100%;height:1400px;border:0;display:block;background:transparent" loading="lazy"></iframe></div>` +
    `<script>if(new URLSearchParams(location.search).get("mobile")){document.querySelector(".tabs").style.display="none"}` +
    `function showTab(n){try{sessionStorage.setItem("ops-tab",n)}catch(e){}setTimeout(loadSys,0);document.querySelectorAll(".tabsec").forEach(el=>{el.style.display="none"});var t=document.getElementById("tab-"+n);if(t)t.style.display="block";document.querySelectorAll("#segbar span").forEach(el=>{el.classList.toggle("on",el.dataset.tab===n)});}` +
    `try{var qt=new URLSearchParams(location.search).get("token");if(qt)localStorage.setItem("terminus-token",qt.trim())}catch(e){}` +
    `function loadSys(){var f=document.getElementById("sysframe");if(!f||f.getAttribute("src"))return;var q=new URLSearchParams(location.search),t=q.get("token")||"";try{if(t)localStorage.setItem("terminus-token",t.trim());else t=localStorage.getItem("terminus-token")||""}catch(e){}f.src="/system?embed=1"+(t?"&token="+encodeURIComponent(t):"")}` +
    `window.addEventListener("message",function(e){if(e.origin!==location.origin||!e.data||e.data.type!=="sysmon-h")return;var f=document.getElementById("sysframe");if(f&&e.data.h)f.style.height=(e.data.h+8)+"px"});` +
    `try{var qt=new URLSearchParams(location.search).get("token");if(qt)localStorage.setItem("terminus-token",qt.trim())}catch(e){}` +
    `try{var _t=sessionStorage.getItem("ops-tab");if(_t&&document.getElementById("tab-"+_t))showTab(_t)}catch(e){}` +
    `var _h=0;document.addEventListener("visibilitychange",function(){if(document.hidden){_h=Date.now();return}if(_h&&Date.now()-_h>60000)location.reload()});` +
    `</script>` +
    `</body></html>`;
}

/** Skills catalog: read-only list of repo skills with click-to-preview.
 *  Web-only tab alongside artifacts/ops/notes. Lives behind the same
 *  tailnet + bearer auth as everything else. */
export function skillsHtml(): string {
  const esc = (s: unknown) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const skills = loadSkills();
  let ix = 0;
  const card = (s: Skill) => {
    const id = `skill${ix++}`;
    return `<div class="job" onclick="var d=document.getElementById('${id}');if(d)d.style.display=d.style.display==='none'?'block':'none'">` +
    `<span class="ic">✦</span>` +
    `<div><div class="t">${esc(s.name)}</div>` +
    `<div class="m">${esc(s.desc)}</div>` +
    `<div class="m sbody" id="${id}" style="display:none;margin-top:8px">${s.html}</div>` +
    `</div></div>`;
  };
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>skills</title><style>` +
    `body{background:#ECE7DA;color:#262220;font-family:ui-monospace,Menlo,monospace;margin:0 auto;max-width:720px;padding:16px 16px 48px}` +
    `h1{font-size:22px;margin:0}` +
    `.sub{font-size:12px;color:#6B645A;margin:0 0 14px}` +
    `.tabs{display:flex;gap:8px;margin-bottom:14px}` +
    `.tabs span,.tabs a{border:1px solid #D8D1C0;border-radius:20px;background:#F5F1E6;color:#262220;font-size:12px;padding:8px 16px;text-decoration:none}` +
    `.tabs .on{background:#262220;color:#ECE7DA;border-color:#262220}` +
    `.job{display:flex;align-items:flex-start;gap:10px;background:#F5F1E6;border:1px solid #D8D1C0;border-radius:12px;padding:12px;margin-bottom:8px;overflow:hidden;cursor:pointer}` +
    `.job>div{flex:1;min-width:0;overflow:hidden}` +
    `.t{font-size:14px;overflow-wrap:anywhere;word-break:break-word}` +
    `.m{font-size:11px;color:#6B645A;margin-top:2px;overflow-wrap:anywhere;word-break:break-word;white-space:pre-wrap}` +
    `.sbody{background:none!important;border:none!important;padding:0!important}` +
    `.sbody h2{font-size:15px;color:#262220;margin:14px 0 6px}` +
    `.sbody h3{font-size:13px;color:#262220;margin:12px 0 4px}` +
    `.sbody ul{padding-left:20px;margin:6px 0 10px}` +
    `.sbody li{margin:4px 0}` +
    `.sbody p{margin:6px 0}` +
    `.sbody code{font-size:11px;background:#EFE9DA;padding:1px 4px;border-radius:4px}` +
    `.ic{font-size:16px}` +
    `#search{width:100%;border:1px solid #D8D1C0;border-radius:12px;background:#F5F1E6;color:#262220;font-family:inherit;font-size:13px;padding:10px 14px;margin-bottom:12px}` +
    `.empty{color:#6B645A;font-size:12px}` + DARK_CSS + `</style></head><body>` +
    `<h1>skills</h1><div class="sub">what the agent knows · ${skills.length}</div>` +
    `<div class="tabs"><a href="/terminus/">artifacts</a><a href="/ops">ops</a><a href="/notes">notes</a><span class="on">skills</span></div>` +
    `<input id="search" type="search" placeholder="search skills…" autocomplete="off">` +
    (skills.length ? skills.map(card).join("") : `<div class="empty">no skills found</div>`) +
    `<script>if(new URLSearchParams(location.search).get("mobile")){document.querySelector(".tabs").style.display="none"}` +
    `document.getElementById("search").oninput=(e)=>{const q=e.target.value.toLowerCase();document.querySelectorAll(".job").forEach(el=>{el.style.display=el.textContent.toLowerCase().includes(q)?"":"none"})};` +
    `</script></body></html>`;
}
import { tmpdir } from "node:os";

/** Gallery root: repo artifacts dir (day-folder layout identical to the old
 *  tmp landing). Env MEDIA_DIR overrides for tests. Uploads land here too. */
export const MEDIA_DIR =
  process.env.MEDIA_DIR || join(repoRoot(), "artifacts");
/** Legacy tmp landing: kept created so any old upload path still works,
 *  but the gallery never reads it. */
export const UPLOAD_LANDING_DIR = join(tmpdir(), "terminus");
try {
  const { renameSync } = await import("node:fs");
  for (const old of ["headless-media", "terminus-captures", "shots"]) {
    const legacy = join(tmpdir(), old);
    if (existsSync(legacy) && !existsSync(UPLOAD_LANDING_DIR)) { renameSync(legacy, UPLOAD_LANDING_DIR); break; }
  }
} catch { /* already migrated or nothing to move */ }
await mkdir(MEDIA_DIR, { recursive: true });
// Start the system sampler with the server so the monitor has history the first time it is opened.
startSysmon();
await mkdir(UPLOAD_LANDING_DIR, { recursive: true });

/** Top-level working folders under MEDIA_DIR that are not gallery content:
 *  agent browser screenshots (short retention, see phone-browser.ts) and files
 *  attached to codex/claude turns. handleList skips them; pruneOld still
 *  expires them with everything else. */
export const PB_SHOTS_DIR = "pb-shots";
export const CHAT_ATTACH_DIR = "chat-attach";
const NON_GALLERY_DIRS = new Set([PB_SHOTS_DIR, CHAT_ATTACH_DIR]);

export function dayDir(d = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** Save a capture buffer; baseUrl is `http://<host>:<port>` from server.ts. */
export async function saveShot(buf: Buffer, baseUrl: string): Promise<string> {
  const day = dayDir();
  const dir = join(MEDIA_DIR, day);
  await mkdir(dir, { recursive: true });
  const fname = `shot-${Date.now()}.png`;
  await writeFile(join(dir, fname), buf).catch(() => {});
  const url = `${baseUrl}/terminus/${day}/${fname}`;
  console.log(`saved ${join(dir, fname)} → ${url}`);
  return url;
}

export const RETENTION_DAYS = Number(process.env.MEDIA_RETENTION_DAYS || 7);

/** Persistent app dirs — never pruned, live outside day-folder retention. */
const KEEP_DIRS = new Set([
  "notebook-data",
  ...(process.env.MEDIA_KEEP_DIRS || "").split(",").map((name) => name.trim()).filter(Boolean),
]);

/** How many built Terminus APKs stay in the gallery; older builds go even inside retention (each is ~17 MB). */
export const KEEP_APKS = Number(process.env.MEDIA_KEEP_APKS || 3);

/** Pure: the Terminus APKs to delete so only the `keep` newest remain. */
export function apksToPrune(files: Array<{ path: string; mtimeMs: number }>, keep = KEEP_APKS): string[] {
  return files
    .filter((f) => /(^|\/)terminus-[^/]*\.apk$/.test(f.path))
    .sort((a, b) => b.mtimeMs - a.mtimeMs)
    .slice(Math.max(0, keep))
    .map((f) => f.path);
}

/** Delete files older than retention, including nested render folders. Runs on list hits.
 *  Persistent named dirs stay exempt; build and media exports expire with the gallery. */
export async function pruneOld() {
  const { readdir, lstat, stat, unlink, rmdir } = await import("node:fs/promises");
  const cutoff = Date.now() - RETENTION_DAYS * 86400000;
  const pruneDir = async (dir: string): Promise<void> => {
    const inner = await readdir(dir, { withFileTypes: true }).catch(() => []);
    for (const entry of inner) {
      const fp = join(dir, entry.name);
      if (entry.isDirectory()) {
        await pruneDir(fp);
        try {
          if ((await readdir(fp)).length === 0) await rmdir(fp).catch(() => {});
        } catch {}
      } else if (entry.isFile()) {
        try {
          if ((await stat(fp)).mtimeMs < cutoff) await unlink(fp).catch(() => {});
        } catch {}
      }
    }
  };
  let entries: string[];
  try {
    entries = await readdir(MEDIA_DIR);
  } catch {
    return;
  }
  for (const e of entries) {
    const p = join(MEDIA_DIR, e);
    let s;
    try {
      s = await lstat(p);
    } catch {
      continue;
    }
    if (s.isDirectory()) {
      if (KEEP_DIRS.has(e)) continue; // persistent: exempt from retention
      await pruneDir(p);
      try {
        if ((await readdir(p)).length === 0) await rmdir(p).catch(() => {});
      } catch {}
    } else if (s.mtimeMs < cutoff) {
      // legacy flat files from before day-dirs
      await unlink(p).catch(() => {});
    }
  }
  // Build cap: day folders only (persistent dirs never lose files here).
  const apks: Array<{ path: string; mtimeMs: number }> = [];
  for (const e of await readdir(MEDIA_DIR).catch(() => [] as string[])) {
    if (KEEP_DIRS.has(e)) continue;
    const dir = join(MEDIA_DIR, e);
    for (const f of await readdir(dir).catch(() => [] as string[])) {
      if (!f.endsWith(".apk")) continue;
      try { apks.push({ path: join(dir, f), mtimeMs: (await stat(join(dir, f))).mtimeMs }); } catch {}
    }
  }
  for (const fp of apksToPrune(apks)) await unlink(fp).catch(() => {});
}

export function mimeFor(name: string): string {
  return name.endsWith(".png") ? "image/png"
    : /\.jpe?g$/i.test(name) ? "image/jpeg"
    : name.endsWith(".gif") ? "image/gif"
    : name.endsWith(".webp") ? "image/webp"
    : /\.svg$/i.test(name) ? "image/svg+xml"
    : /\.html?$/i.test(name) ? "text/html; charset=utf-8"
    : /\.txt$/i.test(name) ? "text/plain; charset=utf-8"
    : /\.json$|\.webmanifest$/i.test(name) ? "application/json"
    : /\.css$/i.test(name) ? "text/css"
    : /\.mjs$|\.js$/i.test(name) ? "text/javascript"
    : /\.pdf$/i.test(name) ? "application/pdf"
    : /\.mp4$/i.test(name) ? "video/mp4"
    : /\.webm$/i.test(name) ? "video/webm"
    : /\.mov$/i.test(name) ? "video/quicktime"
    : /\.mp3$/i.test(name) ? "audio/mpeg"
    : /\.wav$/i.test(name) ? "audio/wav"
    : /\.m4a$/i.test(name) ? "audio/mp4"
    : /\.ogg$/i.test(name) ? "audio/ogg"
    : /\.amr$/i.test(name) ? "audio/amr"
    : "application/octet-stream";
}

type Deps = {
  json: (res: ServerResponse, status: number, body: unknown) => void;
  baseUrl: string;
};

export function safeParts(rel: string): string[] | null {
  const parts = rel.split("/").filter(Boolean);
  if (!parts.length || parts.length > 3 || parts.some((p) => p === "." || p === ".." || p.includes("\\"))) return null;
  return parts;
}

async function handleList(_req: IncomingMessage, res: ServerResponse, { json, baseUrl }: Deps) {
  await pruneOld().catch(() => {});
  const { readdir, stat } = await import("node:fs/promises");
  const base = `${baseUrl}/terminus`;
  const folders: Record<string, { u: string; s: number; t: number }[]> = {};
  const flat: { url: string; mtime: number }[] = [];
  const pushFile = async (rel: string, folder: string) => {
    const s = await stat(join(MEDIA_DIR, rel)).catch(() => null);
    if (!s || s.isDirectory()) return;
    const url = `${base}/${rel.split("/").map(encodeURIComponent).join("/")}`;
    flat.push({ url, mtime: s.mtimeMs });
    (folders[folder] ||= []).push({ u: url, s: s.size, t: Math.floor(s.mtimeMs / 1000) });
  };
  const entries = await readdir(MEDIA_DIR).catch(() => [] as string[]);
  for (const e of entries.sort().reverse()) {
    if (NON_GALLERY_DIRS.has(e)) continue;
    const st = await stat(join(MEDIA_DIR, e)).catch(() => null);
    if (!st) continue;
    if (st.isDirectory()) {
      const inner = await readdir(join(MEDIA_DIR, e)).catch(() => [] as string[]);
      for (const f of inner.sort().reverse()) {
        const fp = join(MEDIA_DIR, e, f);
        const fst = await stat(fp).catch(() => null);
        if (fst?.isDirectory()) {
          // One nested level (e.g. shared/data): files group under the top folder.
          const sub = await readdir(fp).catch(() => [] as string[]);
          for (const g of sub.sort().reverse()) await pushFile(`${e}/${f}/${g}`, e);
        } else {
          await pushFile(`${e}/${f}`, e);
        }
      }
    } else {
      await pushFile(e, "older");
    }
  }
  // No recency cap: every folder lists (retention is handled by pruneOld,
  // not by hiding). Day-folders are bounded by the configured prune window.
  flat.sort((a, b) => b.mtime - a.mtime);
  return json(res, 200, {
    mediaUrl: `${baseUrl}/terminus/`,
    files: flat.map((x) => x.url),
    folders,
    retentionDays: RETENTION_DAYS,
  });
}

/** Repo browser root (read-only): the app's own checkout. Only git-tracked
 *  files are ever visible or servable — secrets, notebook plaintext, .run
 *  state, and local config are untracked/ignored by design, hence
 *  unrepresentable here (not merely hidden). */
const WORKSPACE = repoRoot();
let repoCache: { at: number; files: string[] } | null = null;

/** Tracked-set membership: exact match, no traversal, no absolutes. Pure. */
export function isRepoPath(rel: string, files: Set<string>): boolean {
  if (!rel || rel.startsWith("/") || rel.includes("\\")) return false;
  const parts = rel.split("/");
  if (parts.some((p) => !p || p === "." || p === "..")) return false;
  return files.has(rel);
}

async function repoFiles(): Promise<string[]> {
  if (repoCache && Date.now() - repoCache.at < 60_000) return repoCache.files;
  const { execFile } = await import("node:child_process");
  const out: string = await new Promise((resolve, reject) => {
    execFile("git", ["ls-files"], { cwd: WORKSPACE, timeout: 15000 }, (err, stdout) =>
      err ? reject(err) : resolve(stdout as string),
    );
  });
  const files = String(out).split("\n").map((s) => s.trim()).filter(Boolean);
  repoCache = { at: Date.now(), files };
  return files;
}

/** Agentbook books (local, gitignored) listed beside the tracked files as
 *  agentbook/<backend>/<chatId>.md. Read fresh each call: books change often. */
const AGENTBOOK_PREFIX = "agentbook/";
function agentbookFiles(): string[] {
  const out: string[] = [];
  let backends: string[] = [];
  try { backends = readdirSync(AGENTBOOK_DIR); } catch { return out; }
  for (const backend of backends) {
    try {
      for (const f of readdirSync(join(AGENTBOOK_DIR, backend))) {
        if (f.endsWith(".md")) out.push(`${AGENTBOOK_PREFIX}${backend}/${f}`);
      }
    } catch { /* not a directory */ }
  }
  return out;
}

/** GET /api/repo-tree — tracked paths plus agentbook books (bearer-gated by the router). */
export async function handleRepoTree(_req: IncomingMessage, res: ServerResponse, { json }: Deps) {
  try {
    const files = [...(await repoFiles()), ...agentbookFiles()];
    return void json(res, 200, { root: "terminus", count: files.length, files });
  } catch {
    return void json(res, 503, { error: "repo unavailable" });
  }
}

/** GET /repo/<path> — file bytes for tracked paths only (bearer or query
 *  token, like other non-/api GETs). 5MB view cap; HTML sandboxed like
 *  gallery files. Anything outside the tracked set → 404. */
export async function handleRepoFile(req: IncomingMessage, res: ServerResponse, rel: string) {
  let files: string[];
  try {
    files = [...(await repoFiles()), ...agentbookFiles()];
  } catch {
    res.writeHead(503, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "repo unavailable" }));
    return true;
  }
  const decoded = (() => { try { return decodeURIComponent(rel); } catch { return ""; } })();
  if (!isRepoPath(decoded, new Set(files))) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return true;
  }
  // Membership above already limited this to listed paths; agentbook/ resolves under its own dir.
  const path = decoded.startsWith(AGENTBOOK_PREFIX)
    ? join(AGENTBOOK_DIR, ...decoded.slice(AGENTBOOK_PREFIX.length).split("/"))
    : join(WORKSPACE, ...decoded.split("/"));
  let st;
  try {
    const { statSync: stSync } = await import("node:fs");
    st = stSync(path);
  } catch {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return true;
  }
  if (!st.isFile() || st.size > 5 * 1024 * 1024) {
    res.writeHead(st.isFile() ? 413 : 404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: st.isFile() ? "too large to preview" : "not found" }));
    return true;
  }
  const name = decoded.split("/").pop() as string;
  const mime = mimeFor(name);
  const extra = mime.startsWith("text/html")
    ? { "Content-Security-Policy": "sandbox allow-scripts allow-same-origin allow-downloads" }
    : {};
  let dl = {};
  try {
    dl = new URL(req.url || "", "http://x").searchParams.get("download") !== null
      ? { "Content-Disposition": `attachment; filename="${name}"` }
      : {};
  } catch { /* ignore */ }
  res.writeHead(200, {
    "Content-Type": mime,
    "Content-Length": st.size,
    "Accept-Ranges": "bytes",
    "Access-Control-Allow-Origin": "*",
    ...extra,
    ...dl,
  });
  return createReadStream(path).pipe(res);
}

/** Parse a Range header; null when absent/invalid. Exported for tests. */
export function parseRange(header: string | undefined, size: number): { start: number; end: number } | null {
  if (!header || size <= 0) return null;
  const m = header.match(/bytes=(\d*)-(\d*)/);
  if (!m) return null;
  const start = m[1] ? parseInt(m[1], 10) : 0;
  const end = m[2] ? parseInt(m[2], 10) : size - 1;
  if (isNaN(start) || isNaN(end) || start > end || start >= size) return null;
  return { start, end: Math.min(end, size - 1) };
}

function handleFile(req: IncomingMessage, res: ServerResponse, rel: string) {
  const parts = safeParts(rel);
  if (!parts) return { status: 404 as const };
  const name = parts[parts.length - 1];
  const path = join(MEDIA_DIR, ...parts);
  if (!existsSync(path)) return { status: 404 as const, hint: "try GET /terminus for list" };
  const stat = statSync(path);
  if (stat.isDirectory()) return { status: 404 as const };
  const mime = mimeFor(name);
  // Served HTML: keep same-origin (Transformers.js needs Cache API for model
  // caching) but block top-navigation. Files here are user-placed.
  const extra = mime.startsWith("text/html")
    ? { "Content-Security-Policy": "sandbox allow-scripts allow-same-origin allow-downloads" }
    : {};
  const dl = new URL(req.url || "", "http://x").searchParams.get("download") !== null
    ? { "Content-Disposition": `attachment; filename="${name}"` }
    : {};
  const r = parseRange(req.headers.range, stat.size);
  if (r) {
    res.writeHead(206, {
      "Content-Type": mime,
      "Content-Length": r.end - r.start + 1,
      "Content-Range": `bytes ${r.start}-${r.end}/${stat.size}`,
      "Accept-Ranges": "bytes",
      "Access-Control-Allow-Origin": "*",
      ...extra,
      ...dl,
    });
    return createReadStream(path, { start: r.start, end: r.end }).pipe(res);
  }
  res.writeHead(200, {
    "Content-Type": mime,
    "Content-Length": stat.size,
    "Accept-Ranges": "bytes",
    "Access-Control-Allow-Origin": "*",
    ...extra,
    ...dl,
  });
  return createReadStream(path).pipe(res);
}

/** Upload gate: locked by default. Two ways through:
 *  - human says "unlock" (optionally N minutes) → open season for bearers
 *  - X-Upload-Token header with the device key → the recorder app's
 *    straight-through pass. Separate secret from the bearer, so a leaked
 *    bearer alone still can't fill the disk. */
export let uploadsOpen = false;
let uploadTimer: ReturnType<typeof setTimeout> | null = null;

/** True when the request carries the device pass: either the upload key
 *  or the bearer itself (single authority in auth.ts — the bearer opens
 *  everything, including when HEADLESS_TOKEN sets it). */
export function uploadAuthed(req: IncomingMessage): boolean {
  const h = String(req.headers["x-upload-token"] || "");
  if (h !== "" && uploadKeyMatches(h)) return true;
  const a = String(req.headers.authorization || "");
  if (a.startsWith("Bearer ") && bearerMatches(a.slice(7))) return true;
  // The web app signs in with the login cookie (same check as every other route).
  return authorized(req);
}

export function setUploads(open: boolean, minutes = 0): { open: boolean } {
  uploadsOpen = open;
  if (uploadTimer) { clearTimeout(uploadTimer); uploadTimer = null; }
  if (open && minutes > 0) {
    uploadTimer = setTimeout(() => { uploadsOpen = false; uploadTimer = null; }, minutes * 60_000);
  }
  console.log(`uploads ${open ? "OPEN" + (minutes ? ` for ${minutes}m` : "") : "LOCKED"}`);
  return { open: uploadsOpen };
}

/** Phone upload: POST /terminus with either
 *  - raw bytes + ?filename=name.pdf (or X-Filename header), or
 *  - multipart/form-data file field from the gallery form.
 *  Saves to today's day-dir, returns {ok,url,name,size}. Max 50MB.
 *  Rejections are logged with size: silent outbox stalls end here. */
const UPLOAD_MAX = 50 * 1024 * 1024;
/** Raw (non-multipart) uploads are streamed to disk, never held in memory, so they can be big: app bundles, IPAs. */
const UPLOAD_RAW_MAX = 100 * 1024 * 1024;

function cleanName(raw: string): string {
  const base = raw.split("/").pop()?.split("\\").pop() || "upload";
  const safe = base.replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 120) || "upload";
  return `${Date.now()}-${safe}`;
}

/** Ceiling on bytes read-and-discarded from an oversize upload before the socket is cut. */
const DRAIN_MAX = 512 * 1024 * 1024;

/** Collect a request body up to `max` bytes. An oversize body is drained (discarded, not
 *  buffered) so the sender receives the 413 instead of a reset: a reset reads as a transient
 *  network error and the phone would retry the same file forever. */
async function readBody(req: IncomingMessage, max: number): Promise<Buffer | null> {
  const chunks: Buffer[] = [];
  let total = 0;
  let over = false;
  for await (const chunk of req) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buf.length;
    if (over) {
      if (total > DRAIN_MAX) { req.destroy(); break; }
      continue;
    }
    if (total > max) { over = true; chunks.length = 0; continue; }
    chunks.push(buf);
  }
  return over ? null : Buffer.concat(chunks);
}

/** Retry idempotency: a client that never saw our 200 re-sends the same file with the same
 *  X-Upload-Id. We answer with the first result instead of storing a second copy. In memory
 *  (bounded, 24h): a server restart forgets, which only costs one duplicate. */
const uploadSeen = new Map<string, { at: number; result: Record<string, unknown> }>();
const UPLOAD_SEEN_MAX = 500;
const UPLOAD_SEEN_TTL_MS = 24 * 3600_000;
function uploadIdOf(req: IncomingMessage): string {
  const v = String(req.headers["x-upload-id"] || "").trim();
  return /^[A-Za-z0-9._:-]{8,100}$/.test(v) ? v : "";
}
function uploadSeenGet(id: string): Record<string, unknown> | null {
  if (!id) return null;
  const hit = uploadSeen.get(id);
  if (!hit) return null;
  if (Date.now() - hit.at > UPLOAD_SEEN_TTL_MS) { uploadSeen.delete(id); return null; }
  return hit.result;
}
function uploadSeenSet(id: string, result: Record<string, unknown>): void {
  if (!id) return;
  uploadSeen.set(id, { at: Date.now(), result });
  while (uploadSeen.size > UPLOAD_SEEN_MAX) {
    const oldest = uploadSeen.keys().next();
    if (oldest.done) break;
    uploadSeen.delete(oldest.value);
  }
}

/** Echo ingest: the recorder app's private drop. Audio-only, upload-key only.
 *  Rejects non-audio content-types and non-audio filenames. Max 15MB
 *  (a 5-min AAC chunk is ~2-3MB). Never affected by the uploads lock. */
const ECHO_MAX = 15 * 1024 * 1024;
const ECHO_EXT = ["m4a", "aac", "mp3", "wav", "ogg", "opus"];

async function handleEcho(req: IncomingMessage, res: ServerResponse, { json, baseUrl }: Deps) {
  if (!uploadAuthed(req)) {
    return json(res, 401, { error: "unauthorized" });
  }
  const ctype = String(req.headers["content-type"] || "");
  if (!ctype.startsWith("audio/")) return json(res, 415, { error: "audio only" });
  const url = new URL(req.url || "/echo", "http://x");
  const raw = url.searchParams.get("filename") || String(req.headers["x-filename"] || "");
  const ext = raw.split(".").pop()?.toLowerCase() || "";
  if (!ECHO_EXT.includes(ext)) return json(res, 415, { error: "audio only" });
  const body = await readBody(req, ECHO_MAX + 1024);
  if (!body) return json(res, 413, { error: "file too large (max 15MB)" });
  const uid = uploadIdOf(req);
  const prior = uploadSeenGet(uid);
  if (prior) return json(res, 200, { ...prior, dup: true });
  const name = cleanName(raw);
  const day = new Date().toISOString().slice(0, 10);
  await mkdir(join(MEDIA_DIR, day), { recursive: true });
  await atomicWriteFile(join(MEDIA_DIR, day, name), body);
  const result = { ok: true, url: `${baseUrl}/terminus/${day}/${name}`, name, size: body.length };
  uploadSeenSet(uid, result);
  return json(res, 200, result);
}

/** Stream a raw upload (body is the file, name in ?filename=) to a .part file, then rename it into place.
 *  Memory stays flat whatever the size; an over-cap or broken upload leaves nothing behind. */
async function handleRawUpload(req: IncomingMessage, res: ServerResponse, { json, baseUrl }: Deps, filename: string) {
  const uid = uploadIdOf(req);
  const prior = uploadSeenGet(uid);
  if (prior) { req.resume(); return json(res, 200, { ...prior, dup: true }); }
  const day = dayDir();
  const dir = join(MEDIA_DIR, day);
  await mkdir(dir, { recursive: true });
  const name = cleanName(filename);
  const dest = join(dir, name);
  const part = `${dest}.part`;
  const out = createWriteStream(part);
  let total = 0;
  let over = false;
  let failed = false;
  out.on("error", () => { failed = true; });
  try {
    for await (const chunk of req) {
      const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      total += buf.length;
      if (over || failed) { if (total > DRAIN_MAX) { req.destroy(); break; } continue; }
      if (total > UPLOAD_RAW_MAX) { over = true; continue; }
      if (!out.write(buf)) await once(out, "drain").catch(() => { failed = true; });
    }
  } catch { failed = true; }
  await new Promise<void>((r) => out.end(() => r()));
  if (over || failed || !total || !req.complete) {
    await unlink(part).catch(() => {});
    if (over) {
      console.log(`upload rejected: too large (>${UPLOAD_RAW_MAX / 1048576}MB cap)`);
      return json(res, 413, { error: `file too large (max ${UPLOAD_RAW_MAX / 1048576}MB)` });
    }
    return json(res, failed ? 500 : 400, { error: failed ? "could not store file" : "empty file" });
  }
  await rename(part, dest);
  const fileUrl = `${baseUrl}/terminus/${day}/${encodeURIComponent(name)}`;
  console.log(`upload ${dest} (${total} bytes) → ${fileUrl}`);
  const result = { ok: true, url: fileUrl, name, size: total };
  uploadSeenSet(uid, result);
  return json(res, 200, result);
}

async function handleUpload(req: IncomingMessage, res: ServerResponse, deps: Deps) {
  const { json, baseUrl } = deps;
  if (!uploadsOpen && !uploadAuthed(req)) return json(res, 403, { error: "uploads locked" });
  const ctype = String(req.headers["content-type"] || "");
  const url = new URL(req.url || "/terminus", "http://x");
  const rawName = url.searchParams.get("filename") || String(req.headers["x-filename"] || "");
  if (!/multipart\/form-data/.test(ctype) && rawName) return handleRawUpload(req, res, deps, rawName);
  const body = await readBody(req, UPLOAD_MAX + 1024);
  if (!body) {
    console.log(`upload rejected: too large (>${UPLOAD_MAX / 1048576}MB cap)`);
    return json(res, 413, { error: `file too large (max ${UPLOAD_MAX / 1048576}MB)` });
  }

  let filename = url.searchParams.get("filename") || String(req.headers["x-filename"] || "");
  let data: Buffer | null = null;
  const uid = uploadIdOf(req);
  const prior = uploadSeenGet(uid);
  if (prior) return json(res, 200, { ...prior, dup: true });

  const mpart = ctype.match(/multipart\/form-data;\s*boundary=(.+)/);
  if (mpart) {
    const boundary = Buffer.from("--" + mpart[1].replace(/["']/g, ""));
    const endMark = Buffer.from("--" + mpart[1].replace(/["']/g, "") + "--");
    // find first file part: headers … filename="…" \r\n\r\n <bytes> \r\n--boundary
    const headerEnd = body.indexOf(Buffer.from("\r\n\r\n"));
    if (headerEnd === -1) return json(res, 400, { error: "bad multipart body" });
    const headerText = body.subarray(0, headerEnd).toString("latin1");
    const fn = headerText.match(/filename="([^"]*)"/)?.[1] || filename || "upload";
    filename = fn;
    const start = headerEnd + 4;
    let end = body.indexOf(boundary, start);
    if (end === -1) end = body.indexOf(endMark, start);
    if (end === -1) return json(res, 400, { error: "bad multipart body" });
    data = body.subarray(start, end - 2); // strip trailing CRLF
  } else {
    if (!filename) return json(res, 400, { error: "missing ?filename= (or send multipart form)" });
    data = body;
  }
  if (!data || !data.length) return json(res, 400, { error: "empty file" });
  if (data.length > UPLOAD_MAX) return json(res, 413, { error: "file too large (max 50MB)" });

  const { dayDir: today } = { dayDir };
  void today;
  const day = dayDir();
  const dir = join(MEDIA_DIR, day);
  await mkdir(dir, { recursive: true });
  const name = cleanName(filename);
  await atomicWriteFile(join(dir, name), data);
  const fileUrl = `${baseUrl}/terminus/${day}/${encodeURIComponent(name)}`;
  console.log(`upload ${join(dir, name)} (${data.length} bytes) → ${fileUrl}`);
  const result = { ok: true, url: fileUrl, name, size: data.length };
  uploadSeenSet(uid, result);
  return json(res, 200, result);
}

/** Basic chat-stream mirror: an unauthenticated debug shell whose API calls
 *  remain bearer-gated. The token lives only in page memory and is never
 *  stored; it replays the app's stream handling for verification. */
export function debugChatHtml(builtFrom = "unknown"): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">` +
  `<meta name="viewport" content="width=device-width,initial-scale=1">` +
  `<title>chat stream mirror</title>` +
  `<style>body{font-family:system-ui;margin:16px;max-width:920px}input,button{font:inherit;margin:4px;padding:6px}pre{background:#111;color:#eee;padding:12px;white-space:pre-wrap;max-height:52vh;overflow:auto}#stop{font-weight:700;padding:6px 10px;border:1px solid #999;display:inline-block;margin:8px 0}</style>` +
  `</head><body>` +
  `<h1>chat stream mirror</h1>` +
  `<p>Bearer token stays in page memory only. All chat/stream calls still require it. Mirror build: ${builtFrom}.</p>` +
  `<label>base <input id="base" size="34" value=""></label>` +
  `<label>bearer token <input id="token" type="password" autocomplete="off" size="24"></label>` +
  `<button id="forget" type="button">forget token</button><br>` +
  `<label>chat <input id="chat" size="30" value=""></label>` +
  `<button id="newchat" type="button">new dummy chat</button><br>` +
  `<label>message <input id="text" size="64" value="Reply with exactly STREAM-PROBE-OK"></label><br>` +
  `<button id="connect" type="button">connect</button>` +
  `<button id="disconnect" type="button">disconnect</button>` +
  `<button id="send" type="button">send probe</button>` +
  `<button id="resend" type="button">resend same turnId</button>` +
  `<button id="stop" type="button">stop turn</button>` +
  `<button id="progress" type="button">poll progress</button>` +
  `<button id="drop" type="button">drop next done: off</button>` +
  `<button id="clear" type="button">clear log</button>` +
  `<div id="stopstate">stop hidden</div><div id="state"></div>` +
  `<h2>rendered reply / turn timeline (diagnostic; server acceptance order)</h2><div id="thread"></div><h2>steps</h2><div id="steps"></div><h2>event log</h2><pre id="log"></pre>` +
  `<script>` +
  `let token="";let chat="";let ctl=null;let connected=false;` +
  `const S={pending:[],live:{},delivered:{},serverIdle:false,dropNextDone:false,threadLive:"",threadTurn:"",threadSettled:[],timeline:[],mirrorSteps:[],lastTurnId:"",settledMsg:{},sid:""};` +
  `function el(id){return document.getElementById(id)}` +
  `function log(kind,obj){const t=new Date().toISOString().slice(11,19);const body=obj===undefined?"":" "+JSON.stringify(obj).slice(0,500);const p=el("log");p.textContent+=t+" "+kind+body+"\\n";const lines=p.textContent.split("\\n");if(lines.length>400)p.textContent=lines.slice(-400).join("\\n");p.scrollTop=p.scrollHeight}` +
  `function base(){return el("base").value.trim().replace(/\\/$/,"")||location.origin}` +
  `function auth(){token=el("token").value.trim();if(!token){log("need-token");return false}return true}` +
  `function touch(){const n=Date.now();for(const k in S.live)S.live[k].last=n}` +
  `function stopActive(){if(S.serverIdle)return false;const n=Date.now();for(const k in S.live){if(n-S.live[k].last<90000)return true}return false}` +
  `function render(){el("stopstate").textContent=stopActive()?"STOP VISIBLE":"stop hidden";el("state").textContent="connected="+connected+" pending=["+S.pending.join(",")+"] live=["+Object.keys(S.live).join(",")+"] delivered="+Object.keys(S.delivered).length+" idle="+S.serverIdle;renderThread()}` +
  `function renderThread(){let t="";for(const row of S.timeline){t+="USER "+row.prompt+"\\n";t+=(row.state==="live"?"PENDING ":row.state==="pointer"?"POINTER ":"REPLY ")+String(row.turn).slice(-6)+":\\n"+(row.text||"")+"\\n\\n"}if(!S.timeline.length)t="(no turns)\\n";el("thread").textContent=t;el("steps").textContent=S.mirrorSteps.slice(-20).join("\\n")}` +
  `setInterval(render,1000);render();` +
  `el("forget").onclick=function(){token="";el("token").value="";log("token-forgotten")};` +
  `el("newchat").onclick=function(){chat="debug-"+Date.now().toString(36);el("chat").value=chat;S.pending=[];S.live={};S.delivered={};S.serverIdle=false;S.threadLive="";S.threadTurn="";S.threadSettled=[];S.timeline=[];S.mirrorSteps=[];S.settledMsg={};S.sid="";log("new-dummy-chat",{chat});render()};` +
  `el("drop").onclick=function(){S.dropNextDone=!S.dropNextDone;el("drop").textContent="drop next done: "+(S.dropNextDone?"on":"off");log("drop-next-done",{on:S.dropNextDone})};` +
  `el("clear").onclick=function(){el("log").textContent=""};` +
  `function onEvent(ev,payload){let obj=null;try{obj=payload?JSON.parse(payload):null}catch(e){obj={raw:String(payload||"").slice(0,300)}}` +
  `if(ev==="step"){touch();S.mirrorSteps.push(String((obj&&obj.kind)||"")+" "+String((obj&&obj.label)||""));log("step",obj);render();return}` +
  `if(ev==="delta"){const turn=(obj&&obj.turnId)||S.pending[S.pending.length-1]||"";if(!turn||!S.live[turn]){log("delta-orphan",{turn:turn||"(none)"});return}if(S.threadTurn!==turn){S.threadTurn=turn;S.threadLive=""}S.live[turn].text+=String((obj&&obj.delta)||"");S.threadLive=S.live[turn].text;S.live[turn].last=Date.now();log("delta",{turn:String(turn).slice(-6),chars:String((obj&&obj.delta)||"").length});render();return}` +
  `if(ev==="done"){const turn=(obj&&obj.turnId)||"";const mid=(obj&&obj.messageID)||"";if(S.dropNextDone){S.dropNextDone=false;el("drop").textContent="drop next done: off";log("simulated-lost-done",{turn});return}if(turn&&S.delivered[turn]){log("duplicate-done",{turn});return}if(turn)S.delivered[turn]=1;S.pending=S.pending.filter(function(x){return x!==turn});const row=S.timeline.find(function(x){return x.turn===turn});if(mid&&S.settledMsg[mid]){delete S.live[turn];if(row){row.state="pointer";row.text="answered together with the shared reply"}log("merged-pointer",{turn:turn.slice(-6)});render();return}if(mid)S.settledMsg[mid]=1;delete S.live[turn];if(row){row.state="done";row.text=String((obj&&obj.text)||"")}if(S.threadTurn===turn){S.threadTurn="";S.threadLive=""}log("done",{turn:String(turn).slice(-6),chars:String((obj&&obj.text)||"").length,error:(obj&&obj.error)||""});render();return}` +
  `if(ev==="idle"){S.serverIdle=true;log("idle",obj);render();return}` +
  `log(ev,obj)};` +
  `async function connect(){if(!auth())return;chat=el("chat").value.trim();if(!chat){log("need-chat");return}disconnect(false);ctl=new AbortController();try{const r=await fetch(base()+"/api/stream?chatId="+encodeURIComponent(chat)+"&sessionID="+encodeURIComponent(S.sid||""),{headers:{"Authorization":"Bearer "+token,"Accept":"text/event-stream"},signal:ctl.signal});if(!r.ok||!r.body){log("stream-http",{status:r.status});return}connected=true;log("connected",{chat});render();let buf="";let ev="";let data=[];const rd=r.body.getReader();const dec=new TextDecoder();for(;;){const part=await rd.read();if(part.done)break;buf+=dec.decode(part.value,{stream:true});let idx=0;while((idx=buf.indexOf("\\n"))>=0){let line=buf.slice(0,idx);buf=buf.slice(idx+1);if(line.charCodeAt(line.length-1)===13)line=line.slice(0,-1);if(!line){if(ev||data.length){onEvent(ev||"message",data.join("\\n"));ev="";data=[]}continue}if(line.charAt(0)===":"){log("heartbeat");continue}if(line.indexOf("event:")===0){ev=line.slice(6).trim();continue}if(line.indexOf("data:")===0){data.push(line.slice(5).replace(/^\\s/,""));continue}}}}catch(e){if(e&&e.name==="AbortError")log("disconnected");else log("stream-error",{message:String((e&&e.message)||e).slice(0,200)})}connected=false;render()};` +
  `function disconnect(say){if(ctl){try{ctl.abort()}catch(e){}}ctl=null;connected=false;if(say!==false)log("disconnected");render()};` +
  `el("connect").onclick=connect;el("disconnect").onclick=function(){disconnect(true)};` +
  `el("send").onclick=async function(){if(!auth())return;chat=el("chat").value.trim();if(!chat){log("need-chat");return}const text=el("text").value;const row={turn:"…",prompt:text,text:"",state:"posting"};S.timeline.push(row);render();try{const r=await fetch(base()+"/api/chat",{method:"POST",headers:{"Authorization":"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({chatId:chat,sessionID:S.sid||undefined,title:"debug mirror",text:text})});const j=await r.json().catch(function(){return{}});log("post-chat",{status:r.status,turnId:j.turnId||"",session:j.sessionID||"",error:j.error||""});if(r.ok&&j.turnId){row.turn=j.turnId;row.state="live";if(j.sessionID)S.sid=j.sessionID;S.pending.push(j.turnId);S.live[j.turnId]={started:Date.now(),last:Date.now(),text:""};S.threadTurn=j.turnId;S.threadLive="";S.lastTurnId=j.turnId;S.serverIdle=false;render();if(!connected)connect()}else{row.state="error";row.text=j.error||"request failed";render()}}catch(e){row.state="error";row.text=String((e&&e.message)||e).slice(0,200);log("send-error",{message:row.text});render()}};` +
  `el("stop").onclick=async function(){if(!auth())return;chat=el("chat").value.trim();if(!chat){log("need-chat");return}try{const r=await fetch(base()+"/api/stop",{method:"POST",headers:{"Authorization":"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({sessionID:S.sid})});if(r.ok){for(const t of S.pending){S.delivered[t]=1;delete S.live[t]}S.pending=[];S.threadLive="";S.threadTurn="";render()}log("post-stop",{status:r.status})}catch(e){log("stop-error",{message:String((e&&e.message)||e).slice(0,200)})}};` +
  `el("resend").onclick=async function(){if(!auth())return;chat=el("chat").value.trim();if(!chat){log("need-chat");return}if(!S.lastTurnId){log("no-last-turn");return}const text=el("text").value;try{const r=await fetch(base()+"/api/chat",{method:"POST",headers:{"Authorization":"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({chatId:chat,sessionID:S.sid||undefined,title:"debug mirror",text:text,turnId:S.lastTurnId})});const j=await r.json().catch(function(){return{}});log("post-resend-same-turn",{status:r.status,turnId:j.turnId||"",accepted:j.accepted||false,session:j.sessionID||"",error:j.error||""})}catch(e){log("resend-error",{message:String((e&&e.message)||e).slice(0,200)})}};` +
  `el("progress").onclick=async function(){if(!auth())return;chat=el("chat").value.trim();if(!chat){log("need-chat");return}try{const r=await fetch(base()+"/api/progress?chatId="+encodeURIComponent(chat)+"&sessionID="+encodeURIComponent(S.sid||""),{headers:{"Authorization":"Bearer "+token}});const j=await r.json().catch(function(){return{}});log("progress",{status:r.status,state:j.status||"",steps:(j.steps||[]).length,error:j.error||""})}catch(e){log("progress-error",{message:String((e&&e.message)||e).slice(0,200)})}};` +
  `</script></body></html>`;
}

/** List mirror: simulates the app's session-list merge (renderSessions)
 *  against the live server. Owned ids (legacy /api/bindings + rows adopted
 *  here) render as local chats; everything else as import rows. Per-row
 *  import/delete buttons walk the lifecycle: a delete must make
 *  its row vanish from BOTH sections (deleted, never orphaned). */
export function debugListHtml(builtFrom = "unknown"): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">` +
  `<meta name="viewport" content="width=device-width,initial-scale=1">` +
  `<title>session list mirror</title>` +
  `<style>body{font-family:system-ui;margin:16px;max-width:920px}input,button{font:inherit;margin:4px;padding:6px}pre{background:#111;color:#eee;padding:12px;white-space:pre-wrap;max-height:40vh;overflow:auto}table{border-collapse:collapse;width:100%}td,th{border:1px solid #999;padding:4px 8px;font-size:14px;text-align:left}.own{color:#060}.imp{color:#630}</style>` +
  `</head><body>` +
  `<h1>session list mirror</h1>` +
  `<p>Same merge rule as the app: owned or buried ids never show as imports. Mirror build: ${builtFrom}.</p>` +
  `<label>base <input id="base" size="34" value=""></label>` +
  `<label>bearer token <input id="token" type="password" autocomplete="off" size="24"></label>` +
  `<button id="forget" type="button">forget token</button><br>` +
  `<button id="load" type="button">load</button>` +
  `<button id="walk" type="button">walk lifecycle on a throwaway</button>` +
  `<button id="race" type="button">walk delete-race on a throwaway</button>` +
  `<button id="clear" type="button">clear log</button>` +
  `<h2>local chats (<span id="nloc">0</span>)</h2><table id="local"><tr><th>title</th><th>id</th><th></th></tr></table>` +
  `<h2>imports (<span id="nimp">0</span>)</h2><table id="imports"><tr><th>title</th><th>id</th><th></th></tr></table>` +
  `<h2>log</h2><pre id="log"></pre>` +
  `<script>` +
  `const L={sessions:[],owned:{},buried:{}};` +
  `function el(id){return document.getElementById(id)}` +
  `function log(m,o){const t=new Date().toISOString().slice(11,19);el("log").textContent+=t+" "+m+(o===undefined?"":" "+JSON.stringify(o).slice(0,300))+"\\n"}` +
  `function base(){return el("base").value.trim().replace(/\\/$/,"")||location.origin}` +
  `function auth(){const t=el("token").value.trim();if(!t){log("need-token");return""}return t}` +
  `async function api(path,opts){opts=opts||{};opts.headers=Object.assign({"Authorization":"Bearer "+auth()},opts.headers||{});const r=await fetch(base()+path,opts);let j=null;try{j=await r.json()}catch(e){}return{status:r.status,body:j}}` +
  `function age(ts){const h=(Date.now()-ts)/36e5;return h<1?Math.round(h*60)+"m":h<48?h.toFixed(1)+"h":Math.round(h/24)+"d"}` +
  `function rowHtml(s,owned){const t=String(s.title||"(untitled)").slice(0,40);const id=s.id;const when=age(s.updatedAt||0);const btns=owned?("<button data-a='delete' data-id='"+id+"'>delete</button>"):("<button data-a='import' data-id='"+id+"'>import</button><button data-a='delete' data-id='"+id+"'>delete</button>");return "<tr><td>"+t+" <small>("+when+")</small></td><td><small>"+id.slice(0,14)+"</small></td><td>"+btns+"</td></tr>"}` +
  `function render(){const loc=L.sessions.filter(function(s){return L.owned[s.id]});const imp=L.sessions.filter(function(s){return !L.owned[s.id]&&!L.buried[s.id]}).sort(function(a,b){return (b.updatedAt||0)-(a.updatedAt||0)});el("nloc").textContent=loc.length;el("nimp").textContent=imp.length;el("local").innerHTML="<tr><th>title</th><th>id</th><th></th></tr>"+loc.map(function(s){return rowHtml(s,true)}).join("");el("imports").innerHTML="<tr><th>title</th><th>id</th><th></th></tr>"+imp.map(function(s){return rowHtml(s,false)}).join("")}` +
  `async function load(){const t=auth();if(!t)return;try{const s=await api("/api/sessions");if(s.status!==200){log("sessions-http",{status:s.status});return}L.sessions=(s.body&&s.body.sessions)||[];const b=await api("/api/bindings");const m=(b.body&&b.body.bindings)||{};for(const k in m){const v=m[k];if(v)L.owned[v]=v}render();log("loaded",{sessions:L.sessions.length,owned:Object.keys(L.owned).length})}catch(e){log("load-error",{message:String(e).slice(0,200)})}}` +
  `async function act(a,id){const t=auth();if(!t)return;try{if(a==="import"){L.owned[id]=id;delete L.buried[id];render();log("import-adopted",{id:id.slice(0,14)})}else if(a==="delete"){const r=await api("/api/session-delete",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({sessionID:id})});delete L.owned[id];L.buried[id]=1;await load();log("deleted",{id:id.slice(0,14),status:r.status})}}catch(e){log("act-error",{message:String(e).slice(0,200)})}}` +
  `document.addEventListener("click",function(e){const b=e.target&&e.target.dataset;if(b&&b.a)act(b.a,b.id)});` +
  `el("forget").onclick=function(){el("token").value="";log("token-forgotten")};` +
  `el("load").onclick=load;` +
  `el("clear").onclick=function(){el("log").textContent=""};` +
  `el("walk").onclick=async function(){const t=auth();if(!t)return;try{const p=await api("/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({chatId:"list-walk-"+Date.now().toString(36),title:"list walk",text:"Reply with exactly LIST-WALK-OK"})});const A=p.body&&p.body.sessionID;if(!A){log("FAIL walk-create",{status:p.status});return}L.owned[A]=A;await load();log("walk-created",{id:String(A).slice(0,14)});const r=await api("/api/session-delete",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({sessionID:A})});delete L.owned[A];await load();const gone=!L.sessions.some(function(s){return s.id===A});log((gone?"PASS":"FAIL")+" walk-delete-no-orphan",{status:r.status});const g=await api("/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({chatId:"list-walk-ghost",sessionID:A,text:"x"})});log((g.status===404?"PASS":"FAIL")+" walk-dead-404s",{status:g.status})}catch(e){log("walk-error",{message:String(e).slice(0,200)})}};` +
  `el("race").onclick=async function(){const t=auth();if(!t)return;try{const p=await api("/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({chatId:"list-race-"+Date.now().toString(36),title:"list race",text:"Reply with exactly LIST-RACE-OK"})});const A=p.body&&p.body.sessionID;if(!A){log("FAIL race-create",{status:p.status});return}await load();const listed=L.sessions.some(function(s){return s.id===A});log((listed?"PASS":"FAIL")+" race-throwaway-listed",{});L.buried[A]=1;const dp=api("/api/session-delete",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({sessionID:A})});await load();const hidden=!L.sessions.filter(function(s){return !L.owned[s.id]&&!L.buried[s.id]}).some(function(s){return s.id===A});log((hidden?"PASS":"FAIL")+" race-hidden-during-delete",{});const d=await dp;await load();const gone=!L.sessions.some(function(s){return s.id===A});log((gone?"PASS":"FAIL")+" race-gone-after-delete",{status:d.status});delete L.buried[A]}catch(e){log("race-error",{message:String(e).slice(0,200)})}};` +
  `</script></body></html>`;
}

/** Route /terminus* requests. Returns true when the request was handled. */
export async function handleMedia(
  req: IncomingMessage, res: ServerResponse, reqPath: string, deps: Deps,
): Promise<boolean> {
  const { json, baseUrl } = deps;
  if (reqPath === "/terminus-config" && req.method === "POST") {
    let data = "";
    for await (const chunk of req) data += chunk;
    let payload: { open?: boolean; minutes?: number } = {};
    try { payload = JSON.parse(data || "{}"); } catch { return json(res, 400, { error: "invalid json" }), true; }
    return json(res, 200, { ok: true, uploads: setUploads(!!payload.open, Number(payload.minutes) || 0) }), true;
  }
  if (reqPath === "/terminus-config" && req.method === "GET") {
    return json(res, 200, { ok: true, uploadsOpen }), true;
  }
  if (await handleSysmon(req, res, reqPath, json)) return true;
  if (reqPath === "/api/schedule-rows" && req.method === "GET") {
    return json(res, 200, { rows: scheduleRows() }), true;
  }
  if ((reqPath === "/ops" || reqPath === "/ops/") && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(opsHtml());
    return true;
  }
  if (reqPath === "/echo" && req.method === "POST") {
    await handleEcho(req, res, deps);
    return true;
  }
  if ((reqPath === "/notes" || reqPath === "/notes/") && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(notesHtml(new URL(req.url || "", "http://x").searchParams.get("all") === "1"));
    return true;
  }
  if ((reqPath === "/skills" || reqPath === "/skills/") && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(skillsHtml());
    return true;
  }
  if (reqPath === "/notes-profile" && req.method === "GET") {
    try {
      const profile = readFileSync(join(repoRoot(), "notebook/user.md"), "utf8");
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify({ html: profileMarkdownHtml(profile) }));
    } catch {
      json(res, 404, { error: "user details unavailable" });
    }
    return true;
  }
  if (reqPath === "/notes-version" && req.method === "GET") {
    let mtime = 0;
    try {
      const { readdirSync, statSync } = await import("node:fs");
      const nbDir = join(repoRoot(), "notebook");
      for (const f of readdirSync(nbDir)) {
        if (!/^\d{4}-W\d{2}\.md$/.test(f)) continue;
        const st = statSync(join(nbDir, f));
        if (st.mtimeMs > mtime) mtime = st.mtimeMs;
      }
    } catch { /* ignore */ }
    return json(res, 200, { ok: true, mtime }), true;
  }
  if (reqPath === "/terminus" && req.method === "POST") {
    await handleUpload(req, res, deps);
    return true;
  }
  if (reqPath === "/terminus" && req.method === "GET") {
    await handleList(req, res, deps);
    return true;
  }
  if (reqPath.startsWith("/terminus/") && req.method === "GET") {
    // Decode percent-encoded names (spaces, +, unicode) before filesystem lookup.
    const rel = decodeURIComponent(reqPath.replace("/terminus/", ""));
    const out = handleFile(req, res, rel) as unknown;
    if (out && typeof out === "object" && "status" in (out as object)) {
      const o = out as { status: number; hint?: string };
      return void json(res, o.status, o.hint ? { error: "not found", hint: o.hint } : { error: "not found" }), true;
    }
    return true;
  }
  if (reqPath.startsWith("/terminus/") && req.method === "DELETE") {
    const rel = decodeURIComponent(reqPath.replace("/terminus/", ""));
    const parts = safeParts(rel);
    if (!parts) {
      json(res, 404, { error: "not found" });
      return true;
    }
    const path = join(MEDIA_DIR, ...parts);
    try {
      const s = statSync(path);
      if (s.isDirectory()) {
        json(res, 404, { error: "not found" });
        return true;
      }
      const { rm } = await import("node:fs/promises");
      await rm(path);
      json(res, 200, { ok: true });
      return true;
    } catch {
      json(res, 404, { error: "not found" });
      return true;
    }
  }
  return false;
}
