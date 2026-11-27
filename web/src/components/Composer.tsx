// The message box: text, quotes, up to three attachments, dictation through the Mac, slash commands.
// While a reply runs, the round button is Stop; anything you send then waits in line (Send now / Discard).
import { useEffect, useRef, useState } from 'react';
import { blobToBase64, type Recorder, recorderSupported, startRecording, transcribe } from '../lib/audio';
import { toast } from '../lib/store';
import type { OutFile } from '../lib/thread';
import type { Command } from '../lib/types';

export const LOCAL_COMMANDS: Command[] = [
  { name: '/new', description: 'Start a new chat' },
  { name: '/stop', description: 'Stop the running reply' },
  { name: '/model', description: 'Pick the model (or /model <name>)' },
  { name: '/goal', description: 'Set a goal the agent works towards (/goal <objective>)' },
  { name: '/context', description: 'How full the context window is, and the cost' },
  { name: '/compact', description: 'Summarise the conversation to free context' },
  { name: '/rename', description: 'Rename this chat (/rename <title>)' },
  { name: '/fetch', description: 'Read a web page (/fetch <url>)' },
  { name: '/clear', description: 'Clear this screen (the chat stays on the Mac)' },
  { name: '/export', description: 'Save the chat as a text file' },
  { name: '/delete', description: 'Delete this chat' },
];

const MAX_FILES = 3;
const MAX_BYTES = 5 * 1024 * 1024;

interface Staged extends OutFile {
  size: number;
}

export interface ComposerHandle {
  setText: (t: string) => void;
  addQuote: (q: string) => void;
}

export function Composer({
  running,
  commands,
  onSend,
  onStop,
  onCommand,
  draftKey,
  bindHandle,
}: {
  running: boolean;
  commands: Command[];
  onSend: (text: string, files: OutFile[]) => void;
  onStop: () => void;
  /** A local slash command; returns true when it handled the text. */
  onCommand: (name: string, arg: string) => boolean;
  draftKey: string;
  bindHandle: (h: ComposerHandle) => void;
}) {
  const [text, setText] = useState(() => {
    try {
      return localStorage.getItem(draftKey) ?? '';
    } catch {
      return '';
    }
  });
  const [quotes, setQuotes] = useState<string[]>([]);
  const [files, setFiles] = useState<Staged[]>([]);
  const [rec, setRec] = useState<Recorder | null>(null);
  const [level, setLevel] = useState(0);
  const [secs, setSecs] = useState(0);
  const [busy, setBusy] = useState('');
  const ta = useRef<HTMLTextAreaElement>(null);
  const fileIn = useRef<HTMLInputElement>(null);

  useEffect(() => {
    bindHandle({
      setText: (t) => {
        setText(t);
        setTimeout(() => ta.current?.focus(), 30);
      },
      addQuote: (q) => setQuotes((qs) => [...qs, q.slice(0, 2000)]),
    });
  }, [bindHandle]);

  useEffect(() => {
    try {
      if (text) localStorage.setItem(draftKey, text);
      else localStorage.removeItem(draftKey);
    } catch {
      /* ignore */
    }
  }, [text, draftKey]);

  // Grow with the text.
  useEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight + 2, window.innerHeight * 0.4)}px`;
  }, [text]);

  useEffect(() => {
    if (!rec) return;
    const t = setInterval(() => {
      setLevel(rec.level());
      setSecs(Math.floor((Date.now() - rec.startedAt) / 1000));
    }, 100);
    return () => clearInterval(t);
  }, [rec]);

  const slash = text.startsWith('/') && !text.includes('\n') && !text.includes(' ')
    ? [...LOCAL_COMMANDS, ...commands.filter((c) => !LOCAL_COMMANDS.some((l) => l.name === c.name))].filter((c) => c.name.startsWith(text.toLowerCase())).slice(0, 12)
    : [];

  const submit = () => {
    const raw = text.trim();
    if (!raw && !files.length && !quotes.length) return;
    const m = /^\/([\w.-]+)(?:\s+([\s\S]*))?$/.exec(raw);
    if (m && !quotes.length && !files.length && onCommand(m[1].toLowerCase(), (m[2] ?? '').trim())) {
      setText('');
      return;
    }
    const quoted = quotes.map((q) => q.split('\n').map((l) => `> ${l}`).join('\n')).join('\n\n');
    const full = quoted ? `${quoted}\n\n${raw}` : raw;
    onSend(full, files.map(({ size: _s, ...f }) => f));
    setText('');
    setQuotes([]);
    setFiles([]);
  };

  const pick = async (list: FileList | null) => {
    if (!list) return;
    const next = [...files];
    for (const f of [...list]) {
      if (next.length >= MAX_FILES) {
        toast('Three attachments at most.');
        break;
      }
      if (f.size > MAX_BYTES) {
        toast(`${f.name} is over 5 MB.`);
        continue;
      }
      next.push({ name: f.name || `file-${Date.now()}`, mime: f.type || 'application/octet-stream', dataBase64: await blobToBase64(f), size: f.size });
    }
    setFiles(next);
  };

  const startMic = async () => {
    if (!recorderSupported()) return toast('This browser can’t record. Open Terminus from the Home Screen over HTTPS.');
    try {
      setRec(await startRecording());
    } catch {
      toast('Microphone is off for Terminus.');
    }
  };
  const stopMic = async (keep: boolean) => {
    const r = rec;
    setRec(null);
    if (!r) return;
    if (!keep) return r.cancel();
    const clip = await r.stop();
    setBusy('Transcribing on the Mac…');
    try {
      const t = await transcribe(clip);
      if (!t) toast('Didn’t catch anything.');
      else setText((cur) => (cur ? `${cur.trimEnd()} ${t}` : t));
      setTimeout(() => ta.current?.focus(), 30);
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setBusy('');
    }
  };

  const canSend = !!(text.trim() || files.length || quotes.length);

  return (
    <div className="composer">
      {slash.length ? (
        <div className="slash">
          {slash.map((c) => (
            <div key={c.name} onMouseDown={(e) => e.preventDefault()} onClick={() => setText(`${c.name} `)}>
              <b>{c.name}</b>
              {c.description ? <span>{c.description}</span> : null}
            </div>
          ))}
        </div>
      ) : null}
      {quotes.length ? (
        <div className="quotes">
          {quotes.map((q, i) => (
            <div className="q" key={i}>
              <span>{q}</span>
              <button type="button" className="icon" style={{ width: 24, height: 24, fontSize: 14 }} onClick={() => setQuotes(quotes.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}
        </div>
      ) : null}
      {files.length ? (
        <div className="staged">
          {files.map((f, i) => (
            <div key={i}>
              📎 {f.name}
              <span style={{ cursor: 'pointer' }} onClick={() => setFiles(files.filter((_, j) => j !== i))}>✕</span>
            </div>
          ))}
        </div>
      ) : null}
      <div className="line">
        {rec ? (
          <>
            <button type="button" className="icon" aria-label="Cancel recording" onClick={() => void stopMic(false)}>✕</button>
            <div className="rec">
              <span>● {Math.floor(secs / 60)}:{String(secs % 60).padStart(2, '0')}</span>
              <div className="lvl"><i style={{ width: `${Math.round(level * 100)}%` }} /></div>
            </div>
            <button type="button" className="send" aria-label="Done" onClick={() => void stopMic(true)}>✓</button>
          </>
        ) : (
          <>
            <button type="button" className="icon" aria-label="Attach" onClick={() => fileIn.current?.click()}>＋</button>
            <input ref={fileIn} type="file" multiple hidden onChange={(e) => { void pick(e.target.files); e.target.value = ''; }} />
            <textarea
              ref={ta}
              rows={1}
              value={text}
              placeholder={busy || (running ? 'Add to the queue…' : 'Message')}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                // Enter sends on a keyboard; on a phone, Return is a new line and the button sends.
                if (e.key === 'Enter' && !e.shiftKey && window.matchMedia('(pointer: fine)').matches) {
                  e.preventDefault();
                  submit();
                }
              }}
            />
            {!canSend && !running ? (
              <button type="button" className="icon" aria-label="Dictate" onClick={() => void startMic()} disabled={!!busy}>🎙</button>
            ) : null}
            {running && !canSend ? (
              <button type="button" className="send stop" aria-label="Stop" onClick={onStop}>■</button>
            ) : (
              <button type="button" className="send" aria-label="Send" disabled={!canSend} onMouseDown={(e) => e.preventDefault()} onClick={submit}>↑</button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
