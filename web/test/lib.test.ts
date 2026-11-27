import { describe, expect, it } from 'vitest';
import { localizeLinks } from '../src/lib/markdown';
import { speechChunks, speechText } from '../src/lib/speak';
import { parseSse } from '../src/lib/sse';
import { mergeThread } from '../src/lib/thread';
import type { Msg } from '../src/lib/types';

describe('parseSse', () => {
  it('splits events, skips pings, keeps the unfinished tail', () => {
    const r = parseSse(': ping\n\nevent: delta\ndata: {"a":1}\n\ndata: one\ndata: two\n\nevent: do');
    expect(r.events).toEqual([
      { event: 'delta', data: '{"a":1}' },
      { event: 'message', data: 'one\ntwo' },
    ]);
    expect(r.rest).toBe('event: do');
  });
  it('handles CRLF', () => {
    expect(parseSse('event: idle\r\ndata: x\r\n\r\n').events).toEqual([{ event: 'idle', data: 'x' }]);
  });
});

describe('localizeLinks', () => {
  it('makes the Mac’s own links same-origin and leaves others alone', () => {
    expect(localizeLinks('see http://100.1.2.3:9009/terminus/a.png ok')).toBe('see /terminus/a.png ok');
    expect(localizeLinks('https://example.com/terminus/x')).toBe('https://example.com/terminus/x');
  });
});

describe('speech', () => {
  it('drops markdown and code', () => {
    expect(speechText('# Hi\n\n**Bold** [link](http://x) and `code`\n```js\nx()\n```')).toBe('Hi Bold link and code (code)');
  });
  it('chunks by sentence under the limit', () => {
    const c = speechChunks('One two. Three four. Five six.', 12);
    expect(c).toEqual(['One two.', 'Three four.', 'Five six.']);
  });
});

describe('mergeThread', () => {
  const t = 1_700_000_000_000;
  const m = (p: Partial<Msg>): Msg => ({ id: Math.random().toString(36), role: 'user', text: '', at: t, ...p }) as Msg;
  it('keeps waiting messages, drops sent ones that reached history, keeps live steps', () => {
    const history = [m({ role: 'user', text: 'hello', at: t + 1000 }), m({ role: 'assistant', text: 'hi there', at: t + 2000 })];
    const cached = [
      m({ role: 'user', text: 'hello', at: t, status: 'sending' }),
      m({ role: 'user', text: 'later', at: t + 5000, status: 'waiting' }),
      m({ role: 'assistant', text: 'hi there', at: t + 2000, turnId: 'T1', steps: [] }),
      m({ role: 'note', text: 'a note', at: t + 6000 }),
    ];
    const out = mergeThread(history, [], cached);
    expect(out.map((x) => x.text)).toEqual(['hello', 'hi there', 'later', 'a note']);
    expect(out[1].turnId).toBe('T1');
  });
});
