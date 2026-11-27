// Notifications with the app closed (Web Push). On iPhone this works only from the Home Screen app.
import { call } from './api';
import { toast } from './store';

export function pushSupported(): boolean {
  return typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof window !== 'undefined' && 'PushManager' in window;
}

export function isStandalone(): boolean {
  if (typeof window === 'undefined') return false;
  return window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;
}

export async function pushState(): Promise<'on' | 'off' | 'blocked' | 'unsupported'> {
  if (!pushSupported()) return 'unsupported';
  if (Notification.permission === 'denied') return 'blocked';
  const reg = await navigator.serviceWorker.getRegistration('/app/');
  const sub = await reg?.pushManager.getSubscription();
  return sub ? 'on' : 'off';
}

const keyBytes = (b64: string) => {
  const raw = atob(b64.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
};

export async function turnOnNotifications(): Promise<boolean> {
  try {
    if (!pushSupported()) {
      toast(isStandalone() ? 'This browser can’t do notifications.' : 'On iPhone, add Terminus to the Home Screen first (Share → Add to Home Screen), then turn notifications on from there.');
      return false;
    }
    if ((await Notification.requestPermission()) !== 'granted') {
      toast('Notifications are off for Terminus in Settings.');
      return false;
    }
    const reg = await navigator.serviceWorker.ready;
    const { publicKey } = await call<{ publicKey: string }>('GET', '/api/pwa/push');
    const sub = (await reg.pushManager.getSubscription()) ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) }));
    await call('POST', '/api/pwa/push/subscribe', { subscription: sub.toJSON(), label: navigator.userAgent.includes('iPhone') ? 'iPhone' : 'Browser' });
    const r = await call<{ sent: number; errors: string[] }>('POST', '/api/pwa/push/test');
    toast(r.errors.length ? `Notifications on, but: ${r.errors[0]}` : 'Notifications on. A test is on its way.');
    return true;
  } catch (e) {
    toast(`Couldn’t turn notifications on: ${(e as Error).message}`);
    return false;
  }
}

export async function turnOffNotifications() {
  const reg = await navigator.serviceWorker.getRegistration('/app/');
  const sub = await reg?.pushManager.getSubscription();
  if (sub) {
    await call('POST', '/api/pwa/push/unsubscribe', { endpoint: sub.endpoint }).catch(() => {});
    await sub.unsubscribe().catch(() => {});
  }
  toast('Notifications off on this device.');
}
