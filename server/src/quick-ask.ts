/** One question, one answer, on a given backend + model + effort: no tools, no history, nothing saved.
 *  Used where the server needs a short judgement from the same model a chat runs on (goal checks), without
 *  touching that chat's session. Every path is read-only by construction and killed by the caller's signal.
 *  Returns the reply text, or null when the backend can't answer. */
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { findBin } from "./backends/bin.js";

export type AskTarget = { backend: string; model?: string; effort?: string };

const MAX_OUT = 64 * 1024;

/** argv for each CLI backend (pure, unit-tested); null = not a CLI backend (opencode has its own path). */
export function askArgv(t: AskTarget, outFile = ""): { bin: string; args: string[]; stdin: boolean } | null {
  const model = (t.model || "").trim();
  const effort = ["low", "medium", "high"].includes(t.effort || "") ? t.effort! : "";
  switch (t.backend) {
    case "claude":
      return {
        bin: "claude",
        args: ["-p", "--output-format", "text", "--no-session-persistence", "--tools", "", "--strict-mcp-config",
          ...(model ? ["--model", model] : []), ...(effort ? ["--effort", effort] : [])],
        stdin: true,
      };
    case "codex":
      return {
        bin: "codex",
        args: ["exec", "--ephemeral", "--skip-git-repo-check", "-s", "read-only", "--color", "never",
          ...(model ? ["-m", model] : []), ...(effort ? ["-c", `model_reasoning_effort="${effort}"`] : []),
          ...(outFile ? ["-o", outFile] : []), "-"],
        stdin: true,
      };
    case "gemini":
      return { bin: "gemini", args: [...(model ? ["-m", model] : []), "-p", ""], stdin: true };
    case "agy":
      // Plan mode is read-only; the prompt is appended by the caller as -p's value.
      return { bin: "agy", args: ["--mode", "plan", "--disable-slash-commands", ...(model ? ["--model", model] : []), "-p"], stdin: false };
    default:
      return null;
  }
}

function runCli(t: AskTarget, prompt: string, signal?: AbortSignal): Promise<string | null> {
  const dir = t.backend === "codex" ? mkdtempSync(join(tmpdir(), "ask-")) : "";
  const outFile = dir ? join(dir, "last.txt") : "";
  const spec = askArgv(t, outFile);
  const bin = spec && findBin(spec.bin, [join(homedir(), ".local", "bin")]);
  if (!spec || !bin) return Promise.resolve(null);
  const args = spec.stdin ? spec.args : [...spec.args, prompt];
  return new Promise((resolve) => {
    let out = "";
    let done = false;
    const finish = (v: string | null) => {
      if (done) return;
      done = true;
      if (dir) try { rmSync(dir, { recursive: true, force: true }); } catch { /* tmp */ }
      resolve(v);
    };
    // A neutral working dir: nothing project-shaped for the CLI to pick up.
    const p = spawn(bin, args, { cwd: tmpdir(), stdio: ["pipe", "pipe", "ignore"], signal });
    p.stdout.on("data", (d: Buffer) => { if (out.length < MAX_OUT) out += d.toString("utf8"); });
    p.on("error", () => finish(null));
    p.on("close", (code) => {
      if (code !== 0) return finish(null);
      if (outFile) {
        try { return finish(readFileSync(outFile, "utf8")); } catch { /* fall through to stdout */ }
      }
      finish(out);
    });
    p.stdin.on("error", () => { /* exited early */ });
    p.stdin.end(spec.stdin ? prompt : "");
  });
}

/** opencode: a transient session on the given model, deleted as soon as it answers. */
async function runOpencode(t: AskTarget, prompt: string, signal?: AbortSignal): Promise<string | null> {
  try {
    const { OpenCode } = await import("@opencode/client");
    const base = process.env.OPENCODE_BASE_URL || "http://127.0.0.1:8093";
    const pass = process.env.OPENCODE_PASS ?? process.env.OPENCODE_SERVER_PASSWORD ?? "";
    const headers = pass ? { Authorization: `Basic ${Buffer.from(`opencode:${pass}`).toString("base64")}` } : undefined;
    const client = OpenCode.make({ baseUrl: base, headers });
    const m = (t.model || "").trim();
    const slash = m.indexOf("/");
    const modelRef = slash > 0 && slash < m.length - 1 ? { providerID: m.slice(0, slash), id: m.slice(slash + 1) } : undefined;
    const created = (await client.session.create({ title: "check (transient)", ...(modelRef ? { model: modelRef } : {}) } as never)) as { id: string };
    const sid = created?.id;
    if (!sid) return null;
    const opt = signal ? { signal } : undefined;
    try {
      await client.session.prompt({ sessionID: sid, text: prompt } as never, opt);
      await client.session.wait({ sessionID: sid } as never, opt);
      if (signal?.aborted) return null;
      const listed = (await client.message.list({ sessionID: sid, limit: 5, order: "desc" } as never, opt)) as {
        data?: Array<{ role?: string; content?: Array<{ type?: string; text?: string }> }>;
      };
      const msg = (listed.data || []).find((x) => x.role === "assistant");
      return (msg?.content || []).filter((c) => c.type === "text").map((c) => c.text || "").join("\n") || null;
    } finally {
      try { await (client.session as unknown as { remove: (a: { sessionID: string }) => Promise<unknown> }).remove({ sessionID: sid }); } catch { /* gone */ }
    }
  } catch {
    return null;
  }
}

export async function quickAsk(t: AskTarget, prompt: string, signal?: AbortSignal): Promise<string | null> {
  if (signal?.aborted) return null;
  const text = t.backend === "opencode" ? await runOpencode(t, prompt, signal) : await runCli(t, prompt, signal);
  return text === null ? null : text.trim();
}
