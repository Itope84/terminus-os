/** Human wording for a Codex approval request shown on the phone. Pure,
 *  unit-tested. Never includes more than the command / paths themselves. */
export function approvalPromptText(
  method: string,
  params: unknown,
  reason: string,
): { title: string; detail: string } {
  const p = (params && typeof params === "object" ? params : {}) as Record<string, unknown>;
  const str = (v: unknown) => (typeof v === "string" ? v : "");
  const why = reason.replace(/^deny marker\s*/, "").trim();
  const whyLine = why ? `Protected: ${why}` : reason;
  const paths: string[] = [];
  if (Array.isArray(p.fileChanges)) {
    for (const fc of p.fileChanges as Array<Record<string, unknown>>) {
      const x = str(fc?.newPath) || str(fc?.path);
      if (x) paths.push(x);
    }
  }
  if (str(p.grantRoot)) paths.push(str(p.grantRoot));
  const cmd = str(p.command);
  if (cmd || /command/i.test(method)) {
    return { title: "Run a command", detail: [cmd.slice(0, 400), whyLine].filter(Boolean).join("\n\n") };
  }
  if (/filechange/i.test(method) || paths.length) {
    return { title: "Change files", detail: [paths.slice(0, 6).join("\n"), whyLine].filter(Boolean).join("\n\n") };
  }
  return { title: "Action needs approval", detail: whyLine };
}
