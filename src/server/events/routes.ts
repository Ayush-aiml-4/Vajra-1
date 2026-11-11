/**
 * VAJRA — Authenticated SSE Event Stream Routes
 * Prompt 0053: Cross-Device Owner Approval Notification & Tab Synchronization
 *
 * Dispatches real-time owner notifications: DEVICE_AWAITING_APPROVAL, DEVICE_CONNECTED, DEVICE_REJECTED.
 *
 * Invariants:
 * - Strictly owner-authenticated via session cookie.
 * - Emits RFC-compliant SSE comment lines (": keep-alive") every 15 seconds to prevent gateway timeouts.
 * - Comment lines are ignored by client EventSource parsers and never produce fake business events.
 * - Cleans up intervals, broadcaster registrations, and event listeners on disconnect to prevent leaks.
 */

import { Router, Request, Response } from 'express';
import { requireAuthenticatedUser } from '../auth/middleware.ts';
import { ownerEventBroadcaster } from './ownerStream.ts';

/**
 * Handles the authenticated SSE connection for the owner.
 */
export function handleOwnerSseStream(req: Request, res: Response): void {
  const user = req.authContext?.user;
  if (!user) {
    res.status(401).json({
      error: { code: 'UNAUTHENTICATED', message: 'Authentication required.' },
    });
    return;
  }

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache, no-transform');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.flushHeaders?.();

  // Initial RFC-compliant SSE comment (starts with ':')
  res.write(`: connected at ${new Date().toISOString()}\n\n`);

  // Connection-specific keep-alive heartbeat comment every 15 seconds
  const keepAliveTimer = setInterval(() => {
    if (!res.writableEnded && res.writable) {
      try {
        res.write(`: keep-alive ${Date.now()}\n\n`);
        if (typeof (res as any).flush === 'function') {
          (res as any).flush();
        }
      } catch {
        cleanup();
      }
    } else {
      cleanup();
    }
  }, 15_000);

  if (keepAliveTimer.unref) {
    keepAliveTimer.unref();
  }

  // Register client with broadcaster
  const unregisterBroadcaster = ownerEventBroadcaster.registerClient(user.id, res);

  let isCleanedUp = false;
  const cleanup = () => {
    if (isCleanedUp) return;
    isCleanedUp = true;

    clearInterval(keepAliveTimer);
    unregisterBroadcaster();

    req.removeListener('close', cleanup);
    res.removeListener('close', cleanup);
    res.removeListener('error', cleanup);
    res.removeListener('finish', cleanup);
  };

  req.on('close', cleanup);
  res.on('close', cleanup);
  res.on('error', cleanup);
  res.on('finish', cleanup);
}

export function createEventRouter(): Router {
  const router = Router();
  router.get('/events/stream', requireAuthenticatedUser, handleOwnerSseStream);
  return router;
}
