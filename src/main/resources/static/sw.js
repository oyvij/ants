// The Ants app's service worker, at the site root so it covers every page. It shows each Alert Ants sends, and tapping
// one opens Ants on its entry, or focuses an open Ants window and takes it there. It caches no pages.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('push', event => {
    const alert = event.data?.json() ?? {};
    const title = [alert.icon, alert.tag, alert.run ?? alert.title].filter(Boolean).join(' ') || 'Ants';
    const body = [alert.run && alert.title, alert.text].filter(Boolean).join(': ');
    event.waitUntil(self.registration.showNotification(title, { body, icon: '/icon-192.png', tag: alert.url, data: { url: alert.url || '/notifications' } }));
});

self.addEventListener('notificationclick', event => {
    event.notification.close();
    const url = new URL(event.notification.data?.url || '/notifications', self.location.origin).href;
    event.waitUntil((async () => {
        const open = (await self.clients.matchAll({ type: 'window', includeUncontrolled: true })).find(client => new URL(client.url).origin === self.location.origin);
        if (!open) return self.clients.openWindow(url);
        await open.navigate(url);
        return open.focus();
    })());
});
