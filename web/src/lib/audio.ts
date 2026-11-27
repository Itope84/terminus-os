// Microphone clips for dictation and voice notes. Safari records AAC in MP4; the Mac turns it into
// text with whisper.cpp (POST /api/pwa/transcribe). Recording only runs while the app is on screen:
// iOS stops a web app's microphone when it goes to the background.
import { call } from './api';

export interface Recorder {
  stop: () => Promise<Blob>;
  cancel: () => void;
  /** 0..1, for a level meter. */
  level: () => number;
  startedAt: number;
}

export function recorderSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== 'undefined';
}

export async function startRecording(): Promise<Recorder> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  const type = ['audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'].find((t) => MediaRecorder.isTypeSupported(t));
  const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
  const chunks: Blob[] = [];
  rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  rec.start(1000);
  // Level meter
  let analyser: AnalyserNode | null = null;
  let ctx: AudioContext | null = null;
  try {
    ctx = new AudioContext();
    analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    ctx.createMediaStreamSource(stream).connect(analyser);
  } catch {
    /* no meter */
  }
  const buf = new Uint8Array(128);
  const end = () => {
    stream.getTracks().forEach((t) => t.stop());
    void ctx?.close().catch(() => {});
  };
  return {
    startedAt: Date.now(),
    level: () => {
      if (!analyser) return 0;
      analyser.getByteTimeDomainData(buf);
      let peak = 0;
      for (const v of buf) peak = Math.max(peak, Math.abs(v - 128));
      return Math.min(1, peak / 64);
    },
    stop: () =>
      new Promise<Blob>((resolve) => {
        rec.onstop = () => {
          end();
          resolve(new Blob(chunks, { type: rec.mimeType || 'audio/mp4' }));
        };
        rec.stop();
      }),
    cancel: () => {
      rec.onstop = null;
      try {
        rec.stop();
      } catch {
        /* already stopped */
      }
      end();
    },
  };
}

export async function transcribe(clip: Blob): Promise<string> {
  const r = await call<{ text: string }>('POST', '/api/pwa/transcribe', undefined, {
    raw: clip,
    contentType: clip.type || 'application/octet-stream',
    timeoutMs: 120_000,
  });
  return r.text;
}

export const blobToBase64 = (b: Blob): Promise<string> =>
  new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] ?? '');
    r.onerror = () => reject(r.error);
    r.readAsDataURL(b);
  });
