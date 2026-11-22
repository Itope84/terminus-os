/** Opencode adapter (multi-backend spec M3, section 14).
 *
 *  Facade over the proven opencode-proxy cores: models/commands delegate to
 *  the extracted fetchers, so handler and adapter can never diverge.
 *  Turn lifecycle (startTurn/interrupt/getStatus/stream/…) still routes
 *  through opencode-proxy directly; generic turn routing moves in M6.
 *  Lifecycle methods here fail closed with unsupported_operation until M6
 *  wires them — explicit, never fake success. No route calls them yet.
 */
import type {
  BackendAdapter,
  BackendContext,
  BackendDescriptor,
  BackendStatus,
  CommandOption,
  ForkArgs,
  ForkResult,
  HistoryArgs,
  HistoryResult,
  InterruptResult,
  InterruptTurnArgs,
  ModelOption,
  RemoteSession,
  ResumeSessionArgs,
  ResumeSessionResult,
  SessionArgs,
  StartTurnArgs,
  StartTurnResult,
  StatusArgs,
  StreamArgs,
  EmitBackendEvent,
} from "./types.js";
import { getDescriptor } from "./registry.js";
import { BackendError } from "./errors.js";
import { fetchOpencodeCommands, fetchOpencodeModels } from "../opencode-proxy.js";

function notYet(operation: string): BackendError {
  return new BackendError(
    "unsupported_operation",
    `opencode ${operation} routes in M6`,
    false,
    "opencode",
    operation,
  );
}

export class OpencodeAdapter implements BackendAdapter {
  readonly id = "opencode" as const;

  descriptor(): BackendDescriptor {
    return getDescriptor("opencode");
  }

  async listModels(_ctx: BackendContext): Promise<ModelOption[]> {
    const models = await fetchOpencodeModels().catch(() => {
      throw new BackendError("backend_unavailable", "opencode unavailable", true, "opencode", "listModels");
    });
    return models.map((m) => ({ id: m.id, label: m.label, provider: m.provider }));
  }

  async listCommands(_ctx: BackendContext): Promise<CommandOption[]> {
    const commands = await fetchOpencodeCommands().catch(() => {
      throw new BackendError("backend_unavailable", "opencode unavailable", true, "opencode", "listCommands");
    });
    return commands.map((c) => ({ name: c.name, description: c.description, available: true }));
  }

  async startTurn(_args: StartTurnArgs): Promise<StartTurnResult> {
    throw notYet("startTurn");
  }
  async interruptTurn(_args: InterruptTurnArgs): Promise<InterruptResult> {
    throw notYet("interruptTurn");
  }
  async getStatus(_args: StatusArgs): Promise<BackendStatus> {
    throw notYet("getStatus");
  }
  async resumeSession(_args: ResumeSessionArgs): Promise<ResumeSessionResult> {
    throw notYet("resumeSession");
  }
  async stream(_args: StreamArgs, _emit: EmitBackendEvent): Promise<void> {
    throw notYet("stream");
  }
  async getTitle(_args: SessionArgs): Promise<string> {
    throw notYet("getTitle");
  }
  async setTitle(_args: SessionArgs & { title: string }): Promise<void> {
    throw notYet("setTitle");
  }
  async compact(_args: SessionArgs): Promise<void> {
    throw notYet("compact");
  }
  async deleteSession(_args: SessionArgs): Promise<void> {
    throw notYet("deleteSession");
  }
  async fork(_args: ForkArgs): Promise<ForkResult> {
    throw notYet("fork");
  }
  async listSessions(_args: { limit?: number }): Promise<RemoteSession[]> {
    throw notYet("listSessions");
  }
  async history(_args: HistoryArgs): Promise<HistoryResult> {
    throw notYet("history");
  }
}
