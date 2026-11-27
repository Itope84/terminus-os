// Read a reply aloud with the phone's own voice (speechSynthesis). Markdown is stripped so it isn't
// read out; long text goes in sentence-sized chunks so speech starts at once.

/** Pure: what a reply sounds like (no code blocks, links read as their words, no markup). */
export function speechText(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, ' (code) ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/\S+/g, 'a link')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/[*_~|]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Pure: sentence-sized pieces, each under `max` characters. */
export function speechChunks(text: string, max = 220): string[] {
  const parts = text.match(/[^.!?]+[.!?]*\s*/g) ?? [text];
  const out: string[] = [];
  let cur = '';
  for (const p of parts) {
    if ((cur + p).length > max && cur) {
      out.push(cur.trim());
      cur = '';
    }
    cur += p;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

let speakingId = '';
export const speakingNow = () => speakingId;

export function speak(id: string, md: string, onEnd?: () => void) {
  if (typeof speechSynthesis === 'undefined') return;
  speechSynthesis.cancel();
  speakingId = id;
  const chunks = speechChunks(speechText(md));
  chunks.forEach((c, i) => {
    const u = new SpeechSynthesisUtterance(c);
    u.lang = 'en-GB';
    if (i === chunks.length - 1) u.onend = () => { speakingId = ''; onEnd?.(); };
    speechSynthesis.speak(u);
  });
}

export function stopSpeaking() {
  if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
  speakingId = '';
}
