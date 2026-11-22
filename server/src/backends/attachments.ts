/** Attachments for CLI-style backends (codex, claude).
 *
 *  Neither CLI takes inline base64 through our adapters, so each attachment is written under a
 *  server-owned folder (MEDIA_DIR/chat-attach/<turn>/) and the prompt gets a short "Attached
 *  files" block naming the absolute paths. The agent opens them with its own file tools, which
 *  already reach the workspace. Names are sanitised, the folder is never client-chosen, and the
 *  gallery skips it; the normal media retention expires the files.
 *
 *  Pure pieces (attachmentNote, safeAttachName) are unit-tested; saveBackendAttachments does I/O. */
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { MEDIA_DIR, CHAT_ATTACH_DIR } from "../media.js";
import type { BackendAttachment } from "./types.js";

export const MAX_ATTACH_FILES = 3;
export const MAX_ATTACH_BYTES = 5 * 1024 * 1024;

/** Keep the extension, drop everything that is not [A-Za-z0-9._-], never empty, never a dotfile. */
export function safeAttachName(raw: string, index: number): string {
  const base = (raw || "").split("/").pop()!.split("\\").pop()!;
  const cleaned = base.replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^\.+/, "").slice(0, 100);
  return cleaned || `file-${index + 1}`;
}

/** The text appended to the user's prompt. Empty when there is nothing attached. */
export function attachmentNote(saved: Array<{ path: string; mime: string; name: string }>): string {
  if (!saved.length) return "";
  const lines = saved.map((f) => `- ${f.path}${f.mime ? ` (${f.mime})` : ""}`);
  return `\n\nAttached files (read them with your file tools):\n${lines.join("\n")}`;
}

/** Write attachments for one turn and return the prompt text with the note appended. A file that
 *  cannot be written is skipped, never fatal: the turn still runs with the rest. */
export async function promptWithAttachments(
  text: string,
  files: BackendAttachment[],
  turnId: string,
): Promise<{ text: string; saved: number }> {
  const list = (files ?? []).slice(0, MAX_ATTACH_FILES);
  if (!list.length) return { text, saved: 0 };
  const dirName = turnId.replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) || `turn-${Date.now()}`;
  const dir = join(MEDIA_DIR, CHAT_ATTACH_DIR, dirName);
  const saved: Array<{ path: string; mime: string; name: string }> = [];
  try {
    await mkdir(dir, { recursive: true });
  } catch {
    return { text, saved: 0 };
  }
  for (let i = 0; i < list.length; i++) {
    const f = list[i];
    try {
      const buf = Buffer.from(f.base64 || "", "base64");
      if (buf.length === 0 || buf.length > MAX_ATTACH_BYTES) continue;
      const name = safeAttachName(f.name, i);
      const path = join(dir, `${i + 1}-${name}`);
      await writeFile(path, buf);
      saved.push({ path, mime: (f.mime || "").slice(0, 80), name });
    } catch { /* skip this file */ }
  }
  return { text: text + attachmentNote(saved), saved: saved.length };
}
