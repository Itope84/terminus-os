/** Slash commands and skills Claude Code itself can run, listed so the phone can autocomplete them.
 *  Sources (project wins over user): <cwd>/.claude/commands/*.md, <cwd>/.claude/skills/<name>/SKILL.md,
 *  then the same two under the user's Claude config dir. Only the name and a one-line description leave
 *  the server; the file body never does. Typing `/name args` sends it to claude as a normal prompt. */
import { readdirSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { CommandOption } from "./types.js";

const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const MAX_COMMANDS = 100;

function unquote(s: string): string {
  const t = s.trim();
  return (t.length > 1 && /^(['"]).*\1$/.test(t) ? t.slice(1, -1) : t).replace(/\s+/g, " ").trim();
}

/** `description:` from YAML front matter (a folded `>` / `|` value takes its first indented line), else the
 *  first non-empty body line, else the fallback. */
export function claudeCommandDescription(text: string, fallback: string): string {
  const fm = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(text);
  if (fm) {
    const lines = fm[1].split(/\r?\n/);
    const i = lines.findIndex((l) => /^description\s*:/i.test(l));
    if (i >= 0) {
      const v = lines[i].replace(/^description\s*:\s*/i, "");
      if (/^[>|][+-]?$/.test(v.trim())) {
        const next = lines.slice(i + 1).find((l) => /^\s+\S/.test(l));
        if (next) return unquote(next).slice(0, 160) || fallback;
      } else if (v.trim()) {
        return unquote(v).slice(0, 160) || fallback;
      }
    }
  }
  const body = fm ? text.slice(fm[0].length) : text;
  const first = body.split(/\r?\n/).map((l) => l.replace(/^#+\s*/, "").trim()).find(Boolean);
  return first ? first.slice(0, 160) : fallback;
}

function readHead(file: string): string {
  try { return readFileSync(file, "utf8").slice(0, 4096); } catch { return ""; }
}

function fromRoot(root: string): CommandOption[] {
  const out: CommandOption[] = [];
  try {
    for (const f of readdirSync(join(root, "commands")).sort()) {
      if (!f.toLowerCase().endsWith(".md")) continue;
      const name = f.slice(0, -3);
      if (!NAME.test(name)) continue;
      out.push({ name: `/${name}`, description: claudeCommandDescription(readHead(join(root, "commands", f)), "Claude command"), available: true });
    }
  } catch { /* no commands dir */ }
  try {
    for (const d of readdirSync(join(root, "skills")).sort()) {
      if (!NAME.test(d)) continue;
      const head = readHead(join(root, "skills", d, "SKILL.md"));
      if (!head) continue;
      out.push({ name: `/${d}`, description: claudeCommandDescription(head, "Claude skill"), available: true });
    }
  } catch { /* no skills dir */ }
  return out;
}

const BUILTIN_NOTES: Record<string, string> = {
  compact: "Summarise the conversation to free up context",
  context: "Show how the context window is being used",
};
/** `clear` would orphan the chat's session, so it is never offered from the phone. */
const HIDDEN = new Set(["clear"]);

/** `initNames` = the `slash_commands` Claude itself reported in its last system/init: exactly the commands and
 *  skills that run without a terminal. When present it decides the list and the folders only supply
 *  descriptions; before any turn has run the folders alone are listed. */
export function claudeCommandList(
  cwd: string,
  home: string = homedir(),
  configDir: string | undefined = process.env.CLAUDE_CONFIG_DIR,
  initNames: string[] = [],
): CommandOption[] {
  const userRoot = configDir?.trim() || join(home, ".claude");
  const disk = [...fromRoot(join(cwd, ".claude")), ...fromRoot(userRoot)];
  if (initNames.length) {
    const desc = new Map<string, string>();
    for (const c of disk) if (!desc.has(c.name.toLowerCase())) desc.set(c.name.toLowerCase(), c.description);
    const out: CommandOption[] = [];
    for (const n of initNames) {
      if (HIDDEN.has(n.toLowerCase())) continue;
      out.push({ name: `/${n}`, description: desc.get(`/${n}`.toLowerCase()) ?? BUILTIN_NOTES[n.toLowerCase()] ?? "Claude command", available: true });
      if (out.length >= MAX_COMMANDS) break;
    }
    return out;
  }
  const seen = new Set<string>();
  const out: CommandOption[] = [];
  for (const c of disk) {
    const key = c.name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c);
    if (out.length >= MAX_COMMANDS) break;
  }
  return out;
}

/** Terminus skills (`<dir>/<name>/SKILL.md`) as slash entries for backends with no native skill commands
 *  (codex, opencode, agy). Hidden ones (`user-invocable: false`) are left out. */
export function skillCommands(dir: string): CommandOption[] {
  const out: CommandOption[] = [];
  try {
    for (const d of readdirSync(dir).sort()) {
      if (!NAME.test(d)) continue;
      const head = readHead(join(dir, d, "SKILL.md"));
      if (!head || /^user-invocable:\s*false\s*$/m.test(head)) continue;
      out.push({ name: `/${d}`, description: claudeCommandDescription(head, "Terminus skill"), available: true, kind: "skill" });
      if (out.length >= MAX_COMMANDS) break;
    }
  } catch { /* no skills dir */ }
  return out;
}
