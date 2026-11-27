// Terminus web app service worker: keeps the app's files so it opens with no connection, and shows
// push notifications. Chat data is never cached here (it changes by the second); sends made offline
// wait in the app's outbox (src/lib/outbox.ts). scripts/build-sw.mjs fills BUILD and SHELL after
// `expo export`; left as they are, nothing is precached.
const BUILD = '__TERMINUS_BUILD__';
const SHELL = [] /* __TERMINUS_SHELL__ */;
const SHELL_CACHE = `shell-${BUILD}`;
const TIMEOUT_MS = 5000;
const PAGE = '/app/';

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    if (SHELL.length) {
      const cache = await caches.open(SHELL_CACHE);
      await Promise.all(SHELL.map(async (path) => {
        try {
          const res = await fetch(path, { cache: 'reload' });
          if (res.ok) await cache.put(path, await plain(res));
          else if (path === PAGE) throw new Error(`${PAGE} answered ${res.status}`);
        } catch (e) {
          if (path === PAGE) throw e;
        }
      }));
    }
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith('shell-') && name !== SHELL_CACHE) await caches.delete(name);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || !SHELL.length) return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith('/app')) return;
  if (req.mode === 'navigate') return event.respondWith(page(req));
  event.respondWith(fromShell(req, url));
});

self.addEventListener('push', (event) => {
  let data = { title: 'Terminus', body: '' };
  try { data = event.data ? event.data.json() : data; } catch (e) { data.body = event.data ? event.data.text() : ''; }
  event.waitUntil(self.registration.showNotification(data.title || 'Terminus', {
    body: data.body || '',
    icon: '/app/icon-192.png',
    badge: '/app/icon-192.png',
    tag: data.tag || undefined,
    data: { url: data.url || PAGE },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || PAGE;
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of all) {
      if ('focus' in c) {
        // The app listens for this and opens the chat without a reload.
        c.postMessage({ type: 'open', url: target });
        return c.focus();
      }
    }
    return self.clients.openWindow(target);
  })());
});

async function fromShell(req, url) {
  const cache = await caches.open(SHELL_CACHE);
  const saved = await cache.match(url.pathname);
  if (saved) return saved;
  return fetch(req);
}

/** Pages: the network when it answers in time (an online launch gets the newest app), else this build's page. */
async function page(req) {
  const cache = await caches.open(SHELL_CACHE);
  try {
    return await Promise.race([fetch(req), new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), TIMEOUT_MS))]);
  } catch (e) {
    return (await cache.match(PAGE)) || fetch(req);
  }
}

/** Safari refuses redirected responses for pages; store a plain copy. */
async function plain(res) {
  return new Response(await res.blob(), { status: res.status, headers: res.headers });
}
