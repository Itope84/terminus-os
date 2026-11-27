// The app-wide live link: /api/pwa/events tells the app about chat changes and approvals while it's
// open. Presence (which chat is on screen) means no push arrives for what you're already reading.
import { call } from './api';
import { openSse } from './sse';
import { dropChat, loadChats, putChat, useApp } from './store';
import type { Approval, Chat } from './types';

let link: { close: () => void; kick: () => void } | null = null;

function sendPresence() {
  const visible = typeof document === 'undefined' || document.visibilityState === 'visible';
  void call('POST', '/api/pwa/presence', { visible, chatId: useApp.getState().openChat }).catch(() => {});
}

export function startEvents() {
  if (link) return;
  link = openSse(
    () => `/api/pwa/events?chatId=${encodeURIComponent(useApp.getState().openChat)}`,
    {
      onOpen: () => {
        useApp.getState().set({ online: true });
        // Anything missed while away: the list and the open approvals.
        void loadChats().catch(() => {});
        void call<{ approvals: Approval[] }>('GET', '/api/pwa/approvals')
          .then((r) => useApp.getState().set({ approvals: r.approvals }))
          .catch(() => {});
        sendPresence();
      },
      onDown: () => useApp.getState().set({ online: false }),
      onEvent: (event, data) => {
        const d = JSON.parse(data);
        if (event === 'chat') putChat(d as Chat);
        else if (event === 'chat_deleted') dropChat(String(d.id));
        else if (event === 'approval') {
          const list = useApp.getState().approvals.filter((a) => a.id !== d.id);
          useApp.getState().set({ approvals: [...list, d as Approval] });
        } else if (event === 'approval_cancel') {
          useApp.getState().set({ approvals: useApp.getState().approvals.filter((a) => a.id !== d.id) });
        }
      },
    },
    { stallMs: 50_000 },
  );
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') link?.kick();
      sendPresence();
    });
    window.addEventListener('online', () => link?.kick());
  }
  useApp.subscribe((s, prev) => {
    if (s.openChat !== prev.openChat) sendPresence();
  });
}

export function stopEvents() {
  link?.close();
  link = null;
}

/** Bring every live link back now (the app returned to the foreground). */
export function kickEvents() {
  link?.kick();
}
