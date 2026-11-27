// Replies as HTML: marked for Markdown, DOMPurify so nothing a model writes can run in the page.
// Extras the Android renderer had: copy buttons on code, ```map cards, inline images and video,
// gallery links made relative (the server writes http://<tailscale-ip>:9009/terminus/… which an
// HTTPS page can't load).
import DOMPurify from 'dompurify';
import { Marked, type Tokens } from 'marked';

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** Pure: the server's own absolute links → same-origin paths. */
export function localizeLinks(md: string): string {
  return md.replace(/https?:\/\/[A-Za-z0-9.-]+(?::\d+)?(\/(?:terminus|repo|notes|ops|share)\/[^\s)"'<>]*)/g, (whole, path: string) => {
    try {
      const u = new URL(whole);
      // Only our own server: a Tailscale/LAN/loopback address on port 9009, or this page's host.
      const own = u.port === '9009' || (typeof location !== 'undefined' && u.host === location.host);
      return own ? path : whole;
    } catch {
      return whole;
    }
  });
}

const VIDEO = /\.(mp4|webm|mov|m4v)(\?|$)/i;
const IMAGE = /\.(png|jpe?g|gif|webp|avif)(\?|$)/i;

const md = new Marked({ gfm: true, breaks: true });
md.use({
  renderer: {
    code(token: Tokens.Code) {
      const lang = (token.lang || '').trim().split(/\s+/)[0] ?? '';
      if (lang === 'map') {
        try {
          const cfg = JSON.parse(token.text);
          return `<div class="mapcard" data-map="${esc(JSON.stringify(cfg))}"></div>`;
        } catch {
          /* bad JSON: shown as code */
        }
      }
      return `<div class="code"><div class="codebar"><span>${esc(lang)}</span><button type="button" class="copy" data-copy="1">Copy</button></div><pre><code>${esc(token.text)}</code></pre></div>`;
    },
    link(token: Tokens.Link) {
      const href = token.href || '';
      const text = this.parser.parseInline(token.tokens);
      return `<a href="${esc(href)}" target="_blank" rel="noopener noreferrer">${text}</a>`;
    },
    image(token: Tokens.Image) {
      const src = token.href || '';
      if (VIDEO.test(src)) return `<video src="${esc(src)}" controls playsinline preload="metadata"></video>`;
      return `<img src="${esc(src)}" alt="${esc(token.text || '')}" loading="lazy" />`;
    },
  },
});

/** Pure: a line that is only a link to an image or video becomes that image or video. */
export function inlineMedia(src: string): string {
  return src.replace(/^(https?:\/\/\S+|\/terminus\/\S+)$/gm, (url) =>
    IMAGE.test(url) ? `![](${url})` : VIDEO.test(url) ? `![](${url})` : url,
  );
}

let hooked = false;
export function renderMarkdown(text: string): string {
  if (!hooked && typeof window !== 'undefined') {
    hooked = true;
    DOMPurify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A') {
        node.setAttribute('target', '_blank');
        node.setAttribute('rel', 'noopener noreferrer');
      }
    });
  }
  const html = md.parse(inlineMedia(localizeLinks(text)), { async: false }) as string;
  if (typeof window === 'undefined') return esc(text);
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target', 'data-copy', 'data-map', 'controls', 'playsinline', 'preload', 'loading'], ADD_TAGS: ['video'] });
}
