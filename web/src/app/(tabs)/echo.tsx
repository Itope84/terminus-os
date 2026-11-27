// Echo: voice notes. Record, and the clip goes to the Mac's gallery (POST /echo), where agents can
// read it. Each note can be turned into text on the Mac and sent to a chat. Recording stops if you
// leave the app or lock the phone (iOS doesn't let a web app use the microphone in the background).
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { call } from '../../lib/api';
import { type Recorder, recorderSupported, startRecording, transcribe } from '../../lib/audio';
import { newChatId } from '../../lib/ids';
import { defaultBackend, toast, updateChat } from '../../lib/store';
import { send } from '../../lib/thread';

interface Note {
  name: string;
  at: number;
  secs: number;
  label?: string;
  url?: string;
  text?: string;
  state: 'uploading' | 'saved' | 'failed';
}

const KEY = 'terminus.echo.v1';
const read = (): Note[] => {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '[]');
  } catch {
    return [];
  }
};
const write = (n: Note[]) => {
  try {
    localStorage.setItem(KEY, JSON.stringify(n.slice(0, 60)));
  } catch {
    /* ignore */
  }
};

// Clips that haven't reached the Mac yet, kept in memory for a retry (a reload loses them).
const pending = new Map<string, Blob>();

const stamp = (d: Date) => `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}-${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}${String(d.getSeconds()).padStart(2, '0')}`;

export default function Echo() {
  const router = useRouter();
  const [notes, setNotes] = useState<Note[]>(read);
  const [rec, setRec] = useState<Recorder | null>(null);
  const [secs, setSecs] = useState(0);
  const [level, setLevel] = useState(0);
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState('');

  const save = (fn: (n: Note[]) => Note[]) =>
    setNotes((cur) => {
      const next = fn(cur);
      write(next);
      return next;
    });

  useEffect(() => {
    if (!rec) return;
    const t = setInterval(() => {
      setSecs(Math.floor((Date.now() - rec.startedAt) / 1000));
      setLevel(rec.level());
    }, 120);
    return () => clearInterval(t);
  }, [rec]);

  const upload = async (note: Note, clip: Blob) => {
    save((n) => n.map((x) => (x.name === note.name ? { ...x, state: 'uploading' } : x)));
    try {
      const r = await call<{ url: string }>('POST', `/echo?filename=${encodeURIComponent(note.name)}`, undefined, {
        raw: clip,
        contentType: clip.type.startsWith('audio/') ? clip.type.split(';')[0] : 'audio/mp4',
        timeoutMs: 120_000,
      });
      pending.delete(note.name);
      // The server answers with its own address; the same path works here.
      const path = new URL(r.url, location.origin).pathname;
      save((n) => n.map((x) => (x.name === note.name ? { ...x, state: 'saved', url: path } : x)));
    } catch (e) {
      save((n) => n.map((x) => (x.name === note.name ? { ...x, state: 'failed' } : x)));
      toast(`Not saved yet: ${(e as Error).message}`);
    }
  };

  const toggle = async () => {
    if (rec) {
      const r = rec;
      setRec(null);
      const clip = await r.stop();
      const secsTaken = Math.round((Date.now() - r.startedAt) / 1000);
      const ext = clip.type.includes('webm') ? 'ogg' : 'm4a';
      const slug = label.trim().replace(/[^\w-]+/g, '-').slice(0, 30);
      const note: Note = { name: `echo-${stamp(new Date(r.startedAt))}${slug ? `-${slug}` : ''}.${ext}`, at: r.startedAt, secs: secsTaken, label: label.trim() || undefined, state: 'uploading' };
      pending.set(note.name, clip);
      save((n) => [note, ...n]);
      void upload(note, clip);
      return;
    }
    if (!recorderSupported()) return toast('This browser can’t record. Open Terminus from the Home Screen.');
    try {
      setRec(await startRecording());
    } catch {
      toast('Microphone is off for Terminus.');
    }
  };

  const toText = async (note: Note) => {
    setBusy(note.name);
    try {
      const clip = pending.get(note.name) ?? (note.url ? await (await fetch(note.url, { credentials: 'same-origin' })).blob() : null);
      if (!clip) throw new Error('The recording isn’t on the Mac yet.');
      const text = await transcribe(clip);
      save((n) => n.map((x) => (x.name === note.name ? { ...x, text: text || '(nothing heard)' } : x)));
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setBusy('');
    }
  };

  const toChat = async (note: Note) => {
    const id = newChatId();
    await updateChat(id, { title: note.label || 'Voice note', backend: defaultBackend(), kind: 'chat', touch: true });
    void send(id, `${note.text ? `Voice note${note.label ? ` (${note.label})` : ''}, transcribed:\n\n${note.text}` : 'A voice note'}\n\nThe recording is on the Mac at ${note.url ?? note.name}.`);
    router.push(`/chat/${id}`);
  };

  return (
    <>
      <div className="bar">
        <h1>Echo</h1>
      </div>
      <div className="body">
        <div className="echo-rec">
          <input className="field" style={{ maxWidth: 320 }} placeholder="Label (optional)" value={label} onChange={(e) => setLabel(e.target.value)} disabled={!!rec} />
          <button type="button" className={`echo-btn${rec ? ' on' : ''}`} onClick={() => void toggle()}>
            {rec ? `■ ${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}` : 'Record'}
          </button>
          {rec ? (
            <div style={{ width: 200, height: 6, borderRadius: 3, background: 'var(--chip)', overflow: 'hidden' }}>
              <div style={{ width: `${Math.round(level * 100)}%`, height: '100%', background: 'var(--err)' }} />
            </div>
          ) : (
            <p style={{ color: 'var(--muted)', fontSize: 13, textAlign: 'center', margin: 0, maxWidth: 320 }}>
              Notes go to the Mac’s Files. Keep Terminus open while recording: iPhone stops it if you leave the app or lock the screen.
            </p>
          )}
        </div>
        {notes.map((n) => (
          <div key={n.name} className="note-row">
            <div className="top">
              <b style={{ flex: 1 }}>{n.label || new Date(n.at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</b>
              <span style={{ color: 'var(--faint)' }}>{Math.floor(n.secs / 60)}:{String(n.secs % 60).padStart(2, '0')}</span>
              <span style={{ color: n.state === 'failed' ? 'var(--err)' : 'var(--faint)', fontSize: 12 }}>{n.state === 'uploading' ? 'saving…' : n.state === 'failed' ? 'not saved' : 'on the Mac'}</span>
            </div>
            {n.url ? <audio controls preload="none" src={n.url} /> : null}
            {n.text ? <div className="txt">{n.text}</div> : null}
            <div className="acts">
              {n.state === 'failed' && pending.has(n.name) ? <button type="button" onClick={() => void upload(n, pending.get(n.name)!)}>Retry</button> : null}
              {!n.text ? <button type="button" disabled={busy === n.name} onClick={() => void toText(n)}>{busy === n.name ? 'Transcribing…' : 'Transcribe'}</button> : null}
              {n.state === 'saved' ? <button type="button" onClick={() => void toChat(n)}>Send to a chat</button> : null}
              <button type="button" style={{ color: 'var(--faint)' }} onClick={() => save((x) => x.filter((y) => y.name !== n.name))}>Hide</button>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
