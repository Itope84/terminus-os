/** Pure helpers for Claude sessions kept on disk: fork a transcript, read or override a title, judge a
 *  manual compact run. No process or network here, so every piece is unit-tested without the CLI. */
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

/** Copy of a transcript under a new session id: every top-level `sessionId` that named the source now names
 *  the copy. Lines that are not JSON objects pass through untouched; a torn last line is dropped. */
export function claudeForkTranscript(raw: string, from: string, to: string): string {
  const lines = raw.split("\n");
  if (lines.length && lines[lines.length - 1] !== "") lines.pop(); // torn tail from a live write
  const out: string[] = [];
  for (const line of lines) {
    if (!line) { out.push(line); continue; }
    try {
      const obj = JSON.parse(line) as unknown;
      if (obj && typeof obj === "object" && !Array.isArray(obj) && (obj as Record<string, unknown>).sessionId === from) {
        out.push(JSON.stringify({ ...(obj as Record<string, unknown>), sessionId: to }));
        continue;
      }
    } catch { /* not JSON: keep as is */ }
    out.push(line);
  }
  return out.join("\n") + (out.length && out[out.length - 1] !== "" ? "\n" : "");
}

/** A title Claude Code itself stored in the transcript: a user rename wins, then an AI title, then a summary.
 *  "" when none (the app keeps its own title). The key names are read defensively; unknown shapes yield "". */
export function claudeSessionTitle(raw: string): string {
  let custom = "";
  let ai = "";
  let summary = "";
  for (const line of raw.split("\n")) {
    if (!line.includes("title") && !line.includes("Title") && !line.includes("summary")) continue;
    let obj: Record<string, unknown>;
    try { obj = JSON.parse(line) as Record<string, unknown>; } catch { continue; }
    if (!obj || typeof obj !== "object") continue;
    const s = (v: unknown): string => (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, 200) : "");
    if (obj.type === "custom-title" && s(obj.customTitle)) custom = s(obj.customTitle);
    else if (obj.type === "ai-title" && s(obj.aiTitle)) ai = s(obj.aiTitle);
    else if (obj.type === "summary" && s(obj.summary)) summary = s(obj.summary);
  }
  return custom || ai || summary;
}

/** Titles set from the phone live beside Terminus's other state, never inside Claude's own files. */
export function claudeTitlesFile(home: string = homedir()): string {
  return join(home, ".config", "terminus", "claude-titles.json");
}

const TITLE_CAP = 2000;

export function readClaudeTitles(file: string = claudeTitlesFile()): Record<string, string> {
  try {
    const o = JSON.parse(readFileSync(file, "utf8")) as unknown;
    if (!o || typeof o !== "object" || Array.isArray(o)) return {};
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(o as Record<string, unknown>)) if (typeof v === "string" && v) out[k] = v;
    return out;
  } catch { return {}; }
}

export function setClaudeTitle(id: string, title: string, file: string = claudeTitlesFile()): void {
  const all = readClaudeTitles(file);
  const t = title.replace(/\s+/g, " ").trim().slice(0, 200);
  if (t) all[id] = t; else delete all[id];
  const keys = Object.keys(all);
  for (const k of keys.slice(0, Math.max(0, keys.length - TITLE_CAP))) delete all[k]; // oldest first
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  writeFileSync(tmp, JSON.stringify(all));
  renameSync(tmp, file);
}

/** Did a manual `/compact` run actually compact? Needs a clean exit, no error result and Claude's own
 *  compact_boundary marker. Per Claude's docs a run with too little history still ends "success" with no marker
 *  and a note such as "Not enough messages to compact.": that note becomes the reason, never a false success. */
export function claudeCompactOutcome(stdout: string, code: number | null): { ok: boolean; reason: string } {
  if (code !== 0) return { ok: false, reason: `claude exited code=${code}` };
  let marked = false;
  let errored = false;
  let note = "";
  for (const line of stdout.split("\n")) {
    if (!line.includes("compact_boundary") && !line.includes('"result"')) continue;
    try {
      const o = JSON.parse(line) as Record<string, unknown>;
      if (o.type === "system" && o.subtype === "compact_boundary") marked = true;
      if (o.type === "result") {
        if (o.is_error === true) errored = true;
        if (typeof o.result === "string") note = o.result.replace(/\s+/g, " ").trim().slice(0, 120);
      }
    } catch { /* partial line */ }
  }
  if (errored) return { ok: false, reason: note || "claude reported an error" };
  if (!marked) return { ok: false, reason: note || "nothing was compacted" };
  return { ok: true, reason: "" };
}

/** Command names from a system/init line's `slash_commands` (the ones that work without a terminal), slash
 *  stripped, odd names dropped. Empty when the line has none. */
export function claudeInitSlashCommands(line: Record<string, unknown>): string[] {
  const raw = line.slash_commands;
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  for (const v of raw) {
    if (typeof v !== "string") continue;
    const n = v.replace(/^\//, "");
    if (/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(n) && !out.includes(n)) out.push(n);
    if (out.length >= 200) break;
  }
  return out;
}

/** session_id of the first system/init line in a stream-json capture ("" if none). */
export function claudeInitSessionId(stdout: string): string {
  for (const line of stdout.split("\n")) {
    if (!line.includes('"init"')) continue;
    try {
      const o = JSON.parse(line) as Record<string, unknown>;
      if (o.type === "system" && o.subtype === "init" && typeof o.session_id === "string") return o.session_id;
    } catch { /* partial line */ }
  }
  return "";
}
