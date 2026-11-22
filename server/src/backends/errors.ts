/** Normalized backend errors (multi-backend spec M2, section 23).
 *
 *  Server logs may carry diagnostics; the client (and any error row) only
 *  ever sees { error, backend, operation, message } from toPublicError().
 *  Never surface: provider tokens, command lines, env values, absolute
 *  secret-store paths, prompt text, or attachment payloads.
 */
import type { BackendId } from "./types.js";

export type BackendErrorCode =
  | "backend_unavailable"
  | "not_authenticated"
  | "permission_denied"
  | "model_not_found"
  | "rate_limited"
  | "usage_limit"
  | "invalid_request"
  | "unsupported_operation"
  | "process_exited"
  | "session_not_found"
  | "turn_interrupted"
  | "provider_error"
  | "attachment_unsupported"
  | "timeout"
  | "unknown";

export class BackendError extends Error {
  readonly code: BackendErrorCode;
  readonly retryable: boolean;
  readonly backend?: BackendId;
  readonly operation?: string;

  constructor(
    code: BackendErrorCode,
    message: string,
    retryable = false,
    backend?: BackendId,
    operation?: string,
  ) {
    super(message);
    this.name = "BackendError";
    this.code = code;
    this.retryable = retryable;
    this.backend = backend;
    this.operation = operation;
  }
}

/** Normalized backend error → HTTP status (never 200 for failures).
 *  Pure map shared by routes and the turn runtime. */
export function backendHttpStatus(code: BackendErrorCode): number {
  switch (code) {
    case "invalid_request":
    case "model_not_found":
    case "attachment_unsupported":
      return 400;
    case "not_authenticated":
      return 401;
    case "permission_denied":
      return 403;
    case "session_not_found":
      return 404;
    case "unsupported_operation":
    case "backend_unavailable":
      return 501;
    case "rate_limited":
    case "usage_limit":
      return 429;
    case "timeout":
      return 504;
    case "turn_interrupted":
      return 409;
    default:
      return 502;
  }
}

export interface PublicBackendError {
  error: BackendErrorCode;
  backend?: BackendId;
  operation?: string;
  message: string;
}

/** Safe client-facing projection. Drops cause/env/command detail by design. */
export function toPublicError(err: unknown, backend?: BackendId, operation?: string): PublicBackendError {
  if (err instanceof BackendError) {
    return {
      error: err.code,
      backend: err.backend ?? backend,
      operation: err.operation ?? operation,
      message: err.message,
    };
  }
  const message = err instanceof Error ? err.message : "provider failed";
  return { error: "unknown", backend, operation, message: safeMessage(message) };
}

/** Strip secret-shaped content from foreign error text before it can reach
 *  a response: tokens, key material, env dumps, absolute home paths. */
export function safeMessage(raw: string): string {
  return String(raw)
    .replace(/sk-[A-Za-z0-9_-]{8,}/g, "sk-REDACTED")
    .replace(/xox[bpas]-[A-Za-z0-9-]{6,}/g, "xox-REDACTED")
    .replace(/Bearer\s+[A-Za-z0-9._~+/-]{8,}={0,2}/gi, "Bearer REDACTED")
    .replace(/\/Users\/[^/\s]+/g, "/Users/REDACTED")
    .replace(/\/home\/[^/\s]+/g, "/home/REDACTED")
    .slice(0, 300);
}

/** One row of a backend's provider-text → BackendError table. Rows are tried in order. */
export interface ErrorRule {
  re: RegExp;
  code: BackendErrorCode;
  message: string;
  retryable?: boolean;
}

/** Shared body of every per-backend error mapper: lower-cases the provider text, returns the
 *  first matching rule, else a generic provider_error. Pure. */
export function classifyProviderError(
  e: unknown,
  operation: string,
  backend: BackendId,
  fallbackMessage: string,
  rules: readonly ErrorRule[],
): BackendError {
  const low = (e instanceof Error ? e.message : String(e)).toLowerCase();
  for (const r of rules) {
    if (r.re.test(low)) return new BackendError(r.code, r.message, r.retryable ?? false, backend, operation);
  }
  return new BackendError("provider_error", fallbackMessage, false, backend, operation);
}
