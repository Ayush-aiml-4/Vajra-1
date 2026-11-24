package com.vajra.companion.network

import java.net.URI
import java.util.Locale

/**
 * Strict URI validator for VAJRA backend server URLs.
 *
 * Security rules:
 * - Only `http` and `https` schemes are accepted.
 * - Cleartext `http` is permitted ONLY for exact approved loopback hosts:
 *   `localhost`, `127.0.0.1`, `10.0.2.2`, `::1`.
 * - `https` is mandatory for all non-loopback hosts.
 * - Rejects malformed URIs, embedded credentials (`user:pass@host`),
 *   queries/fragments, and invalid hostnames.
 */
object ServerUrlValidator {

    private val APPROVED_LOOPBACK_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "10.0.2.2",
        "::1"
    )

    private val DNS_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4_CANDIDATE_REGEX = Regex("^[0-9.]+$")

    data class ValidationResult(
        val valid: Boolean,
        val normalizedUrl: String? = null,
        val errorMessage: String? = null
    )

    fun normalizeAndValidate(rawUrl: String?): String? =
        validate(rawUrl).normalizedUrl

    fun validate(rawUrl: String?): ValidationResult {
        val trimmed = rawUrl?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isEmpty()) {
            return ValidationResult(false, errorMessage = "Server URL is required.")
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return ValidationResult(false, errorMessage = "Server URL must not contain whitespace or control characters.")
        }

        val uri = try {
            URI(trimmed).parseServerAuthority()
        } catch (_: Exception) {
            return ValidationResult(false, errorMessage = "Server URL is malformed.")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return ValidationResult(false, errorMessage = "Server URL must use https:// (or http:// for approved loopback hosts).")
        }

        if (uri.rawUserInfo != null || uri.userInfo != null) {
            return ValidationResult(false, errorMessage = "Server URL must not contain embedded credentials.")
        }

        if (uri.rawQuery != null || uri.rawFragment != null) {
            return ValidationResult(false, errorMessage = "Server URL must not contain query parameters or fragments.")
        }

        val rawHost = uri.host
        if (rawHost.isNullOrBlank()) {
            return ValidationResult(false, errorMessage = "Server URL must include a valid hostname.")
        }

        val normalizedHost = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (!isValidHost(normalizedHost)) {
            return ValidationResult(false, errorMessage = "Server URL contains an invalid hostname.")
        }

        if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
            return ValidationResult(false, errorMessage = "Server URL contains an invalid port.")
        }

        if (scheme == "http" && normalizedHost !in APPROVED_LOOPBACK_HOSTS) {
            return ValidationResult(
                false,
                errorMessage = "HTTPS is required for all non-loopback hosts (HTTP is restricted to localhost, 127.0.0.1, 10.0.2.2, or ::1)."
            )
        }

        return ValidationResult(valid = true, normalizedUrl = trimmed)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host == "::1") return true
        if (host.contains(':')) return false // Only ::1 loopback IPv6 literal is accepted cleanly

        if (IPV4_CANDIDATE_REGEX.matches(host)) {
            val octets = host.split('.')
            if (octets.size != 4) return false
            return octets.all { part ->
                part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        if (host.startsWith('.') || host.endsWith('.')) return false
        val labels = host.split('.')
        return labels.all { label -> DNS_LABEL_REGEX.matches(label) }
    }
}
