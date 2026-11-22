/** Tiny, safe Markdown → HTML for notebook entries, skills and the profile.
 *  Escapes first, then supports only what those texts use: #–### headings, bullet and numbered lists,
 *  **bold**, `code`, and http(s) links. Anything else stays plain text, so it can never inject markup. */
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

export function inlineMd(s: string): string {
  return esc(s)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" rel="noopener">$1</a>');
}

/** The same text with the markup removed, for one-line previews and search. */
export function plainMd(s: string): string {
  return s
    .replace(/^\s*(?:[-*•]|\d+[.)])\s+/gm, "")
    .replace(/^#{1,3}\s+/gm, "")
    .replace(/\[([^\]]+)\]\(https?:\/\/[^\s)]+\)/g, "$1")
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/`([^`]+)`/g, "$1");
}

export function markdownHtml(markdown: string): string {
  const out: string[] = [];
  let list: "ul" | "ol" | null = null;
  const close = () => { if (list) { out.push(`</${list}>`); list = null; } };
  const open = (k: "ul" | "ol") => { if (list !== k) { close(); out.push(`<${k}>`); list = k; } };
  for (const raw of markdown.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) { close(); continue; }
    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) { close(); const lv = heading[1].length === 1 ? 2 : 3; out.push(`<h${lv}>${inlineMd(heading[2])}</h${lv}>`); continue; }
    const bullet = line.match(/^[-*•]\s+(.+)$/);
    if (bullet) { open("ul"); out.push(`<li>${inlineMd(bullet[1])}</li>`); continue; }
    const num = line.match(/^\d+[.)]\s+(.+)$/);
    if (num) { open("ol"); out.push(`<li>${inlineMd(num[1])}</li>`); continue; }
    close();
    out.push(`<p>${inlineMd(line)}</p>`);
  }
  close();
  return out.join("");
}
