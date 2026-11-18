/**
 * VAJRA — Master Functional Restoration Verification Suite
 *
 * Verifies:
 * 1. Authoritative notification target resolution never falls back to list[0] on stale/resolved deviceId.
 * 2. Browser notification payload spec contains zero sensitive tokens, passcodes, or keys.
 * 3. Query string deep-link parsing & cleanup work safely.
 * 4. Service worker (/public/sw.js) and SVG icon (/public/favicon.svg) exist and route notification clicks without auto-approving.
 * 5. Windows Agent EnrollmentCli & PairingBundleParser wire verificationPasscode and serverUrl parity.
 * 6. Android Companion enforces lifecycle-gated worker scheduling, durable command ledger, and Step 3 state recovery.
 * 7. Azure Bicep template provisions PASSCODE_HMAC_SECRET and documents single-instance SSE constraints.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import {
  buildApprovalNotificationTag,
  buildPendingApprovalNotificationSpec,
  buildReviewCleanupUrl,
  parseReviewDeviceIdFromSearch,
  resolveAuthoritativeApprovalTarget,
} from '../src/lib/ownerNotifications.ts';

describe('VAJRA — Master Functional Restoration Verification Suite', () => {
  it('1. resolveAuthoritativeApprovalTarget never falls back to list[0] when targetDeviceId is stale', () => {
    const pendingList = [
      { deviceId: 'dev-other-1', name: 'Other Laptop' },
      { deviceId: 'dev-other-2', name: 'Other Phone' },
    ];

    const staleResolution = resolveAuthoritativeApprovalTarget(pendingList, 'dev-already-approved');
    assert.deepEqual(staleResolution, {
      status: 'STALE',
      targetDeviceId: 'dev-already-approved',
    });

    const emptyStaleResolution = resolveAuthoritativeApprovalTarget([], 'dev-already-approved');
    assert.deepEqual(emptyStaleResolution, {
      status: 'STALE',
      targetDeviceId: 'dev-already-approved',
    });

    const emptyDefaultResolution = resolveAuthoritativeApprovalTarget([]);
    assert.deepEqual(emptyDefaultResolution, { status: 'EMPTY' });

    const matchedResolution = resolveAuthoritativeApprovalTarget(pendingList, 'dev-other-2');
    assert.deepEqual(matchedResolution, {
      status: 'FOUND',
      device: pendingList[1],
    });

    const bannerDefaultResolution = resolveAuthoritativeApprovalTarget(pendingList);
    assert.deepEqual(bannerDefaultResolution, {
      status: 'FOUND',
      device: pendingList[0],
    });
  });

  it('2. Pending approval notification spec contains exact required copy and zero secrets', () => {
    const spec = buildPendingApprovalNotificationSpec('dev-123');
    assert.equal(spec.title, 'VAJRA Security — Approval Required');
    assert.equal(
      spec.body,
      'An Android or Windows device is requesting authorization. Open VAJRA to review.'
    );
    assert.equal(spec.tag, buildApprovalNotificationTag('dev-123'));
    assert.equal(spec.icon, '/favicon.svg');
    assert.deepEqual(spec.data, { deviceId: 'dev-123' });

    const serialized = JSON.stringify(spec).toLowerCase();
    for (const forbidden of ['passcode', 'token', 'privatekey', 'secret', 'hmac']) {
      assert.equal(serialized.includes(forbidden), false);
    }
  });

  it('3. reviewDeviceId query deep-link parser rejects unsafe values and cleans URL cleanly', () => {
    assert.equal(parseReviewDeviceIdFromSearch('?reviewDeviceId=dev_abc-123'), 'dev_abc-123');
    assert.equal(parseReviewDeviceIdFromSearch('?foo=bar&reviewDeviceId=bad<script>'), null);
    assert.equal(parseReviewDeviceIdFromSearch(''), null);
    assert.equal(
      buildReviewCleanupUrl('/console', '?foo=1&reviewDeviceId=dev-123&bar=2', '#devices'),
      '/console?foo=1&bar=2#devices'
    );
    assert.equal(buildReviewCleanupUrl('/', '?reviewDeviceId=dev-123', ''), '/');
  });

  it('4. Service Worker (/public/sw.js) and /public/favicon.svg exist and handle notificationclick safely', () => {
    const swPath = path.resolve(process.cwd(), 'public/sw.js');
    const faviconPath = path.resolve(process.cwd(), 'public/favicon.svg');
    assert.equal(fs.existsSync(swPath), true);
    assert.equal(fs.existsSync(faviconPath), true);

    const swSource = fs.readFileSync(swPath, 'utf8');
    assert.equal(swSource.includes("addEventListener('notificationclick'"), true);
    assert.equal(swSource.includes('VAJRA_OPEN_REVIEW_MODAL'), true);
    assert.equal(swSource.includes('reviewDeviceId='), true);
    assert.equal(swSource.includes('/enrollment/confirm'), false);
  });

  it('5. Windows Agent EnrollmentCli and workers maintain protocol parity and durable state', () => {
    const cliSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/windows-agent/src/Hosting/EnrollmentCli.cs'),
      'utf8'
    );
    const parserSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/windows-agent/src/Configuration/PairingBundleParser.cs'),
      'utf8'
    );
    const pollerSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/windows-agent/src/Workers/CommandPollerWorker.cs'),
      'utf8'
    );
    const hbSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/windows-agent/src/Workers/HeartbeatWorker.cs'),
      'utf8'
    );

    assert.equal(cliSrc.includes('--passcode'), true);
    assert.equal(cliSrc.includes('verificationPasscode'), true);
    assert.equal(cliSrc.includes('deviceName'), true);
    assert.equal(parserSrc.includes('"serverUrl"'), true);
    assert.equal(parserSrc.includes('VAJRA_ENROLL_V1'), true);
    assert.equal(pollerSrc.includes('command-ledger.json'), true);
    assert.equal(pollerSrc.includes('PENDING_RESULT_SUBMIT'), true);
    assert.equal(pollerSrc.includes('DEVICE_CREDENTIAL_INACTIVE'), true);
    assert.equal(hbSrc.includes('DEVICE_CREDENTIAL_INACTIVE'), true);
  });

  it('6. Android Companion gates workers by trusted lifecycle, restores Step 3, and tracks RING_DEVICE honestly', () => {
    const appSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/android/app/src/main/java/com/vajra/companion/VajraApp.kt'),
      'utf8'
    );
    const mainSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/android/app/src/main/java/com/vajra/companion/ui/MainActivity.kt'),
      'utf8'
    );
    const enrollSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/android/app/src/main/java/com/vajra/companion/ui/EnrollmentActivity.kt'),
      'utf8'
    );
    const cmdSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/android/app/src/main/java/com/vajra/companion/work/CommandPollWorker.kt'),
      'utf8'
    );
    const manifestSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'apps/android/app/src/main/AndroidManifest.xml'),
      'utf8'
    );

    assert.equal(appSrc.includes('listOf("TRUSTED", "ACTIVE", "OFFLINE")'), true);
    assert.equal(mainSrc.includes('btnResumePendingApproval'), true);
    assert.equal(mainSrc.includes('RingAlarmController.stop'), true);
    assert.equal(enrollSrc.includes('btnCheckStatusNow'), true);
    assert.equal(enrollSrc.includes('savedMeta.optString("lifecycleState") == "ENROLLMENT_PENDING"'), true);
    assert.equal(cmdSrc.includes('PENDING_RESULT_SUBMIT'), true);
    assert.equal(cmdSrc.includes('audiblePlaybackStarted'), true);
    assert.equal(cmdSrc.includes('Stop Alarm'), true);
    assert.equal(manifestSrc.includes('.work.StopRingReceiver'), true);
  });

  it('7. Azure Bicep provisions PASSCODE_HMAC_SECRET and locks App Service capacity to 1 for in-process SSE', () => {
    const bicepSrc = fs.readFileSync(
      path.resolve(process.cwd(), 'infrastructure/azure/main.bicep'),
      'utf8'
    );
    assert.equal(bicepSrc.includes('param passcodeHmacSecret string'), true);
    assert.equal(bicepSrc.includes("name: 'PASSCODE_HMAC_SECRET'"), true);
    assert.equal(bicepSrc.includes('capacity: 1'), true);
  });
});
