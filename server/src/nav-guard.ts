/** Request-scope rules that must hold whatever else changes (pure, unit-tested). */

/** Where a browser-driving route may navigate: http(s) only. `file:`, `chrome:`, `view-source:`, `data:`
 *  and `javascript:` would let a request read local files (the token files included) through a screenshot
 *  or a scrape. Returns the normalised URL, or null to refuse. */
export function safeNavUrl(raw: unknown): string | null {
  if (typeof raw !== "string" || !raw.trim() || raw.length > 4096) return null;
  try {
    const u = new URL(raw.trim());
    return u.protocol === "http:" || u.protocol === "https:" ? u.toString() : null;
  } catch { return null; }
}

/** The only paths a link token (the leak-tolerant stand-in kept in URLs) may open: plain files under the
 *  gallery and the tracked-repo viewer. Everything else (the browser routes, profiles, ops, notes, echo)
 *  needs the master token, so a copy that leaks from a URL or log cannot drive the logged-in browser. */
export function linkTokenPathOk(rawUrl: string): boolean {
  let p = "";
  try { p = new URL(rawUrl, "http://x").pathname; } catch { return false; }
  if (p.split("/").some((s) => s === "..")) return false;
  // Agentbook books are local working memory: master token only, never a shareable link.
  // Notes are personal: the app opens them with the master token, never a link token.
  return p.startsWith("/terminus/") || (p.startsWith("/repo/") && !p.startsWith("/repo/agentbook/"));
}
