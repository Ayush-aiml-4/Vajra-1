package com.vajra.companion

import com.vajra.companion.identity.DeviceStatusResolver
import com.vajra.companion.network.ServerUrlValidator
import com.vajra.companion.ui.EnrollmentActivity
import com.vajra.companion.work.RingExecutionHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Focused regression tests for:
 * 1. Strict URI hostname & scheme validation (anti-spoofing, credential rejection, exact loopback rules).
 * 2. Authoritative device lifecycle resolution (COMPROMISED never maps to TRUSTED).
 * 3. Separated RingExecutionHelper result fields (audio, notification, DND, stop controls).
 */
class SecurityRegressionTest {

    @Test
    fun urlValidator_allowsApprovedLoopbacksAndHttps() {
        assertEquals("http://localhost:3000", ServerUrlValidator.normalizeAndValidate("http://localhost:3000/"))
        assertEquals("http://127.0.0.1:3000", ServerUrlValidator.normalizeAndValidate("http://127.0.0.1:3000"))
        assertEquals("http://10.0.2.2:3000", ServerUrlValidator.normalizeAndValidate("http://10.0.2.2:3000"))
        assertEquals("http://[::1]:3000", ServerUrlValidator.normalizeAndValidate("http://[::1]:3000/"))
        assertEquals("https://vajra.security.example.com", ServerUrlValidator.normalizeAndValidate("https://vajra.security.example.com/"))
        assertEquals("https://vajra.security.example.com:8443", ServerUrlValidator.normalizeAndValidate("https://vajra.security.example.com:8443"))
    }

    @Test
    fun urlValidator_rejectsHostnameSpoofingAndPublicHttp() {
        val spoofedOrInsecure = listOf(
            "http://localhost.attacker.com:3000",
            "http://127.0.0.1.nip.io:3000",
            "http://10.0.2.2.evil.example",
            "http://evil.example/localhost",
            "http://vajra.example.com",
            "http://192.168.1.50:3000",
            "https://user:pass@vajra.example.com",
            "http://localhost@evil.com:3000",
            "ftp://vajra.example.com",
            "javascript:alert(1)",
            "https://vajra.example.com?token=leak",
            "https://vajra.example.com#frag",
            "https://bad host.example.com",
            "not a url",
            ""
        )

        for (candidate in spoofedOrInsecure) {
            val res = ServerUrlValidator.validate(candidate)
            assertFalse("Expected URL to be rejected: $candidate", res.valid)
            assertNull("Expected null normalizedUrl for rejected URL: $candidate", res.normalizedUrl)
        }
    }

    @Test
    fun pairingBundle_rejectsSpoofedLoopbackAndEmbeddedCredentials() {
        val futureExpiry = Instant.now().plusSeconds(300).toString()
        val spoofedJson = JSONObject()
            .put("protocol", "VAJRA_ENROLL_V1")
            .put("serverUrl", "http://127.0.0.1.attacker.example:3000")
            .put("deviceId", "dev-1")
            .put("enrollmentToken", "VJR-TOKEN-1")
            .put("challengeNonce", "nonce-1234567890abcdef")
            .put("expiresAt", futureExpiry)
            .toString()
        assertNull(EnrollmentActivity.parsePairingBundleJson(spoofedJson))

        val credJson = JSONObject()
            .put("protocol", "VAJRA_ENROLL_V1")
            .put("serverUrl", "https://admin:secret@vajra.example.com")
            .put("deviceId", "dev-1")
            .put("enrollmentToken", "VJR-TOKEN-1")
            .put("challengeNonce", "nonce-1234567890abcdef")
            .put("expiresAt", futureExpiry)
            .toString()
        assertNull(EnrollmentActivity.parsePairingBundleJson(credJson))

        val ipv6LoopbackJson = JSONObject()
            .put("protocol", "VAJRA_ENROLL_V1")
            .put("serverUrl", "http://[::1]:3000")
            .put("deviceId", "dev-1")
            .put("enrollmentToken", "VJR-TOKEN-1")
            .put("challengeNonce", "nonce-1234567890abcdef")
            .put("expiresAt", futureExpiry)
            .toString()
        val parsed = EnrollmentActivity.parsePairingBundleJson(ipv6LoopbackJson)
        assertNotNull(parsed)
        assertEquals("http://[::1]:3000", parsed?.serverUrl)
    }

    @Test
    fun statusResolver_neverMapsCompromisedToTrustedAndPreservesDistinctStates() {
        assertEquals("COMPROMISED", DeviceStatusResolver.resolveLifecycleState("CONNECTED", "COMPROMISED"))
        assertEquals("COMPROMISED", DeviceStatusResolver.resolveLifecycleState("AWAITING_OWNER_CONFIRMATION", "COMPROMISED"))
        assertFalse(DeviceStatusResolver.isOperationalState("COMPROMISED"))
        assertTrue(DeviceStatusResolver.isQuarantinedOrRevokedState("COMPROMISED"))

        assertEquals("OFFLINE", DeviceStatusResolver.resolveLifecycleState("CONNECTED", "OFFLINE"))
        assertTrue(DeviceStatusResolver.isOperationalState("OFFLINE"))

        assertEquals("REVOKED", DeviceStatusResolver.resolveLifecycleState("REVOKED", "REVOKED"))
        assertEquals("DECOMMISSIONED", DeviceStatusResolver.resolveLifecycleState("REVOKED", "DECOMMISSIONED"))
        assertEquals("ENROLLMENT_PENDING", DeviceStatusResolver.resolveLifecycleState("AWAITING_OWNER_CONFIRMATION", "ENROLLMENT_PENDING"))
        assertEquals("ENROLLMENT_PENDING", DeviceStatusResolver.resolveLifecycleState("", "PENDING"))
        assertEquals("TRUSTED", DeviceStatusResolver.resolveLifecycleState("CONNECTED", "TRUSTED"))
        assertEquals("ACTIVE", DeviceStatusResolver.resolveLifecycleState("CONNECTED", "ACTIVE"))
    }

    @Test
    fun ringOutcome_separatesAudioNotificationDndAndStopControls() {
        // 1. Audio confirmed + notifications blocked => audible execution reported accurately, notification controls unavailable
        val audibleNoNotif = RingExecutionHelper.evaluateRingOutcome(
            playbackConfirmed = true,
            notificationPosted = false,
            hasPostPermission = false,
            appNotificationsEnabled = true,
            channelBlocked = false,
            dndActive = false,
            alarmVolume = 5
        )
        assertEquals("COMPLETED", audibleNoNotif.status)
        assertEquals("AUDIBLE_WITHOUT_NOTIFICATION", audibleNoNotif.deliveryOutcome)
        assertTrue(audibleNoNotif.audioPlaybackConfirmed)
        assertEquals("CONFIRMED_AUDIBLE", audibleNoNotif.audioPlaybackStatus)
        assertFalse(audibleNoNotif.notificationPosted)
        assertEquals("PERMISSION_DENIED", audibleNoNotif.notificationAvailability)
        assertFalse(audibleNoNotif.stopNotificationControlAvailable)
        assertTrue(audibleNoNotif.stopInAppControlAvailable)
        assertEquals("IN_APP_ONLY", audibleNoNotif.stopActionAvailability)
        assertTrue(audibleNoNotif.telemetry.getBoolean("audiblePlaybackStarted"))
        assertFalse(audibleNoNotif.telemetry.getBoolean("stopNotificationControlAvailable"))

        // 2. Playback NOT confirmed + notification posted => must never claim audible execution
        val unconfirmedAudio = RingExecutionHelper.evaluateRingOutcome(
            playbackConfirmed = false,
            notificationPosted = true,
            hasPostPermission = true,
            appNotificationsEnabled = true,
            channelBlocked = false,
            dndActive = false,
            alarmVolume = 5
        )
        assertEquals("COMPLETED", unconfirmedAudio.status)
        assertEquals("NOTIFICATION_ONLY_AUDIO_UNCONFIRMED", unconfirmedAudio.deliveryOutcome)
        assertFalse(unconfirmedAudio.audioPlaybackConfirmed)
        assertEquals("NOT_CONFIRMED", unconfirmedAudio.audioPlaybackStatus)
        assertFalse(unconfirmedAudio.telemetry.getBoolean("audiblePlaybackStarted"))
        assertTrue(unconfirmedAudio.stopNotificationControlAvailable)
        assertFalse(unconfirmedAudio.stopInAppControlAvailable)

        // 3. Playback started while DND active => must report PLAYBACK_SUPPRESSED, not CONFIRMED_AUDIBLE
        val dndSuppressed = RingExecutionHelper.evaluateRingOutcome(
            playbackConfirmed = true,
            notificationPosted = true,
            hasPostPermission = true,
            appNotificationsEnabled = true,
            channelBlocked = false,
            dndActive = true,
            alarmVolume = 5
        )
        assertEquals("SUPPRESSED_WITH_NOTIFICATION", dndSuppressed.deliveryOutcome)
        assertEquals("PLAYBACK_SUPPRESSED", dndSuppressed.audioPlaybackStatus)
        assertTrue(dndSuppressed.dndRestricted)
        assertFalse(dndSuppressed.telemetry.getBoolean("audiblePlaybackStarted"))

        // 4. Both blocked => FAILED / BLOCKED / NONE
        val blocked = RingExecutionHelper.evaluateRingOutcome(
            playbackConfirmed = false,
            notificationPosted = false,
            hasPostPermission = true,
            appNotificationsEnabled = false,
            channelBlocked = false,
            dndActive = false,
            alarmVolume = 0
        )
        assertEquals("FAILED", blocked.status)
        assertEquals("BLOCKED", blocked.deliveryOutcome)
        assertEquals("APP_DISABLED", blocked.notificationAvailability)
        assertEquals("NONE", blocked.stopActionAvailability)
        assertFalse(blocked.stopActionAvailable)
    }
}
