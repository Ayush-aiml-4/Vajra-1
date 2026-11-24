package com.vajra.companion.work

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import com.vajra.companion.identity.DeviceActivityLog

object RingAlarmController {
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_RING = "com.vajra.companion.action.STOP_RING"

    private val lock = Any()
    private var activeRingtone: Ringtone? = null

    fun isRinging(): Boolean {
        synchronized(lock) {
            return try {
                activeRingtone?.isPlaying == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun start(ringtone: Ringtone?): Boolean {
        synchronized(lock) {
            try {
                activeRingtone?.stop()
            } catch (_: Exception) {
                // ignore stop error on stale ringtone
            }
            activeRingtone = ringtone
            if (ringtone == null) return false
            return try {
                ringtone.play()
                val playing = try {
                    ringtone.isPlaying
                } catch (_: Exception) {
                    false
                }
                if (!playing) {
                    try {
                        ringtone.stop()
                    } catch (_: Exception) {
                        // ignore cleanup error
                    }
                    activeRingtone = null
                }
                playing
            } catch (_: Exception) {
                activeRingtone = null
                false
            }
        }
    }

    fun stop(context: Context): Boolean {
        var wasPlaying = false
        synchronized(lock) {
            try {
                wasPlaying = activeRingtone?.isPlaying == true
                activeRingtone?.stop()
            } catch (_: Exception) {
                // ignore
            } finally {
                activeRingtone = null
            }
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // ignore
        }
        if (wasPlaying) {
            DeviceActivityLog.record(
                context = context,
                category = "COMMAND",
                status = "SUCCESS",
                title = "Ring Alarm Stopped",
                detail = "Active Find My Device alarm playback and notification cleared."
            )
        }
        return wasPlaying
    }
}

class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == RingAlarmController.ACTION_STOP_RING) {
            RingAlarmController.stop(context)
        }
    }
}
