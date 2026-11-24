package com.vajra.companion.identity

import java.util.Locale

/**
 * Resolves authoritative device lifecycle state from `/api/devices/:id/enrollment/status`
 * responses without ever elevating COMPROMISED, REVOKED, DECOMMISSIONED, or OFFLINE
 * devices to TRUSTED merely because `status == "CONNECTED"` is present.
 */
object DeviceStatusResolver {

    fun resolveLifecycleState(status: String?, lifecycleState: String?): String {
        val normLifecycle = lifecycleState?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val normStatus = status?.trim()?.uppercase(Locale.ROOT).orEmpty()

        return when {
            normLifecycle == "COMPROMISED" -> "COMPROMISED"
            normLifecycle == "REVOKED" || normStatus == "REVOKED" || normStatus == "REJECTED" -> "REVOKED"
            normLifecycle == "DECOMMISSIONED" -> "DECOMMISSIONED"
            normLifecycle == "OFFLINE" -> "OFFLINE"
            normLifecycle == "LOST" -> "LOST"
            normLifecycle == "ENROLLMENT_PENDING" || normLifecycle == "PENDING" -> "ENROLLMENT_PENDING"
            normLifecycle == "UNREGISTERED" -> "UNREGISTERED"
            normLifecycle == "ACTIVE" || normLifecycle == "TRUSTED" -> normLifecycle
            normStatus == "CONNECTED" -> "TRUSTED"
            normStatus == "AWAITING_OWNER_CONFIRMATION" || normStatus == "PENDING_SCAN" -> "ENROLLMENT_PENDING"
            normLifecycle.isNotBlank() -> normLifecycle
            else -> "UNKNOWN"
        }
    }

    fun isOperationalState(lifecycleState: String): Boolean =
        lifecycleState in setOf("TRUSTED", "ACTIVE", "OFFLINE")

    fun isQuarantinedOrRevokedState(lifecycleState: String): Boolean =
        lifecycleState in setOf("COMPROMISED", "REVOKED", "DECOMMISSIONED")
}
