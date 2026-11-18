package com.vajra.companion.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import com.vajra.companion.commands.CommandCapabilityMatrix
import com.vajra.companion.identity.AndroidKeystoreIdentity
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
                    if (
                        errBody.contains("REVOKED") ||
                        errBody.contains("DECOMMISSIONED") ||
                        errBody.contains("DEVICE_CREDENTIAL_INACTIVE")
                    ) {
                        identity.saveMeta(meta.getString("deviceId"), baseUrl, "REVOKED")
                        HeartbeatWorker.cancel(applicationContext)
                        CommandPollWorker.cancel(applicationContext)
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
                                cmdStatePrefs.edit().putString(
                                    id,
                                    JSONObject().put("state", "SUBMITTED").put("result", savedResult).toString()
                                ).apply()
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
                    try {
                        api.acknowledge(id).use { ackRes ->
                            if (ackRes.code == 404 || ackRes.code == 410) {
                                cmdStatePrefs.edit().putString(
                                    id,
                                    JSONObject().put("state", "SUBMITTED").toString()
                                ).apply()
                                continue
                            }
                        }
                    } catch (_: Exception) {
                        // Proceed safely; /result still reports final status
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
                    cmdStatePrefs.edit().putString(
                        id,
                        JSONObject()
                            .put("state", "PENDING_RESULT_SUBMIT")
                            .put("result", result)
                            .toString()
                    ).apply()

                    val submitted = submitResultIdempotently(api, id, result)
                    if (submitted) {
                        cmdStatePrefs.edit().putString(
                            id,
                            JSONObject()
                                .put("state", "SUBMITTED")
                                .put("result", result)
                                .toString()
                        ).apply()
                    } else {
                        shouldRetryResultSubmit = true
                    }
                }

                if (shouldRetryResultSubmit) Result.retry() else Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
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
                    .put("agentVersion", "0.1.0-android")
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
        val channelId = "vajra_ring"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(channelId)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    channelId,
                    "VAJRA Ring",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Owner-requested device ring from VAJRA console"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
                nm.createNotificationChannel(channel)
            }
        }

        val hasPostPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val appNotificationsEnabled = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(channelId)
            ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE
        } else {
            false
        }
        val dndActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } else {
            false
        }
        val alarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1

        val notificationsEffective = hasPostPermission && appNotificationsEnabled && !channelBlocked

        if (notificationsEffective) {
            val stopIntent = Intent(applicationContext, StopRingReceiver::class.java).apply {
                action = RingAlarmController.ACTION_STOP_RING
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                RingAlarmController.NOTIFICATION_ID,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("VAJRA — Find my device")
                .setContentText("Your VAJRA account requested this device to ring.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "Stop Alarm",
                    stopPendingIntent
                )
                .setDeleteIntent(stopPendingIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(RingAlarmController.NOTIFICATION_ID, notification)
        }

        val audiblePlaybackStarted = try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = alarmUri?.let { RingtoneManager.getRingtone(applicationContext, it) }
            RingAlarmController.start(ringtone)
        } catch (_: Exception) {
            false
        }

        val outcome = when {
            !notificationsEffective && !audiblePlaybackStarted -> "BLOCKED"
            audiblePlaybackStarted && !dndActive && alarmVolume != 0 && notificationsEffective -> "FULL_AUDIBLE"
            else -> "DEGRADED_SILENT"
        }

        val telemetry = JSONObject()
            .put("deliveryOutcome", outcome)
            .put("notificationsEnabled", notificationsEffective)
            .put("postNotificationsGranted", hasPostPermission)
            .put("channelBlocked", channelBlocked)
            .put("dndActive", dndActive)
            .put("alarmVolume", alarmVolume)
            .put("audiblePlaybackStarted", audiblePlaybackStarted)

        return when (outcome) {
            "BLOCKED" -> Triple(
                "FAILED",
                "Ring blocked: device notifications are disabled and alarm playback could not start.",
                telemetry
            )
            "FULL_AUDIBLE" -> Triple(
                "COMPLETED",
                "Ring notification posted and alarm playback started on device.",
                telemetry
            )
            else -> Triple(
                "COMPLETED",
                "Ring triggered with degraded audio visibility (check DND, alarm volume, or notification settings).",
                telemetry
            )
        }
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
