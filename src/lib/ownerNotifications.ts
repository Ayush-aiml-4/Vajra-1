export const VAJRA_APPROVAL_NOTIFICATION_TAG_PREFIX = 'vajra-approval-';
export const VAJRA_OWNER_SYNC_CHANNEL = 'vajra_owner_sync';

export interface PendingApprovalIdentity {
  deviceId: string;
}

export type AuthoritativeApprovalTargetResult<T extends PendingApprovalIdentity> =
  | { status: 'EMPTY' }
  | { status: 'STALE'; targetDeviceId: string }
  | { status: 'FOUND'; device: T };

/**
 * Resolves the target pending device strictly against the authoritative backend list.
 * When a notification references a specific deviceId that is no longer pending, this
 * returns STALE instead of falling back to an unrelated device in pendingList[0].
 */
export function resolveAuthoritativeApprovalTarget<T extends PendingApprovalIdentity>(
  pendingList: readonly T[],
  targetDeviceId?: string
): AuthoritativeApprovalTargetResult<T> {
  const normalizedTarget = typeof targetDeviceId === 'string' ? targetDeviceId.trim() : '';

  if (normalizedTarget.length > 0) {
    const exactMatch = pendingList.find((item) => item.deviceId === normalizedTarget);
    if (!exactMatch) {
      return { status: 'STALE', targetDeviceId: normalizedTarget };
    }
    return { status: 'FOUND', device: exactMatch };
  }

  if (pendingList.length === 0) {
    return { status: 'EMPTY' };
  }

  return { status: 'FOUND', device: pendingList[0] };
}

export function buildApprovalNotificationTag(deviceId: string): string {
  return `${VAJRA_APPROVAL_NOTIFICATION_TAG_PREFIX}${deviceId.trim()}`;
}

export interface PendingApprovalNotificationSpec {
  title: string;
  body: string;
  icon: string;
  tag: string;
  requireInteraction: boolean;
  data: {
    deviceId: string;
  };
}

/**
 * Builds browser notification metadata with zero enrollment tokens, passcodes, or keys.
 */
export function buildPendingApprovalNotificationSpec(
  deviceId: string
): PendingApprovalNotificationSpec {
  const cleanId = deviceId.trim();
  return {
    title: 'VAJRA Security — Approval Required',
    body: 'An Android or Windows device is requesting authorization. Open VAJRA to review.',
    icon: '/favicon.svg',
    tag: buildApprovalNotificationTag(cleanId),
    requireInteraction: true,
    data: {
      deviceId: cleanId,
    },
  };
}

export function parseReviewDeviceIdFromSearch(search: string): string | null {
  if (!search) return null;
  try {
    const params = new URLSearchParams(search.startsWith('?') ? search : `?${search}`);
    const raw = params.get('reviewDeviceId')?.trim() ?? '';
    if (!raw || raw.length > 128) return null;
    if (!/^[A-Za-z0-9_-]+$/.test(raw)) return null;
    return raw;
  } catch {
    return null;
  }
}

export function buildReviewCleanupUrl(pathname: string, search: string, hash: string): string {
  try {
    const params = new URLSearchParams(search.startsWith('?') ? search : `?${search}`);
    params.delete('reviewDeviceId');
    const query = params.toString();
    return `${pathname || '/'}${query ? `?${query}` : ''}${hash || ''}`;
  } catch {
    return pathname || '/';
  }
}

export type OwnerSyncMessage =
  | { type: 'DEVICE_NOTIFIED'; deviceId: string }
  | { type: 'DEVICE_RESOLVED'; deviceId: string; resolution: 'APPROVED' | 'REJECTED' }
  | { type: 'PENDING_UPDATED' };
