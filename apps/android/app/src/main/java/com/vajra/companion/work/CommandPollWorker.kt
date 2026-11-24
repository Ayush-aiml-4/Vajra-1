package com.vajra.companion.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vajra.companion.BuildConfig
import com.vajra.companion.commands.CommandCapabilityMatrix
import com.vajra.companion.identity.AndroidKeystoreIdentity
import com.vajra.companion.identity.DeviceActivityLog
import com.vajra.companion.network.VajraApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class CommandPollWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val identity = AndroidKeystoreIdentity(applicationContext)
        val meta = identity.loadMeta() ?: return Result.success()
        val state = meta.optString("lifecycleState", "")
        if (state !in listOf("TRUSTED", "ACTIVE", "OFFLINE")) {
            return Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("vajra_settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("serverBaseUrl", null)
            ?: meta.optString("serverBaseUrl")
        val api = VajraApiClient(identity) { baseUrl }
        val cmdStatePrefs = applicationContext.getSharedPreferences("vajra_cmd_state", Context.MODE_PRIVATE)

        return try {
            api.pendingCommands().use { res ->
                if (res.code == 401 || res.code == 403) {
                    val errBody = res.body?.string().orEmpty()
                    if (errBody.contains("DEVICE_COMPROMISED")) {
                        identity.saveMeta(meta.getString("deviceId"), baseUrl, "COMPROMISED")
                        HeartbeatWorker.cancel(applicationContext)
                        CommandPollWorker.cancel(applicationContext)
                        DeviceActivityLog.record(
                            context = applicationContext,
                            category = "SECURITY",
                            status = "FAILED",
                            title = "Command Poll Rejected (Compromised)",
                            detail = "Server returned HTTP ${res.code}; device quarantined."
                        )
                    } else if (
                        errBody.contains("REVOKED") ||
                        errBody.contains("DECOMMISSIONED") ||
                        errBody.contains("DEVICE_CREDENTIAL_INACTIVE")
                    ) {
                        identity.saveMeta(meta.getString("deviceId"), baseUrl, "REVOKED")
                        HeartbeatWorker.cancel(applicationContext)
                        CommandPollWorker.cancel(applicationContext)
                        DeviceActivityLog.record(
                            context = applicationContext,
                            category = "SECURITY",
                            status = "FAILED",
                            title = "Command Poll Rejected (Revoked)",
                            detail = "Server returned HTTP ${res.code}; local device access revoked."
                        )
                    }
                    return Result.success()
                }
                if (!res.isSuccessful) return Result.retry()

                val body = res.body?.string().orEmpty()
                val root = JSONObject(body)
                val commands: JSONArray = when {
                    root.has("commands") -> root.getJSONArray("commands")
                    root.has("pendingCommands") -> root.getJSONArray("pendingCommands")
                    else -> JSONArray()
                }

                cmdStatePrefs.edit()
                    .putLong("last_poll_at", System.currentTimeMillis())
                    .putInt("last_poll_count", commands.length())
                    .apply()

                var shouldRetryResultSubmit = false

                for (i in 0 until commands.length()) {
                    val cmd = commands.getJSONObject(i)
                    val id = cmd.optString("id")
                    val action = cmd.optString("actionType")
                    if (id.isBlank() || action.isBlank()) continue

                    val existingRaw = cmdStatePrefs.getString(id, null)
                    val existingEntry = existingRaw?.let {
                        try { JSONObject(it) } catch (_: Exception) { null }
                    }
                    if (existingEntry?.optString("state") == "SUBMITTED") {
                        continue
                    }

                    // If action already executed locally and only result submission failed, retry upload without re-executing
                    if (existingEntry?.optString("state") == "PENDING_RESULT_SUBMIT") {
                        val savedResult = existingEntry.optJSONObject("result")
                        if (savedResult != null) {
                            val submitted = submitResultIdempotently(api, id, savedResult)
                            if (submitted) {
                                persistCommandSummary(
                                    cmdStatePrefs = cmdStatePrefs,
                                    commandId = id,
                                    actionType = action,
                                    syncState = "SUBMITTED",
                                    result = savedResult
                                )
                            } else {
                                shouldRetryResultSubmit = true
                            }
                            continue
                        }
                    }

                    // Reject expired command locally before acknowledgement/execution
                    val expiresAtStr = cmd.optString("expiresAt").trim()
                    val isExpired = if (expiresAtStr.isNotEmpty()) {
                        try {
                            Instant.parse(expiresAtStr).toEpochMilli() <= System.currentTimeMillis()
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        false
                    }

                    // Acknowledge receipt first (allow 409 if already acknowledged on prior attempt)
                    val skipMissingOrGone = try {
                        api.acknowledge(id).use { ackRes ->
                            if (ackRes.code == 404 || ackRes.code == 410) {
                                cmdStatePrefs.edit().putString(
                                    id,
                                    JSONObject().put("state", "SUBMITTED").toString()
                                ).apply()
                                true
                            } else {
                                false
                            }
                        }
                    } catch (_: Exception) {
                        // Proceed safely; /result still reports final status
                        false
                    }
                    if (skipMissingOrGone) {
                        continue
                    }

                    val (status, message, metadata) = if (isExpired) {
                        Triple("FAILED", "Command expired prior to device execution.", null)
                    } else {
                        execute(action)
                    }

                    val result = JSONObject()
                        .put("status", status)
                        .put("safeMessage", message)
                    if (metadata != null) result.put("resultMetadata", metadata)

                    // Persist PENDING_RESULT_SUBMIT BEFORE calling /result so retries never double-execute RING_DEVICE
                    persistCommandSummary(
                        cmdStatePrefs = cmdStatePrefs,
                        commandId = id,
                        actionType = action,
                        syncState = "PENDING_RESULT_SUBMIT",
                        result = result
                    )

                    val submitted = submitResultIdempotently(api, id, result)
                    if (submitted) {
                        persistCommandSummary(
                            cmdStatePrefs = cmdStatePrefs,
                            commandId = id,
                            actionType = action,
                            syncState = "SUBMITTED",
                            result = result
                        )
                    } else {
                        shouldRetryResultSubmit = true
                    }

                    DeviceActivityLog.record(
                        context = applicationContext,
                        category = "COMMAND",
                        status = if (status == "COMPLETED") "SUCCESS" else "FAILED",
                        title = "Console Command: $action",
                        detail = if (submitted) {
                            "$status — $message"
                        } else {
                            "$status locally (pending result upload) — $message"
                        }
                    )
                }

                if (shouldRetryResultSubmit) Result.retry() else Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun persistCommandSummary(
        cmdStatePrefs: android.content.SharedPreferences,
        commandId: String,
        actionType: String,
        syncState: String,
        result: JSONObject
    ) {
        val entry = JSONObject()
            .put("commandId", commandId)
            .put("actionType", actionType)
            .put("state", syncState)
            .put("updatedAt", System.currentTimeMillis())
            .put("result", result)
            .toString()
        cmdStatePrefs.edit()
            .putString(commandId, entry)
            .putString("last_command_summary", entry)
            .apply()
    }

    private fun submitResultIdempotently(api: VajraApiClient, id: String, result: JSONObject): Boolean {
        return try {
            api.commandResult(id, result).use { res ->
                res.isSuccessful || res.code == 409 || res.code == 404 || res.code == 410
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun execute(action: String): Triple<String, String, JSONObject?> {
        if (!CommandCapabilityMatrix.isKnown(action)) {
            return Triple("FAILED", "Unknown action type rejected.", null)
        }
        if (!CommandCapabilityMatrix.isExecutableOnAndroid(action)) {
            return Triple("FAILED", CommandCapabilityMatrix.unsupportedReason(action), null)
        }
        return when (action) {
            CommandCapabilityMatrix.REQUEST_DEVICE_STATUS -> {
                val meta = JSONObject()
                    .put("agentVersion", "${BuildConfig.VERSION_NAME}-android")
                    .put("osVersion", "Android ${Build.VERSION.RELEASE}")
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                Triple("COMPLETED", "Android companion status report completed.", meta)
            }
            CommandCapabilityMatrix.RING_DEVICE -> ringDevice()
            else -> Triple("FAILED", CommandCapabilityMatrix.unsupportedReason(action), null)
        }
    }

    private fun ringDevice(): Triple<String, String, JSONObject> {
        val outcome = RingExecutionHelper.executeRing(applicationContext, "Your VAJRA account")
        return Triple(outcome.status, outcome.message, outcome.telemetry)
    }

    companion object {
        const val UNIQUE = "vajra_command_poll"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<CommandPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<CommandPollWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE}_immediate",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            WorkManager.getInstance(context).cancelUniqueWork("${UNIQUE}_immediate")
        }
    }
}
