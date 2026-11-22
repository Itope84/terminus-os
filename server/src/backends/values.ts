/** Tiny pure coercions for untrusted JSON (no runtime imports, so the *-core test files stay light). */
export function textOf(v: unknown): string {
  return typeof v === "string" ? v : "";
}

export function asRecord(v: unknown): Record<string, unknown> {
  return (v && typeof v === "object" && !Array.isArray(v) ? v : {}) as Record<string, unknown>;
}
