// The thread, drawn as plain DOM (web): selectable text for Quote, real <details> for steps, and
// Markdown HTML from lib/markdown.ts. Keeps to the bottom while you're at the bottom.
import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { drawMaps } from '../lib/maps';
import { renderMarkdown } from '../lib/markdown';
import { speak, speakingNow, stopSpeaking } from '../lib/speak';
import type { Msg, Step } from '../lib/types';
import './thread.css';

export interface ThreadActions {
  onRetry: (msgId: string) => void;
  onEdit: (msgId: string) => void;
  onSendNow: (turnId: string) => void;
  onDiscard: (turnId: string) => void;
  onQuote: (text: string) => void;
}

const time = (at: number) => new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const dayLabel = (at: number) => {
  const d = new Date(at);
  const today = new Date();
  const y = new Date(Date.now() - 86_400_000);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) });
};

function dur(steps: Step[]): string {
  if (steps.length < 2) return '';
  const s = Math.round((steps.at(-1)!.at - steps[0].at) / 1000);
  return s >= 60 ? `${Math.floor(s / 60)}m ${s % 60}s` : `${s}s`;
}

const Steps = memo(function Steps({ steps, live }: { steps: Step[]; live: boolean }) {
  const shown = live ? steps.slice(-4) : steps;
  const failed = steps.filter((s) => /fail|error/i.test(s.label)).length;
  const files = [...new Set(steps.flatMap((s) => s.files ?? []))];
  const body = shown.map((s, i) => (
    <div key={i} className={`step${/fail|error/i.test(s.label) ? ' fail' : ''}`}>
      {s.label}
      {s.detail ? <span className="d">{s.detail}</span> : null}
    </div>
  ));
  if (live) return <div className="steps">{body}</div>;
  const n = steps.filter((s) => !/^done /.test(s.label)).length || steps.length;
  return (
    <details className="steps">
      <summary>
        {n} step{n === 1 ? '' : 's'}
        {dur(steps) ? ` · ${dur(steps)}` : ''}
        {failed ? ` · ${failed} failed` : ''}
        {files.length ? ` · ${files.length} file${files.length === 1 ? '' : 's'} changed` : ''}
      </summary>
      {body}
      {files.length ? <div className="step">Changed: {files.join(', ')}</div> : null}
    </details>
  );
});

const Reply = memo(function Reply({ text }: { text: string }) {
  const html = useMemo(() => renderMarkdown(text), [text]);
  return <div className="reply" dangerouslySetInnerHTML={{ __html: html }} />;
});

function Row({ m, a, status }: { m: Msg; a: ThreadActions; status: string }) {
  const [, force] = useState(0);
  if (m.role === 'user') {
    return (
      <div className="row user">
        <div className="bubble">
          {m.text}
          {m.files?.length ? <div className="files">📎 {m.files.join(', ')}</div> : null}
        </div>
        <div className="meta">
          {m.status === 'sending' && <span>Sending…</span>}
          {m.status === 'waiting' && (
            <>
              <span>Waiting for the Mac · goes when it’s back</span>
              <button type="button" onClick={() => a.onEdit(m.id)}>Edit</button>
            </>
          )}
          {m.status === 'queued' && m.turnId && (
            <>
              <span>Queued</span>
              <button type="button" onClick={() => a.onSendNow(m.turnId!)}>Send now</button>
              <button type="button" onClick={() => a.onDiscard(m.turnId!)}>Discard</button>
            </>
          )}
          {m.status === 'failed' && (
            <>
              <span className="bad">{m.error || 'Not sent'}</span>
              <button type="button" onClick={() => a.onRetry(m.id)}>Retry</button>
              <button type="button" onClick={() => a.onEdit(m.id)}>Edit</button>
            </>
          )}
          {(!m.status || m.status === 'sent') && <span>{time(m.at)}</span>}
        </div>
      </div>
    );
  }
  if (m.role === 'assistant') {
    const live = m.status === 'live';
    const reading = speakingNow() === m.id;
    return (
      <div className="row">
        {m.steps?.length ? <Steps steps={m.steps} live={live} /> : null}
        {live && (
          <div className="live">
            <span className="dots"><i /><i /><i /></span>
            <span>{status || (m.text ? 'Writing…' : 'Working…')}</span>
          </div>
        )}
        {m.text ? (
          <div className={live ? 'cursor' : ''}>
            <Reply text={m.text} />
          </div>
        ) : null}
        {!live && (
          <div className="meta">
            <span>{time(m.at)}{m.model ? ` · ${m.model}` : ''}</span>
            <button
              type="button"
              onClick={() => {
                if (reading) stopSpeaking();
                else speak(m.id, m.text, () => force((x) => x + 1));
                force((x) => x + 1);
              }}
            >
              {reading ? 'Stop reading' : 'Read aloud'}
            </button>
            <button type="button" onClick={() => void navigator.clipboard?.writeText(m.text)}>Copy</button>
          </div>
        )}
      </div>
    );
  }
  if (m.role === 'inbox') {
    return (
      <div className="row">
        <div className="inbox">
          {m.title ? <div className="t">{m.title}</div> : null}
          {m.text ? <Reply text={m.text} /> : null}
        </div>
        <div className="meta"><span>{time(m.at)}</span></div>
      </div>
    );
  }
  if (m.role === 'error') {
    return (
      <div className="row">
        <div className="error">{m.text}</div>
      </div>
    );
  }
  return <div className="note">{m.text}</div>;
}

export function Thread({ msgs, status, actions, empty, style }: { msgs: Msg[]; status: string; actions: ThreadActions; empty: string; style: React.CSSProperties }) {
  const ref = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const [quote, setQuote] = useState<{ text: string; x: number; y: number } | null>(null);

  // Stay at the bottom while the reply grows, unless you've scrolled up to read.
  useLayoutEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  });
  useEffect(() => {
    if (ref.current) void drawMaps(ref.current);
  }, [msgs]);

  useEffect(() => {
    const onSel = () => {
      const sel = document.getSelection();
      const text = sel?.toString().trim() ?? '';
      if (!text || !sel?.rangeCount || !ref.current?.contains(sel.anchorNode)) return setQuote(null);
      const r = sel.getRangeAt(0).getBoundingClientRect();
      setQuote({ text, x: Math.min(Math.max(r.left + r.width / 2 - 40, 8), window.innerWidth - 100), y: Math.max(r.top - 46, 60) });
    };
    document.addEventListener('selectionchange', onSel);
    return () => document.removeEventListener('selectionchange', onSel);
  }, []);

  const onClick = (e: React.MouseEvent) => {
    const btn = (e.target as HTMLElement).closest('button[data-copy]');
    if (btn) {
      const code = btn.closest('.code')?.querySelector('code')?.textContent ?? '';
      void navigator.clipboard?.writeText(code);
      btn.textContent = 'Copied';
      setTimeout(() => (btn.textContent = 'Copy'), 1200);
    }
  };

  let lastDay = '';
  return (
    <div
      ref={ref}
      className="thread"
      style={style}
      onClick={onClick}
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
      }}
    >
      {msgs.length === 0 ? <div className="empty">{empty}</div> : null}
      {msgs.map((m) => {
        const day = m.at ? dayLabel(m.at) : '';
        const sep = day && day !== lastDay;
        lastDay = day || lastDay;
        return (
          <div key={m.id}>
            {sep ? <div className="day">{day}</div> : null}
            <Row m={m} a={actions} status={status} />
          </div>
        );
      })}
      {quote ? (
        <button
          type="button"
          className="quotebtn"
          style={{ left: quote.x, top: quote.y }}
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            actions.onQuote(quote.text);
            document.getSelection()?.removeAllRanges();
            setQuote(null);
          }}
        >
          Quote
        </button>
      ) : null}
    </div>
  );
}
