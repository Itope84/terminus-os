// Calls to the Terminus server. The app is served by the same server, so every call is same-origin
// and the login cookie (set by /terminus-login) carries the credential. No token is kept in the page.

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly body?: unknown,
  ) {
    super(message);
  }
}

type Listener = () => void;
const signedOutListeners = new Set<Listener>();
/** Told when any call answers 401: the cookie is gone or the token changed. */
export function onSignedOut(fn: Listener): () => void {
  signedOutListeners.add(fn);
  return () => signedOutListeners.delete(fn);
}

export async function call<T = unknown>(
  method: 'GET' | 'POST' | 'DELETE',
  path: string,
  body?: unknown,
  opts: { timeoutMs?: number; raw?: BodyInit; contentType?: string; signal?: AbortSignal } = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  let payload: BodyInit | undefined;
  if (opts.raw !== undefined) {
    payload = opts.raw;
    if (opts.contentType) headers['Content-Type'] = opts.contentType;
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), opts.timeoutMs ?? 30_000);
  opts.signal?.addEventListener('abort', () => ctl.abort());
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: payload, credentials: 'same-origin', signal: ctl.signal });
  } catch (e) {
    throw new ApiError(0, ctl.signal.aborted ? 'The Mac took too long to answer.' : 'Can’t reach the Mac.');
  } finally {
    clearTimeout(timer);
  }
  const text = await res.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = text;
  }
  if (res.status === 401) {
    for (const fn of signedOutListeners) fn();
    throw new ApiError(401, 'Signed out.');
  }
  if (!res.ok) {
    const err = data && typeof data === 'object' && 'error' in data ? String((data as { error: unknown }).error) : '';
    const msg = err || `The Mac answered ${res.status}.`;
    throw new ApiError(res.status, msg, data);
  }
  return data as T;
}

export const isOffline = (e: unknown): boolean => e instanceof ApiError && e.status === 0;
