// Server-Sent Events over fetch: reads the server's `: ping` comments too, so a stalled link (common
// on iOS after the phone sleeps) is noticed and reopened. EventSource hides pings and can hang.

export interface SseHandlers {
  onEvent: (event: string, data: string) => void;
  /** The stream is up (or back up). */
  onOpen?: () => void;
  /** The stream dropped; it will retry. */
  onDown?: (why: string) => void;
}

export interface SseOptions {
  /** No bytes for this long = stalled: reopen. The server pings every 20 s. */
  stallMs?: number;
  /** Max wait between retries. */
  maxBackoffMs?: number;
}

/** Pure: split a buffer into complete SSE blocks; the unfinished tail stays in `rest`. */
export function parseSse(buf: string): { events: { event: string; data: string }[]; rest: string } {
  const blocks = buf.replace(/\r\n/g, '\n').split('\n\n');
  const rest = blocks.pop() ?? '';
  const events: { event: string; data: string }[] = [];
  for (const block of blocks) {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) continue;
      const i = line.indexOf(':');
      const field = i === -1 ? line : line.slice(0, i);
      const value = i === -1 ? '' : line.slice(i + 1).replace(/^ /, '');
      if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length) events.push({ event, data: data.join('\n') });
  }
  return { events, rest };
}

/** Opens `url()` (called again on each retry, so it can carry a fresh `since`) and keeps it open
 *  until close(). An answer with status 401/400/409 stops retrying; others back off. */
export function openSse(url: () => string, h: SseHandlers, opts: SseOptions = {}): { close: () => void; kick: () => void } {
  const stallMs = opts.stallMs ?? 60_000;
  const maxBackoff = opts.maxBackoffMs ?? 30_000;
  let closed = false;
  let ctl: AbortController | null = null;
  let backoff = 1000;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  /** Each attempt's number: an attempt that's been replaced (kick) never schedules a retry. */
  let gen = 0;

  const connect = async () => {
    if (closed) return;
    const myGen = ++gen;
    ctl = new AbortController();
    const mine = ctl;
    let stall: ReturnType<typeof setTimeout> | null = null;
    const arm = () => {
      if (stall) clearTimeout(stall);
      stall = setTimeout(() => mine.abort(), stallMs);
    };
    let why = 'closed';
    try {
      arm();
      const res = await fetch(url(), { headers: { Accept: 'text/event-stream' }, credentials: 'same-origin', signal: mine.signal, cache: 'no-store' });
      if (!res.ok || !res.body) {
        why = `status ${res.status}`;
        if (res.status === 401 || res.status === 400 || res.status === 409) {
          h.onDown?.(why);
          closed = true;
          return;
        }
        throw new Error(why);
      }
      backoff = 1000;
      h.onOpen?.();
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        arm();
        buf += dec.decode(value, { stream: true });
        const parsed = parseSse(buf);
        buf = parsed.rest;
        for (const e of parsed.events) {
          try {
            h.onEvent(e.event, e.data);
          } catch {
            /* a bad handler never kills the stream */
          }
        }
      }
    } catch (e) {
      why = mine.signal.aborted ? 'stalled' : String((e as Error).message || 'network');
    } finally {
      if (stall) clearTimeout(stall);
    }
    if (closed || myGen !== gen) return;
    h.onDown?.(why);
    retryTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, maxBackoff);
  };

  void connect();
  return {
    close: () => {
      closed = true;
      if (retryTimer) clearTimeout(retryTimer);
      ctl?.abort();
    },
    /** Reconnect now (the app came back to the foreground or the network returned). */
    kick: () => {
      if (closed) return;
      if (retryTimer) clearTimeout(retryTimer);
      backoff = 1000;
      gen++; // the running attempt is now stale
      ctl?.abort();
      retryTimer = setTimeout(connect, 50);
    },
  };
}
