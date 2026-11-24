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
import org.json.JSONObject

object RingExecutionHelper {

    const val CHANNEL_ID = "vajra_ring"

    data class RingOutcome(
        val status: String,
        val deliveryOutcome: String,
        val audioPlaybackConfirmed: Boolean,
        val audioPlaybackStatus: String,
        val notificationPosted: Boolean,
        val notificationAvailability: String,
        val dndRestricted: Boolean,
        val stopActionAvailable: Boolean,
        val stopNotificationControlAvailable: Boolean,
        val stopInAppControlAvailable: Boolean,
        val stopActionAvailability: String,
        val message: String,
        val telemetry: JSONObject
    )

    fun executeRing(context: Context, sourceLabel: String): RingOutcome {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = nm.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(CHANNEL_ID)
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
        val notificationsPermitted = hasPostPermission && appNotificationsEnabled && !channelBlocked

        var notificationPosted = false
        if (notificationsPermitted) {
            try {
                val stopIntent = Intent(context, StopRingReceiver::class.java).apply {
                    action = RingAlarmController.ACTION_STOP_RING
                }
                val stopPendingIntent = PendingIntent.getBroadcast(
                    context,
                    RingAlarmController.NOTIFICATION_ID,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("VAJRA — Find my device")
                    .setContentText("$sourceLabel requested this device to ring.")
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
                notificationPosted = true
            } catch (_: Exception) {
                notificationPosted = false
            }
        }

        val playbackConfirmed = try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = alarmUri?.let { RingtoneManager.getRingtone(context, it) }
            RingAlarmController.start(ringtone)
        } catch (_: Exception) {
            false
        }

        return evaluateRingOutcome(
            playbackConfirmed = playbackConfirmed,
            notificationPosted = notificationPosted,
            hasPostPermission = hasPostPermission,
            appNotificationsEnabled = appNotificationsEnabled,
            channelBlocked = channelBlocked,
            dndActive = dndActive,
            alarmVolume = alarmVolume
        )
    }

    /**
     * Evaluates separated audio playback, notification availability, DND restrictions,
     * and stop-action availability without conflating blocked notifications with silent audio.
     */
    fun evaluateRingOutcome(
        playbackConfirmed: Boolean,
        notificationPosted: Boolean,
        hasPostPermission: Boolean,
        appNotificationsEnabled: Boolean,
        channelBlocked: Boolean,
        dndActive: Boolean,
        alarmVolume: Int
    ): RingOutcome {
        val notificationAvailability = when {
            notificationPosted -> "POSTED"
            !hasPostPermission -> "PERMISSION_DENIED"
            !appNotificationsEnabled -> "APP_DISABLED"
            channelBlocked -> "CHANNEL_BLOCKED"
            else -> "UNAVAILABLE"
        }

        val volumeMuted = alarmVolume == 0
        val audibleConfirmed = playbackConfirmed && !dndActive && !volumeMuted

        val audioPlaybackStatus = when {
            audibleConfirmed -> "CONFIRMED_AUDIBLE"
            playbackConfirmed -> "PLAYBACK_SUPPRESSED"
            else -> "NOT_CONFIRMED"
        }

        val stopNotificationControlAvailable = notificationPosted
        val stopInAppControlAvailable = playbackConfirmed
        val stopActionAvailable = stopNotificationControlAvailable || stopInAppControlAvailable
        val stopActionAvailability = when {
            stopNotificationControlAvailable && stopInAppControlAvailable -> "NOTIFICATION_AND_IN_APP"
            stopNotificationControlAvailable -> "NOTIFICATION_ONLY"
            stopInAppControlAvailable -> "IN_APP_ONLY"
            else -> "NONE"
        }

        val deliveryOutcome = when {
            audibleConfirmed && notificationPosted -> "FULL_AUDIBLE"
            audibleConfirmed && !notificationPosted -> "AUDIBLE_WITHOUT_NOTIFICATION"
            playbackConfirmed && (dndActive || volumeMuted) && notificationPosted -> "SUPPRESSED_WITH_NOTIFICATION"
            playbackConfirmed && (dndActive || volumeMuted) -> "SUPPRESSED_WITHOUT_NOTIFICATION"
            notificationPosted -> "NOTIFICATION_ONLY_AUDIO_UNCONFIRMED"
            else -> "BLOCKED"
        }

        val notificationBlockedDetail = when (notificationAvailability) {
            "PERMISSION_DENIED" -> "POST_NOTIFICATIONS permission denied"
            "APP_DISABLED" -> "app notifications disabled"
            "CHANNEL_BLOCKED" -> "VAJRA Ring channel muted"
            else -> "notification dispatch failed"
        }

        val suppressionDetail = when {
            dndActive && volumeMuted -> "Do Not Disturb active and alarm volume is 0"
            dndActive -> "Do Not Disturb is active"
            volumeMuted -> "alarm volume is 0"
            else -> ""
        }

        val status = if (deliveryOutcome == "BLOCKED") "FAILED" else "COMPLETED"
        val message = when (deliveryOutcome) {
            "FULL_AUDIBLE" ->
                "Confirmed audible alarm playback started; ring notification and Stop Alarm controls are active."
            "AUDIBLE_WITHOUT_NOTIFICATION" ->
                "Confirmed audible alarm playback started on device, while notification controls are unavailable ($notificationBlockedDetail). Stop Ring remains available inside the app."
            "SUPPRESSED_WITH_NOTIFICATION" ->
                "Alarm playback started ($suppressionDetail), and ring notification Stop Alarm control was posted."
            "SUPPRESSED_WITHOUT_NOTIFICATION" ->
                "Alarm playback started ($suppressionDetail); notification controls are unavailable ($notificationBlockedDetail). In-app Stop Ring remains available."
            "NOTIFICATION_ONLY_AUDIO_UNCONFIRMED" ->
                "Ring notification posted, but active alarm audio playback could not be confirmed."
            else ->
                "Ring blocked: alarm audio playback could not be confirmed and notification controls are unavailable ($notificationBlockedDetail)."
        }

        val telemetry = JSONObject()
            .put("deliveryOutcome", deliveryOutcome)
            .put("audioPlaybackConfirmed", playbackConfirmed)
            .put("audioPlaybackStatus", audioPlaybackStatus)
            .put("audiblePlaybackStarted", audibleConfirmed)
            .put("notificationsEnabled", notificationPosted)
            .put("notificationPosted", notificationPosted)
            .put("notificationAvailability", notificationAvailability)
            .put("postNotificationsGranted", hasPostPermission)
            .put("appNotificationsEnabled", appNotificationsEnabled)
            .put("channelBlocked", channelBlocked)
            .put("dndRestricted", dndActive)
            .put("dndActive", dndActive)
            .put("alarmVolume", alarmVolume)
            .put("stopActionAvailable", stopActionAvailable)
            .put("stopNotificationControlAvailable", stopNotificationControlAvailable)
            .put("stopInAppControlAvailable", stopInAppControlAvailable)
            .put("stopActionAvailability", stopActionAvailability)

        return RingOutcome(
            status = status,
            deliveryOutcome = deliveryOutcome,
            audioPlaybackConfirmed = playbackConfirmed,
            audioPlaybackStatus = audioPlaybackStatus,
            notificationPosted = notificationPosted,
            notificationAvailability = notificationAvailability,
            dndRestricted = dndActive,
            stopActionAvailable = stopActionAvailable,
            stopNotificationControlAvailable = stopNotificationControlAvailable,
            stopInAppControlAvailable = stopInAppControlAvailable,
            stopActionAvailability = stopActionAvailability,
            message = message,
            telemetry = telemetry
        )
    }
}
