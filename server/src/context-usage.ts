/** Per-chat context size, token totals and cost, fed by each backend's own usage figures. In memory only:
 *  a server restart clears claude/codex totals (opencode totals are re-read from its messages). */

export type UsageBackend = "opencode" | "claude" | "codex" | "agy";

export interface ContextUsage {
  backend: UsageBackend;
  model: string | null;
  /** Tokens in the context after the newest request. */
  used: number;
  /** The model's context window, when the backend reports it. */
  window: number | null;
  /** The newest request's counters. */
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  totalTokens: number;
  costUsd: number | null;
  turns: number;
  at: number;
}

type Rec = Record<string, unknown>;
const usage = new Map<string, ContextUsage>();
const rowsByMessage = new Map<string, Rec>();

const rec = (v: unknown): Rec => (v && typeof v === "object" && !Array.isArray(v) ? (v as Rec) : {});
const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v > 0 ? v : 0);

export function getUsage(chatId: string): ContextUsage | undefined {
  return usage.get(chatId);
}

/** Everything a request read plus what it wrote: the context the next request starts from. */
export function contextAfter(p: { input?: number; output?: number; cacheRead?: number; cacheWrite?: number }): number {
  return num(p.input) + num(p.output) + num(p.cacheRead) + num(p.cacheWrite);
}

function slot(chatId: string, backend: UsageBackend): ContextUsage {
  const have = usage.get(chatId);
  if (have && have.backend === backend) return have;
  const fresh: ContextUsage = {
    backend, model: null, used: 0, window: null, input: 0, output: 0, cacheRead: 0, cacheWrite: 0,
    totalTokens: 0, costUsd: null, turns: 0, at: Date.now(),
  };
  usage.set(chatId, fresh);
  return fresh;
}

/** One stream-json line from a claude turn. */
export function recordClaudeLine(chatId: string, line: Rec): void {
  if (line.type === "assistant") {
    if (line.parent_tool_use_id) return; // a sub-agent's request, not this chat's context
    const msg = rec(line.message);
    const u = rec(msg.usage);
    if (!Object.keys(u).length) return;
    const c = slot(chatId, "claude");
    c.input = num(u.input_tokens);
    c.output = num(u.output_tokens);
    c.cacheRead = num(u.cache_read_input_tokens);
    c.cacheWrite = num(u.cache_creation_input_tokens);
    c.used = contextAfter(c);
    if (typeof msg.model === "string") c.model = msg.model;
    c.at = Date.now();
  } else if (line.type === "result") {
    const c = slot(chatId, "claude");
    const u = rec(line.usage); // sums every request of the turn: a total, never the context size
    c.totalTokens += num(u.input_tokens) + num(u.output_tokens) + num(u.cache_read_input_tokens) + num(u.cache_creation_input_tokens);
    if (typeof line.total_cost_usd === "number" && line.total_cost_usd >= 0) c.costUsd = (c.costUsd ?? 0) + line.total_cost_usd;
    const byModel = Object.entries(rec(line.modelUsage));
    const mine = byModel.find(([k]) => c.model && (k === c.model || c.model.startsWith(k))) ?? byModel[0];
    const w = mine ? num(rec(mine[1]).contextWindow) : 0;
    if (w > 0) c.window = w;
    c.turns += 1;
    c.at = Date.now();
  }
}

/** One agy stream-json line: a finished model step carries that request's usage (the context size); the
 *  result sums the turn. */
export function recordAgyLine(chatId: string, line: Rec): void {
  if (line.event === "step_update") {
    const st = rec(line.step_update);
    const u = rec(st.usage);
    if (!Object.keys(u).length) return;
    const c = slot(chatId, "agy");
    c.input = Math.max(0, num(u.input_tokens) - num(u.cache_read_tokens));
    c.output = num(u.output_tokens) + num(u.thinking_tokens);
    c.cacheRead = num(u.cache_read_tokens);
    c.cacheWrite = 0;
    c.used = contextAfter(c);
    c.at = Date.now();
  } else if (line.event === "result") {
    const u = rec(rec(line.result).usage);
    const c = slot(chatId, "agy");
    c.totalTokens += num(u.total_tokens) || num(u.input_tokens) + num(u.output_tokens);
    c.turns += 1;
    c.at = Date.now();
  }
}

/** params of a codex thread/tokenUsage/updated notification. */
export function recordCodexUsage(chatId: string, params: Rec): void {
  const tu = rec(params.tokenUsage);
  const last = rec(tu.last);
  const c = slot(chatId, "codex");
  const cached = num(last.cachedInputTokens);
  c.cacheRead = cached;
  c.cacheWrite = 0;
  c.input = Math.max(0, num(last.inputTokens) - cached); // codex counts cached tokens inside input
  c.output = num(last.outputTokens);
  c.used = num(last.totalTokens) || contextAfter(c);
  const total = num(rec(tu.total).totalTokens);
  if (total > 0) c.totalTokens = total;
  const w = num(tu.modelContextWindow);
  if (w > 0) c.window = w;
  c.at = Date.now();
}

/** Remember a finished opencode assistant row until its turn result names the chat. */
export function noteRow(messageID: string | undefined, row: Rec): void {
  if (!messageID) return;
  rowsByMessage.set(messageID, row);
  if (rowsByMessage.size > 200) rowsByMessage.delete(rowsByMessage.keys().next().value as string);
}

export function recordOpencodeTurn(chatId: string, messageID: string | undefined): void {
  const row = messageID ? rowsByMessage.get(messageID) : undefined;
  if (!row || !messageID) return;
  rowsByMessage.delete(messageID);
  const t = rec(row.tokens);
  const cache = rec(t.cache);
  const c = slot(chatId, "opencode");
  c.input = num(t.input);
  c.output = num(t.output);
  c.cacheRead = num(cache.read);
  c.cacheWrite = num(cache.write);
  c.used = contextAfter(c);
  c.at = Date.now();
}

/** Totals over a session's assistant rows (opencode keeps them per message). */
export function sumRows(rows: Rec[]): { tokens: number; cost: number | null; turns: number } {
  let tokens = 0;
  let cost = 0;
  let turns = 0;
  for (const row of rows) {
    const t = rec(row.tokens);
    if (!Object.keys(t).length) continue;
    const cache = rec(t.cache);
    tokens += num(t.input) + num(t.output) + num(t.reasoning) + num(cache.read) + num(cache.write);
    cost += num(row.cost);
    turns += 1;
  }
  return { tokens, cost: cost > 0 ? cost : null, turns };
}

export function setOpencodeTotals(chatId: string, rows: Rec[]): void {
  const s = sumRows(rows);
  const c = slot(chatId, "opencode");
  c.totalTokens = s.tokens;
  c.costUsd = s.cost;
  c.turns = s.turns;
}
