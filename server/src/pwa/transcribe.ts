/** Speech to text on this Mac for the web app (dictation and voice notes). Safari can't stream raw
 *  microphone PCM reliably from a home-screen app, so the app records a clip and posts it whole.
 *
 *    POST /api/pwa/transcribe   body: the audio (mp4/aac, webm, wav…), up to 25 MB → {text}
 *
 *  ffmpeg turns it into 16 kHz mono WAV, whisper.cpp (`whisper-cli`) reads it. Both run locally; the
 *  audio never leaves the Mac. 503 when either tool or the model is missing.
 *    TERMINUS_WHISPER_BIN    default /opt/homebrew/bin/whisper-cli
 *    TERMINUS_WHISPER_MODEL  default ~/.cache/whisper/ggml-base.en.bin
 *    TERMINUS_FFMPEG         default /opt/homebrew/bin/ffmpeg */
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { json } from "../http.js";

const MAX = 25 * 1024 * 1024;
const TIMEOUT_MS = 120_000;

export function whisperSetup(env: NodeJS.ProcessEnv = process.env): { bin: string; model: string; ffmpeg: string } {
  return {
    bin: env.TERMINUS_WHISPER_BIN || "/opt/homebrew/bin/whisper-cli",
    model: env.TERMINUS_WHISPER_MODEL || join(homedir(), ".cache", "whisper", "ggml-base.en.bin"),
    ffmpeg: env.TERMINUS_FFMPEG || "/opt/homebrew/bin/ffmpeg",
  };
}

/** Pure: whisper's stdout (one line per segment, maybe "[BLANK_AUDIO]") → plain text. */
export function cleanTranscript(out: string): string {
  return out
    .split("\n")
    .map((l) => l.replace(/^\s*\[[^\]]*-->[^\]]*\]\s*/, "").trim())
    .filter((l) => l && !/^\[(BLANK_AUDIO|MUSIC|NOISE|SILENCE)\]$/i.test(l) && !/^\((?:silence|music|noise)\)$/i.test(l))
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

function run(cmd: string, args: string[]): Promise<{ code: number; out: string; err: string }> {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    let err = "";
    p.stdout.on("data", (d) => { out += d; });
    p.stderr.on("data", (d) => { err = (err + d).slice(-2000); });
    const t = setTimeout(() => p.kill("SIGKILL"), TIMEOUT_MS);
    p.on("close", (code) => { clearTimeout(t); resolve({ code: code ?? 1, out, err }); });
    p.on("error", (e) => { clearTimeout(t); resolve({ code: 1, out: "", err: e.message }); });
  });
}

async function readAll(req: IncomingMessage): Promise<Buffer | null> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const c of req) {
    const b = Buffer.isBuffer(c) ? c : Buffer.from(c as Uint8Array);
    size += b.length;
    if (size > MAX) return null;
    chunks.push(b);
  }
  return Buffer.concat(chunks);
}

export async function handleTranscribe(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const w = whisperSetup();
  if (!existsSync(w.bin) || !existsSync(w.model) || !existsSync(w.ffmpeg)) {
    return json(res, 503, { error: "speech to text isn't set up on the Mac (whisper-cli, its model or ffmpeg is missing)" });
  }
  const body = await readAll(req);
  if (!body) return json(res, 413, { error: "recording too long (25 MB max)" });
  if (body.length < 200) return json(res, 200, { text: "" });
  const dir = await mkdtemp(join(tmpdir(), "terminus-stt-"));
  try {
    const input = join(dir, "in");
    const wav = join(dir, "in.wav");
    await writeFile(input, body, { mode: 0o600 });
    const conv = await run(w.ffmpeg, ["-hide_banner", "-loglevel", "error", "-i", input, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav]);
    if (conv.code !== 0) return json(res, 400, { error: "couldn't read that recording" });
    const r = await run(w.bin, ["-m", w.model, "-f", wav, "-nt", "-np", "-l", "en"]);
    if (r.code !== 0) {
      console.log(`transcribe: whisper failed ${r.err.slice(-200)}`);
      return json(res, 500, { error: "speech to text failed" });
    }
    return json(res, 200, { text: cleanTranscript(r.out) });
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}
