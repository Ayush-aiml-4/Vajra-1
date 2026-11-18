/* VAJRA Console Service Worker — Notification Dispatch & Click Routing */

const APPROVAL_TAG_PREFIX = 'vajra-approval-';

self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('notificationclick', (event) => {
  const notification = event.notification;
  let deviceId = '';
  if (notification && notification.data && typeof notification.data.deviceId === 'string') {
    deviceId = notification.data.deviceId.trim();
  } else if (notification && typeof notification.tag === 'string' && notification.tag.startsWith(APPROVAL_TAG_PREFIX)) {
    deviceId = notification.tag.slice(APPROVAL_TAG_PREFIX.length).trim();
  }

  if (notification && typeof notification.close === 'function') {
    notification.close();
  }

  const targetUrl = deviceId
    ? `/?reviewDeviceId=${encodeURIComponent(deviceId)}`
    : '/';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
      for (const client of windowClients) {
        if (!client || typeof client.url !== 'string') continue;
        try {
          const clientUrl = new URL(client.url);
          if (clientUrl.origin === self.location.origin) {
            if (typeof client.postMessage === 'function') {
              client.postMessage({
                type: 'VAJRA_OPEN_REVIEW_MODAL',
                deviceId: deviceId || undefined,
              });
            }
            if (typeof client.focus === 'function') {
              return client.focus();
            }
          }
        } catch {
          // Ignore malformed client URL
        }
      }

      if (self.clients.openWindow) {
        return self.clients.openWindow(targetUrl);
      }
      return undefined;
    })
  );
});
