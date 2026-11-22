/** Agentbook server side: hidden prompt lines that keep a chat's working memory (skills/agentbook) current.
 *  The book itself is read and written only by that skill's scripts; this decides when to hand it back or nudge. */
import { readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";

import { repoRoot } from "./paths.js";

export const AGENTBOOK_DIR = process.env.AGENTBOOK_HOME || join(repoRoot(), "agentbook");
const SCRIPTS = "skills/agentbook";
const NEAR_FRACTION = 0.8;
const STALE_TURNS = 10;
const STALE_GROWTH = 15_000;
const BOOK_MAX_AGE_MS = 60 * 86_400_000;
const NOTE_RE = /^<agentbook>[\s\S]*?<\/agentbook>\s*/;

/** Always in the system prompt: tells the agent the book exists and how to use it. */
export const AGENTBOOK_HINT =
  `Agentbook: this chat has a small working-memory book that survives compaction. Read it at the start and after any compaction: python3 ${SCRIPTS}/read.py. Update it with write.py (set|add|del|touch) when a decision, constraint, open item or reusable reference appears, and when you finish meaningful work (record the outcome, close its open item). Worth writing only if you would be lost or repeat work after a compaction without it. Never every turn; see the agentbook skill. Never write secrets or personal identifiers into it. If a <agentbook> note says it is stale or compaction is near, update it first.`;

interface ChatState {
  afterCompaction: boolean;
  nearNudged: boolean;
  staleNudged: boolean;
  turns: number;
  usedAtReset: number;
  seenMtime: number;
}
const states = new Map<string, ChatState>();

function stateOf(chatId: string): ChatState {
  let s = states.get(chatId);
  if (!s) {
    s = { afterCompaction: false, nearNudged: false, staleNudged: false, turns: 0, usedAtReset: 0, seenMtime: 0 };
    states.set(chatId, s);
    if (states.size > 500) states.delete(states.keys().next().value as string);
  }
  return s;
}

function bookFile(backend: string, chatId: string): string {
  return join(AGENTBOOK_DIR, backend, `${chatId}.md`);
}

/** Compaction ended: hand the book back on the next turn and allow a fresh near-ceiling nudge. */
export function noteCompacted(chatId: string): void {
  const s = stateOf(chatId);
  s.afterCompaction = true;
  s.nearNudged = false;
  s.staleNudged = false;
}

/** Delete books nobody has written in 60 days (a chat that went quiet). Once per process, on first use. */
export function sweepOldBooks(now = Date.now()): number {
  let gone = 0;
  try {
    for (const backend of readdirSync(AGENTBOOK_DIR)) {
      let names: string[];
      try { names = readdirSync(join(AGENTBOOK_DIR, backend)); } catch { continue; }
      for (const n of names) {
        const f = join(AGENTBOOK_DIR, backend, n);
        try {
          if (n.endsWith(".md") && now - statSync(f).mtimeMs > BOOK_MAX_AGE_MS) { rmSync(f, { force: true }); gone++; }
        } catch { /* vanished */ }
      }
    }
  } catch { /* no store yet */ }
  return gone;
}
let swept = false;

/** Drop the book with its chat. */
export function forgetBook(backend: string, chatId: string): void {
  states.delete(chatId);
  try { rmSync(bookFile(backend, chatId), { force: true }); } catch { /* absent */ }
}

/** Hidden lines to put before the user's text for this turn ("" = nothing). Call once per started turn. */
export function agentbookPrefix(backend: string, chatId: string, used: number, compactAt: number): string {
  if (!swept) { swept = true; sweepOldBooks(); }
  const s = stateOf(chatId);
  let text = "";
  let mtime = 0;
  try {
    mtime = statSync(bookFile(backend, chatId)).mtimeMs;
    text = readFileSync(bookFile(backend, chatId), "utf8").trim();
  } catch { /* no book yet */ }
  if (mtime !== s.seenMtime) {
    s.seenMtime = mtime;
    s.turns = 0;
    s.usedAtReset = used;
    s.staleNudged = false;
  } else {
    s.turns += 1;
  }
  const lines: string[] = [];
  if (s.afterCompaction) {
    s.afterCompaction = false;
    lines.push(text ? `Context was just compacted. Your agentbook:\n${text}` : "Context was just compacted. Agentbook is empty; record what matters now.");
  }
  if (compactAt > 0 && used >= compactAt * NEAR_FRACTION && !s.nearNudged) {
    s.nearNudged = true;
    lines.push(`Compaction is near (${used}/${compactAt} tokens). Update agentbook now, before anything else.`);
  } else if (s.turns >= STALE_TURNS && used - s.usedAtReset >= STALE_GROWTH && !s.staleNudged) {
    s.staleNudged = true;
    lines.push("Agentbook is stale. Update it if anything worth keeping happened.");
  }
  return lines.length ? `<agentbook>\n${lines.join("\n")}\n</agentbook>\n\n` : "";
}

/** History rows must show what the user typed, not the hidden block. */
export function stripAgentbook(text: string): string {
  return text.replace(NOTE_RE, "");
}
