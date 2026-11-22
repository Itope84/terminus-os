/** Streamed thinking arrives in arbitrary chunks ("I'" + "d planned. I'm mo" + "ving on"). Showing each chunk as its
 *  own row splits words and sentences, so chunks are buffered and cut into rows at paragraph breaks, or at the last
 *  space before the row cap. Pure: the caller owns the buffer and the timer. */
export const REASONING_ROW_MAX = 500;

export function splitReasoning(buf: string, final: boolean, max: number = REASONING_ROW_MAX): { rows: string[]; rest: string } {
  const rows: string[] = [];
  const push = (t: string) => { const v = t.trim(); if (v) rows.push(v); };
  let rest = buf;
  // Complete paragraphs go out whole (long ones are cut at spaces below).
  for (;;) {
    const i = rest.indexOf("\n\n");
    if (i < 0) break;
    cut(rest.slice(0, i), max).forEach(push);
    rest = rest.slice(i + 2);
  }
  // A long unfinished paragraph: send what fits, keep the tail for the next chunk.
  while (rest.length > max) {
    let at = rest.lastIndexOf(" ", max);
    if (at < max / 2) at = max; // no usable space: hard cut
    push(rest.slice(0, at));
    rest = rest.slice(at).replace(/^ +/, "");
  }
  if (final) { push(rest); rest = ""; }
  return { rows, rest };
}

function cut(paragraph: string, max: number): string[] {
  const out: string[] = [];
  let rest = paragraph;
  while (rest.length > max) {
    let at = rest.lastIndexOf(" ", max);
    if (at < max / 2) at = max;
    out.push(rest.slice(0, at));
    rest = rest.slice(at).replace(/^ +/, "");
  }
  out.push(rest);
  return out;
}
