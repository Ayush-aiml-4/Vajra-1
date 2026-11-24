package com.vajra.companion.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.vajra.companion.BuildConfig
import com.vajra.companion.R
import com.vajra.companion.identity.AndroidKeystoreIdentity
import com.vajra.companion.identity.DeviceActivityLog
import com.vajra.companion.network.ServerUrlValidator
import com.vajra.companion.work.CommandPollWorker
import com.vajra.companion.work.HeartbeatWorker
import com.vajra.companion.work.RingAlarmController
import com.vajra.companion.work.RingExecutionHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var identity: AndroidKeystoreIdentity

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notification permission not granted. You can enable it in System Notification Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshSettingsView()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        identity = AndroidKeystoreIdentity(this)

        findViewById<ImageButton>(R.id.btnSettingsBack)?.setOnClickListener {
            finish()
        }

        val input = findViewById<EditText>(R.id.inputServerUrl)
        val prefs = getSharedPreferences("vajra_settings", MODE_PRIVATE)
        input.setText(
            prefs.getString("serverBaseUrl", getString(R.string.default_server_url))
        )

        findViewById<MaterialButton>(R.id.btnGrantNotifPerm)?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }

        findViewById<MaterialButton>(R.id.btnOpenNotifSettings)?.setOnClickListener {
            openNotificationSettings()
        }

        findViewById<MaterialButton>(R.id.btnOpenAppPermissions)?.setOnClickListener {
            openAppDetailsSettings()
        }

        findViewById<MaterialButton>(R.id.btnSettingsCopyFp)?.setOnClickListener {
            if (identity.hasKey()) {
                val fp = identity.exportPublicIdentity().fingerprint
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VAJRA Public Key Fingerprint", fp))
                Toast.makeText(this, "Public key fingerprint copied.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnSettingsPairNow)?.setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnSettingsPrivacy)?.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnSaveServer)?.setOnClickListener {
            val validation = ServerUrlValidator.validate(input.text.toString())
            if (!validation.valid || validation.normalizedUrl == null) {
                Toast.makeText(
                    this,
                    validation.errorMessage ?: getString(R.string.invalid_server_url),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val url = validation.normalizedUrl
            input.setText(url)
            prefs.edit().putString("serverBaseUrl", url).apply()
            identity.loadMeta()?.let { meta ->
                identity.saveMeta(
                    meta.getString("deviceId"),
                    url,
                    meta.optString("lifecycleState", "ENROLLMENT_PENDING")
                )
            }
            DeviceActivityLog.record(
                context = this,
                category = "SECURITY",
                status = "INFO",
                title = "Server Configuration Updated",
                detail = "Configured VAJRA origin set to $url"
            )
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.txtSettingsAppVersion)?.text =
            "App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.APPLICATION_ID}"

        findViewById<MaterialButton>(R.id.btnDisconnect)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.disconnect_title)
                .setMessage(R.string.disconnect_message)
                .setPositiveButton(R.string.disconnect_confirm) { _, _ ->
                    HeartbeatWorker.cancel(this)
                    CommandPollWorker.cancel(this)
                    RingAlarmController.stop(this)
                    identity.deleteKey()
                    getSharedPreferences("vajra_cmd_processed", MODE_PRIVATE).edit().clear().apply()
                    Toast.makeText(this, R.string.disconnected, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSettingsView()
    }

    private fun refreshSettingsView() {
        val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasCameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        val appEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ringChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.getNotificationChannel(RingExecutionHelper.CHANNEL_ID)
        } else {
            null
        }
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ringChannel != null &&
            ringChannel.importance == NotificationManager.IMPORTANCE_NONE
        val dndActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } else {
            false
        }

        val txtNotifPerm = findViewById<TextView>(R.id.txtSettingsNotifPerm)
        val txtChannelState = findViewById<TextView>(R.id.txtSettingsChannelState)
        val btnGrantNotif = findViewById<MaterialButton>(R.id.btnGrantNotifPerm)

        if (hasNotifPerm) {
            txtNotifPerm?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "POST_NOTIFICATIONS Permission: Granted"
            } else {
                "System Notifications: Permitted on Android ${Build.VERSION.RELEASE}"
            }
            txtNotifPerm?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
            btnGrantNotif?.visibility = View.GONE
        } else {
            txtNotifPerm?.text = "POST_NOTIFICATIONS Permission: Denied"
            txtNotifPerm?.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
            btnGrantNotif?.visibility = View.VISIBLE
        }

        val channelLine = when {
            !appEnabled -> "App notifications are disabled at OS level. Find My Device ring banners will not display."
            channelBlocked -> "VAJRA Ring channel is muted in system settings. Enable High Importance for ring alerts."
            dndActive -> "VAJRA Ring channel ready (Do Not Disturb is currently active on device)."
            else -> "App notifications and VAJRA Ring high-priority alert channel are ready."
        }
        txtChannelState?.text = channelLine

        // Permissions Card
        val txtPermCam = findViewById<TextView>(R.id.txtPermCamera)
        val txtPermNotif = findViewById<TextView>(R.id.txtPermNotifications)
        txtPermCam?.text = if (hasCameraPerm) "Granted" else "Not granted"
        txtPermCam?.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasCameraPerm) R.color.vajra_jade else R.color.vajra_text_secondary
            )
        )
        txtPermNotif?.text = if (hasNotifPerm && appEnabled) "Granted" else "Blocked / Denied"
        txtPermNotif?.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasNotifPerm && appEnabled) R.color.vajra_jade else R.color.vajra_crimson
            )
        )

        // Device Identity Card
        val meta = identity.loadMeta()
        val badgeState = findViewById<TextView>(R.id.badgeSettingsEnrollState)
        val txtModel = findViewById<TextView>(R.id.txtSettingsDeviceModel)
        val txtDeviceId = findViewById<TextView>(R.id.txtSettingsDeviceId)
        val txtKeyLevel = findViewById<TextView>(R.id.txtSettingsKeystoreLevel)
        val layoutFpWell = findViewById<LinearLayout>(R.id.layoutSettingsFpWell)
        val txtFp = findViewById<TextView>(R.id.txtSettingsFingerprint)
        val btnPairNow = findViewById<MaterialButton>(R.id.btnSettingsPairNow)
        val layoutDisconnect = findViewById<LinearLayout>(R.id.layoutSettingsDisconnectCard)

        txtModel?.text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

        if (meta == null && !identity.hasKey()) {
            badgeState?.text = "NOT ENROLLED"
            badgeState?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            badgeState?.setBackgroundResource(R.drawable.bg_badge_neutral)
            txtDeviceId?.text = "Device ID: Not paired yet"
            txtKeyLevel?.text = "Keystore Identity: Generated upon pairing"
            txtKeyLevel?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            layoutFpWell?.visibility = View.GONE
            btnPairNow?.visibility = View.VISIBLE
            layoutDisconnect?.visibility = View.GONE
        } else {
            val lifecycle = meta?.optString("lifecycleState", "KEYSTORE_PRESENT") ?: "KEYSTORE_PRESENT"
            badgeState?.text = lifecycle
            when (lifecycle) {
                "TRUSTED", "ACTIVE" -> {
                    badgeState?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                    badgeState?.setBackgroundResource(R.drawable.bg_badge_jade)
                }
                "ENROLLMENT_PENDING" -> {
                    badgeState?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
                    badgeState?.setBackgroundResource(R.drawable.bg_badge_gold)
                }
                "COMPROMISED", "REVOKED", "DECOMMISSIONED" -> {
                    badgeState?.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
                    badgeState?.setBackgroundResource(R.drawable.bg_badge_crimson)
                }
                else -> {
                    badgeState?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
                    badgeState?.setBackgroundResource(R.drawable.bg_badge_neutral)
                }
            }
            val deviceId = meta?.optString("deviceId", "").orEmpty()
            txtDeviceId?.text = if (deviceId.isNotBlank()) "Device ID: $deviceId" else "Device ID: Pending"

            when (identity.getDetailedSecurityLevel()) {
                AndroidKeystoreIdentity.KeystoreSecurityLevel.STRONGBOX -> {
                    txtKeyLevel?.text = "Keystore Protection: StrongBox HSM (ECDSA P-256)"
                    txtKeyLevel?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                }
                AndroidKeystoreIdentity.KeystoreSecurityLevel.TEE -> {
                    txtKeyLevel?.text = "Keystore Protection: Hardware TEE (ECDSA P-256)"
                    txtKeyLevel?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                }
                AndroidKeystoreIdentity.KeystoreSecurityLevel.SOFTWARE -> {
                    txtKeyLevel?.text = "Keystore Protection: Software Keystore (ECDSA P-256)"
                    txtKeyLevel?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
                }
                else -> {
                    txtKeyLevel?.text = "Keystore Protection: Pending key generation"
                    txtKeyLevel?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
                }
            }

            if (identity.hasKey()) {
                layoutFpWell?.visibility = View.VISIBLE
                txtFp?.text = identity.exportPublicIdentity().fingerprint
            } else {
                layoutFpWell?.visibility = View.GONE
            }
            btnPairNow?.visibility = View.GONE
            layoutDisconnect?.visibility = View.VISIBLE
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}
