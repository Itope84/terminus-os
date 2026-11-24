import type { IncomingMessage, ServerResponse } from "node:http";
import type { Json } from "./util.js";
import { readJsonBody } from "./http.js";

export async function handleBackendFork(req: IncomingMessage, res: ServerResponse, json: Json) {
  const parsed = await readJsonBody(req);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  const body = parsed.value;
  const backend = body.backend;
  const chatId = typeof body.chatId === "string" ? body.chatId : "";
  const remoteSessionId = typeof body.sessionID === "string" ? body.sessionID : "";
  if ((backend !== "codex" && backend !== "claude") || !chatId || !remoteSessionId) {
    return json(res, 400, { error: "bad backend fork request" });
  }
  try {
    const { getAdapter } = await import("./backends/registry.js");
    const adapter = getAdapter(backend);
    if (typeof adapter.fork !== "function") {
      return json(res, 502, { error: "backend does not support fork" });
    }
    const forked = await adapter.fork({ chatId, remoteSessionId });
    return json(res, 200, { ok: true, sessionID: forked.remoteSessionId });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Codex fork failed";
    return json(res, 502, { error: message.slice(0, 240) });
  }
}

export async function handleBackendCommand(req: IncomingMessage, res: ServerResponse, json: Json) {
  const parsed = await readJsonBody(req);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  const body = parsed.value;
  const command = typeof body.command === "string" ? body.command.trim().toLowerCase() : "";
  const argument = typeof body.argument === "string" ? body.argument.slice(0, 200) : "";
  if (body.backend !== "codex" || !["/usage", "/resets", "/redeem"].includes(command)) {
    return json(res, 400, { error: "unsupported backend command" });
  }
  try {
    const { getAdapter } = await import("./backends/registry.js");
    const adapter = getAdapter("codex") as typeof getAdapter extends (...args: never[]) => infer A ? A & { executeCommand?: (name: string, argument: string) => Promise<string> } : never;
    if (typeof adapter.executeCommand !== "function") return json(res, 501, { error: "Codex commands unavailable" });
    const text = await adapter.executeCommand(command, argument);
    return json(res, 200, { backend: "codex", command, text });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Codex command failed";
    return json(res, 502, { error: message.slice(0, 240) });
  }
}
