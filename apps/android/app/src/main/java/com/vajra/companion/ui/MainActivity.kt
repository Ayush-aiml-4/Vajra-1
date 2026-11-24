package com.vajra.companion.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vajra.companion.BuildConfig
import com.vajra.companion.R
import com.vajra.companion.identity.AndroidKeystoreIdentity
import com.vajra.companion.identity.DeviceActivityLog
import com.vajra.companion.identity.DeviceStatusResolver
import com.vajra.companion.network.VajraApiClient
import com.vajra.companion.work.CommandPollWorker
import com.vajra.companion.work.HeartbeatWorker
import com.vajra.companion.work.RingAlarmController
import com.vajra.companion.work.RingExecutionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var identity: AndroidKeystoreIdentity
    private var lastLocalCommandNote: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        identity = AndroidKeystoreIdentity(this)

        checkNotificationPermission()

        // Top App Bar Settings
        findViewById<ImageButton>(R.id.btnHeaderSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Onboarding actions
        findViewById<MaterialButton>(R.id.btnConnectDevice)?.setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        // Dashboard actions
        findViewById<MaterialButton>(R.id.btnResumePendingApproval)?.setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnRefreshStatus)?.setOnClickListener {
            refresh()
        }

        findViewById<MaterialButton>(R.id.btnSendHeartbeat)?.setOnClickListener {
            triggerImmediateSignedHeartbeat()
        }

        findViewById<MaterialButton>(R.id.btnCopyFingerprint)?.setOnClickListener {
            if (identity.hasKey()) {
                val fp = identity.exportPublicIdentity().fingerprint
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VAJRA Public Key Fingerprint", fp))
                Toast.makeText(this, "Public key fingerprint copied.", Toast.LENGTH_SHORT).show()
            }
        }

        // Device Commands
        findViewById<MaterialButton>(R.id.btnCmdRingDevice)?.setOnClickListener {
            executeLocalRingDiagnostic()
        }

        findViewById<MaterialButton>(R.id.btnCmdStopRing)?.setOnClickListener {
            executeStopRing()
        }

        findViewById<MaterialButton>(R.id.btnCmdRequestStatus)?.setOnClickListener {
            executeRequestDeviceStatus()
        }

        findViewById<MaterialButton>(R.id.btnDisconnect)?.setOnClickListener {
            showDisconnectConfirmationDialog()
        }

        // Global links
        findViewById<TextView>(R.id.btnPrivacyLink)?.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<TextView>(R.id.btnSettingsLink)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        val state = identity.loadMeta()?.optString("lifecycleState", "") ?: ""
        if (state in listOf("TRUSTED", "ACTIVE", "OFFLINE")) {
            HeartbeatWorker.enqueueImmediate(this)
            CommandPollWorker.enqueueImmediate(this)
            HeartbeatWorker.schedule(this)
            CommandPollWorker.schedule(this)
        }
    }

    private fun refresh() {
        val layoutOnboarding = findViewById<LinearLayout>(R.id.layoutOnboarding)
        val layoutDashboard = findViewById<LinearLayout>(R.id.layoutDashboard)
        val badgeStatus = findViewById<TextView>(R.id.badgeStatus)
        val txtSyncSource = findViewById<TextView>(R.id.txtSyncSource)

        val meta = identity.loadMeta()
        if (meta == null) {
            layoutOnboarding?.visibility = View.VISIBLE
            layoutDashboard?.visibility = View.GONE
            badgeStatus?.text = getString(R.string.status_not_enrolled)
            badgeStatus?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            badgeStatus?.setBackgroundResource(R.drawable.bg_badge_neutral)
            txtSyncSource?.text = "Ready to pair"
            return
        }

        layoutOnboarding?.visibility = View.GONE
        layoutDashboard?.visibility = View.VISIBLE

        val lifecycle = meta.optString("lifecycleState", "UNKNOWN")
        val deviceId = meta.optString("deviceId", "")
        val prefs = getSharedPreferences("vajra_settings", Context.MODE_PRIVATE)
        val baseUrl = meta.optString("serverBaseUrl")
            .ifBlank { prefs.getString("serverBaseUrl", getString(R.string.default_server_url)).orEmpty() }

        renderStatusState(lifecycle, isLiveVerified = false)
        if (deviceId.isNotBlank() && baseUrl.isNotBlank()) {
            syncStatusWithServer(deviceId, baseUrl)
        }

        // Device Identity
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        findViewById<TextView>(R.id.txtDeviceModel)?.text = deviceModel
        findViewById<TextView>(R.id.txtDeviceId)?.text = "Device ID: $deviceId"

        val txtKeystoreTrust = findViewById<TextView>(R.id.txtKeystoreTrust)
        when (identity.getDetailedSecurityLevel()) {
            AndroidKeystoreIdentity.KeystoreSecurityLevel.STRONGBOX -> {
                txtKeystoreTrust?.text = "StrongBox Keymaster (Local HSM · Unattested by Server)"
                txtKeystoreTrust?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
            }
            AndroidKeystoreIdentity.KeystoreSecurityLevel.TEE -> {
                txtKeystoreTrust?.text = "Hardware Keystore (Local TEE · Unattested by Server)"
                txtKeystoreTrust?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
            }
            AndroidKeystoreIdentity.KeystoreSecurityLevel.SOFTWARE -> {
                txtKeystoreTrust?.text = "Software Keystore (Cryptographic Key)"
                txtKeystoreTrust?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
            }
            else -> {
                txtKeystoreTrust?.text = "Cryptographic Keystore"
                txtKeystoreTrust?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            }
        }

        if (identity.hasKey()) {
            val pub = identity.exportPublicIdentity()
            findViewById<TextView>(R.id.txtFingerprint)?.text = pub.fingerprint
        } else {
            val pendingFp = meta.optString("pendingFingerprint", "")
            findViewById<TextView>(R.id.txtFingerprint)?.text =
                pendingFp.ifBlank { "No Keystore key present" }
        }

        // Connection & Heartbeat
        findViewById<TextView>(R.id.txtServerUrl)?.text = baseUrl.ifBlank { "Not configured" }
        renderHeartbeatSummary()
        renderTelemetryCard()
        renderCommandState()
        renderRecentActivity()
    }

    private fun renderHeartbeatSummary() {
        val lastHb = identity.getLastHeartbeatAt()
        val txtHeartbeatStatus = findViewById<TextView>(R.id.txtHeartbeatStatus)
        val badgeFreshness = findViewById<TextView>(R.id.badgeHeartbeatFreshness)

        if (lastHb <= 0L) {
            txtHeartbeatStatus?.text = getString(R.string.heartbeat_never)
            badgeFreshness?.text = "NO CHECK-IN"
            badgeFreshness?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            badgeFreshness?.setBackgroundResource(R.drawable.bg_badge_neutral)
        } else {
            val ageMs = System.currentTimeMillis() - lastHb
            val formattedTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(lastHb))
            txtHeartbeatStatus?.text = "Last signed check-in: $formattedTime"
            when {
                ageMs < 20 * 60 * 1000L -> {
                    badgeFreshness?.text = "ONLINE"
                    badgeFreshness?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                    badgeFreshness?.setBackgroundResource(R.drawable.bg_badge_jade)
                }
                ageMs < 60 * 60 * 1000L -> {
                    badgeFreshness?.text = "STALE"
                    badgeFreshness?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
                    badgeFreshness?.setBackgroundResource(R.drawable.bg_badge_gold)
                }
                else -> {
                    badgeFreshness?.text = "OFFLINE"
                    badgeFreshness?.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
                    badgeFreshness?.setBackgroundResource(R.drawable.bg_badge_crimson)
                }
            }
        }

        val cmdPrefs = getSharedPreferences("vajra_cmd_state", Context.MODE_PRIVATE)
        val lastPollAt = cmdPrefs.getLong("last_poll_at", 0L)
        val lastPollCount = cmdPrefs.getInt("last_poll_count", 0)
        val txtPoll = findViewById<TextView>(R.id.txtCommandPollTime)
        if (lastPollAt > 0L) {
            val pollTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastPollAt))
            txtPoll?.text = "Last command check: $pollTime ($lastPollCount pending)"
        } else {
            txtPoll?.text = "Last command check: Not polled yet"
        }
    }

    private fun renderTelemetryCard() {
        // Battery via sticky ACTION_BATTERY_CHANGED intent (no extra permissions needed)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val txtBattery = findViewById<TextView>(R.id.txtTelemetryBattery)
        if (batteryIntent != null && level >= 0 && scale > 0) {
            val pct = ((level.toDouble() / scale.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            val stateLabel = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                else -> "On battery"
            }
            txtBattery?.text = "$pct% · $stateLabel"
            txtBattery?.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (pct <= 15 && status != BatteryManager.BATTERY_STATUS_CHARGING) R.color.vajra_amber else R.color.vajra_text_primary
                )
            )
        } else {
            txtBattery?.text = "Unavailable"
            txtBattery?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_muted))
        }

        findViewById<TextView>(R.id.txtTelemetryOs)?.text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        findViewById<TextView>(R.id.txtTelemetryAgent)?.text =
            "${BuildConfig.VERSION_NAME}-android"

        // Ring alert readiness (permission + notification channel + DND)
        val hasPostPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val appEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(RingExecutionHelper.CHANNEL_ID)
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

        val txtAlerts = findViewById<TextView>(R.id.txtTelemetryAlerts)
        when {
            !hasPostPermission || !appEnabled || channelBlocked -> {
                txtAlerts?.text = "Blocked (Settings)"
                txtAlerts?.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
            }
            dndActive -> {
                txtAlerts?.text = "Ready (DND Active)"
                txtAlerts?.setTextColor(ContextCompat.getColor(this, R.color.vajra_amber))
            }
            else -> {
                txtAlerts?.text = "Ready"
                txtAlerts?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
            }
        }
    }

    private fun renderCommandState() {
        val isRinging = RingAlarmController.isRinging()
        findViewById<LinearLayout>(R.id.layoutActiveRingBanner)?.visibility =
            if (isRinging) View.VISIBLE else View.GONE

        val cmdPrefs = getSharedPreferences("vajra_cmd_state", Context.MODE_PRIVATE)
        val lastCmdRaw = cmdPrefs.getString("last_command_summary", null)
        val lines = ArrayList<String>()

        lastLocalCommandNote?.let { lines.add(it) }

        if (!lastCmdRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(lastCmdRaw)
                val action = obj.optString("actionType", "UNKNOWN")
                val cmdId = obj.optString("commandId", "")
                val syncState = obj.optString("state", "")
                val updatedAt = obj.optLong("updatedAt", 0L)
                val result = obj.optJSONObject("result")
                val status = result?.optString("status", "UNKNOWN") ?: "UNKNOWN"
                val safeMsg = result?.optString("safeMessage", "") ?: ""
                val outcome = result?.optJSONObject("resultMetadata")?.optString("deliveryOutcome", "") ?: ""

                val timeStr = if (updatedAt > 0L) {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updatedAt))
                } else {
                    ""
                }
                val shortId = if (cmdId.length > 8) cmdId.take(8) + "…" else cmdId
                val syncLabel = if (syncState == "PENDING_RESULT_SUBMIT") {
                    "Pending result upload"
                } else {
                    "Synced to console"
                }
                val sb = StringBuilder()
                sb.append("Last Console Command: $action ($status)")
                if (outcome.isNotBlank()) sb.append(" · $outcome")
                if (shortId.isNotBlank()) sb.append("\nID: $shortId · $syncLabel")
                if (timeStr.isNotBlank()) sb.append(" · $timeStr")
                if (safeMsg.isNotBlank()) sb.append("\n$safeMsg")
                lines.add(sb.toString())
            } catch (_: Exception) {
                // Ignore malformed cached summary
            }
        }

        val txtLifecycle = findViewById<TextView>(R.id.txtCommandLifecycleStatus)
        if (lines.isEmpty()) {
            txtLifecycle?.text = "Ready. No local or console commands executed yet."
            txtLifecycle?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
        } else {
            txtLifecycle?.text = lines.joinToString("\n\n")
            txtLifecycle?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_primary))
        }
    }

    private fun renderRecentActivity() {
        val entries = DeviceActivityLog.list(this, limit = 6)
        val txtEmpty = findViewById<TextView>(R.id.txtActivityEmpty)
        val container = findViewById<LinearLayout>(R.id.layoutActivityList) ?: return

        if (entries.isEmpty()) {
            txtEmpty?.visibility = View.VISIBLE
            container.visibility = View.GONE
            container.removeAllViews()
            return
        }

        txtEmpty?.visibility = View.GONE
        container.visibility = View.VISIBLE
        container.removeAllViews()

        val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)

        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) lp.topMargin = dp(8)
                layoutParams = lp
                setBackgroundResource(R.drawable.bg_well_code)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = entry.title
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vajra_text_primary))
            }

            val timeView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = timeFormatter.format(Date(entry.timestampMs))
                textSize = 10f
                val colorRes = when (entry.status) {
                    "SUCCESS" -> R.color.vajra_jade
                    "FAILED" -> R.color.vajra_crimson
                    "WARNING" -> R.color.vajra_amber
                    else -> R.color.vajra_text_muted
                }
                setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
            }

            topRow.addView(titleView)
            topRow.addView(timeView)
            row.addView(topRow)

            if (entry.detail.isNotBlank()) {
                val detailView = TextView(this).apply {
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(3)
                    layoutParams = lp
                    text = entry.detail
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vajra_text_secondary))
                }
                row.addView(detailView)
            }

            container.addView(row)
        }
    }

    private fun executeLocalRingDiagnostic() {
        val outcome = RingExecutionHelper.executeRing(this, "Local Device Diagnostic")
        val logStatus = when (outcome.status) {
            "COMPLETED" -> if (outcome.deliveryOutcome == "FULL_AUDIBLE") "SUCCESS" else "WARNING"
            else -> "FAILED"
        }
        DeviceActivityLog.record(
            context = this,
            category = "COMMAND",
            status = logStatus,
            title = "Local Ring Trigger (${outcome.deliveryOutcome})",
            detail = outcome.message
        )
        lastLocalCommandNote =
            "Local Ring Command: ${outcome.status} (${outcome.deliveryOutcome})\n" +
            "Audio: ${outcome.audioPlaybackStatus} · Notification: ${outcome.notificationAvailability} · Stop Control: ${outcome.stopActionAvailability}\n" +
            outcome.message
        renderTelemetryCard()
        renderCommandState()
        renderRecentActivity()
    }

    private fun executeStopRing() {
        val wasPlaying = RingAlarmController.stop(this)
        lastLocalCommandNote = if (wasPlaying) {
            "Stop Ring Command: COMPLETED\nActive Find My Device alarm playback and notification stopped."
        } else {
            "Stop Ring Command: COMPLETED\nNo active ring alarm was playing; notification cleared."
        }
        renderCommandState()
        renderRecentActivity()
    }

    private fun executeRequestDeviceStatus() {
        val meta = identity.loadMeta()
        val state = meta?.optString("lifecycleState", "") ?: ""
        if (meta == null || state !in listOf("TRUSTED", "ACTIVE", "OFFLINE")) {
            lastLocalCommandNote =
                "Request Device Status: Unavailable until owner approval completes on the VAJRA console."
            renderCommandState()
            return
        }
        if (!identity.hasKey()) {
            lastLocalCommandNote = "Request Device Status: FAILED — Android Keystore key is missing."
            renderCommandState()
            return
        }

        val btn = findViewById<MaterialButton>(R.id.btnCmdRequestStatus)
        btn?.isEnabled = false
        lastLocalCommandNote = "Request Device Status: Sending signed status report & checking pending console commands…"
        renderCommandState()

        val prefs = getSharedPreferences("vajra_settings", Context.MODE_PRIVATE)
        val baseUrl = meta.optString("serverBaseUrl").ifBlank {
            prefs.getString("serverBaseUrl", getString(R.string.default_server_url)).orEmpty()
        }
        val api = VajraApiClient(identity, timeoutSeconds = 10) { baseUrl }

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        lifecycleScope.launch(Dispatchers.IO) {
            val payload = HeartbeatWorker.buildHeartbeatPayload(
                osRelease = Build.VERSION.RELEASE ?: "unknown",
                level = level,
                scale = scale,
                status = status,
                hasBatteryIntent = batteryIntent != null
            )

            var statusSummary: String
            var logStatus = "SUCCESS"

            try {
                api.heartbeat(payload).use { hbRes ->
                    if (hbRes.isSuccessful) {
                        identity.setLastHeartbeatAt(System.currentTimeMillis())
                        identity.saveMeta(meta.getString("deviceId"), baseUrl, "ACTIVE")
                        api.pendingCommands().use { cmdRes ->
                            if (cmdRes.isSuccessful) {
                                val root = JSONObject(cmdRes.body?.string().orEmpty())
                                val cmds = when {
                                    root.has("commands") -> root.getJSONArray("commands")
                                    root.has("pendingCommands") -> root.getJSONArray("pendingCommands")
                                    else -> org.json.JSONArray()
                                }
                                getSharedPreferences("vajra_cmd_state", Context.MODE_PRIVATE)
                                    .edit()
                                    .putLong("last_poll_at", System.currentTimeMillis())
                                    .putInt("last_poll_count", cmds.length())
                                    .apply()
                                if (cmds.length() > 0) {
                                    CommandPollWorker.enqueueImmediate(this@MainActivity)
                                }
                                statusSummary =
                                    "Request Device Status: COMPLETED (HTTP ${hbRes.code})\nSigned telemetry accepted; ${cmds.length()} pending console command(s)."
                            } else {
                                logStatus = "WARNING"
                                statusSummary =
                                    "Request Device Status: Heartbeat accepted (HTTP ${hbRes.code}), command poll returned HTTP ${cmdRes.code}."
                            }
                        }
                    } else {
                        logStatus = "FAILED"
                        val errBody = hbRes.body?.string().orEmpty()
                        if (hbRes.code == 401 || hbRes.code == 403) {
                            when {
                                errBody.contains("DEVICE_COMPROMISED") -> {
                                    identity.saveMeta(meta.getString("deviceId"), baseUrl, "COMPROMISED")
                                    HeartbeatWorker.cancel(this@MainActivity)
                                    CommandPollWorker.cancel(this@MainActivity)
                                }
                                errBody.contains("REVOKED") || errBody.contains("DECOMMISSIONED") || errBody.contains("DEVICE_CREDENTIAL_INACTIVE") -> {
                                    identity.saveMeta(meta.getString("deviceId"), baseUrl, "REVOKED")
                                    HeartbeatWorker.cancel(this@MainActivity)
                                    CommandPollWorker.cancel(this@MainActivity)
                                }
                            }
                        }
                        statusSummary = "Request Device Status: FAILED (HTTP ${hbRes.code})"
                    }
                }
            } catch (e: Exception) {
                logStatus = "WARNING"
                statusSummary =
                    "Request Device Status: Network error (${e.localizedMessage ?: e.javaClass.simpleName})"
            }

            DeviceActivityLog.record(
                context = this@MainActivity,
                category = "COMMAND",
                status = logStatus,
                title = "Device Status Sync",
                detail = statusSummary.replace('\n', ' ')
            )

            withContext(Dispatchers.Main) {
                btn?.isEnabled = true
                lastLocalCommandNote = statusSummary
                refresh()
            }
        }
    }

    private fun triggerImmediateSignedHeartbeat() {
        val meta = identity.loadMeta() ?: return
        val state = meta.optString("lifecycleState", "")
        if (state !in listOf("TRUSTED", "ACTIVE", "OFFLINE")) {
            Toast.makeText(this, "Heartbeat begins once owner approves this device.", Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnSendHeartbeat)
        btn?.isEnabled = false
        val prefs = getSharedPreferences("vajra_settings", Context.MODE_PRIVATE)
        val baseUrl = meta.optString("serverBaseUrl").ifBlank {
            prefs.getString("serverBaseUrl", getString(R.string.default_server_url)).orEmpty()
        }
        val api = VajraApiClient(identity, timeoutSeconds = 10) { baseUrl }
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        lifecycleScope.launch(Dispatchers.IO) {
            val payload = HeartbeatWorker.buildHeartbeatPayload(
                osRelease = Build.VERSION.RELEASE ?: "unknown",
                level = level,
                scale = scale,
                status = status,
                hasBatteryIntent = batteryIntent != null
            )
            var toastMsg: String
            try {
                api.heartbeat(payload).use { res ->
                    if (res.isSuccessful) {
                        identity.setLastHeartbeatAt(System.currentTimeMillis())
                        identity.saveMeta(meta.getString("deviceId"), baseUrl, "ACTIVE")
                        DeviceActivityLog.record(
                            context = this@MainActivity,
                            category = "HEARTBEAT",
                            status = "SUCCESS",
                            title = "Manual Signed Heartbeat",
                            detail = "ECDSA P-256 check-in accepted (HTTP ${res.code})."
                        )
                        toastMsg = "Signed heartbeat delivered (HTTP ${res.code})."
                    } else {
                        val errBody = res.body?.string().orEmpty()
                        if (res.code == 401 || res.code == 403) {
                            when {
                                errBody.contains("DEVICE_COMPROMISED") -> {
                                    identity.saveMeta(meta.getString("deviceId"), baseUrl, "COMPROMISED")
                                    HeartbeatWorker.cancel(this@MainActivity)
                                    CommandPollWorker.cancel(this@MainActivity)
                                }
                                errBody.contains("REVOKED") || errBody.contains("DECOMMISSIONED") || errBody.contains("DEVICE_CREDENTIAL_INACTIVE") -> {
                                    identity.saveMeta(meta.getString("deviceId"), baseUrl, "REVOKED")
                                    HeartbeatWorker.cancel(this@MainActivity)
                                    CommandPollWorker.cancel(this@MainActivity)
                                }
                            }
                        }
                        DeviceActivityLog.record(
                            context = this@MainActivity,
                            category = "HEARTBEAT",
                            status = "FAILED",
                            title = "Manual Heartbeat Failed (HTTP ${res.code})",
                            detail = "Console rejected check-in."
                        )
                        toastMsg = "Heartbeat rejected (HTTP ${res.code})."
                    }
                }
            } catch (e: Exception) {
                toastMsg = "Network error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                btn?.isEnabled = true
                Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun showDisconnectConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disconnect_title))
            .setMessage(getString(R.string.disconnect_message))
            .setPositiveButton(getString(R.string.disconnect_confirm)) { _, _ ->
                HeartbeatWorker.cancel(this)
                CommandPollWorker.cancel(this)
                RingAlarmController.stop(this)
                identity.deleteKey()
                lastLocalCommandNote = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncStatusWithServer(deviceId: String, baseUrl: String) {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        lifecycleScope.launch(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/devices/$deviceId/enrollment/status"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty().trim()
                    if (res.isSuccessful && raw.startsWith("{")) {
                        val json = JSONObject(raw)
                        val status = json.optString("status")
                        val lifecycleState = json.optString("lifecycleState")
                        val resolvedState = DeviceStatusResolver.resolveLifecycleState(status, lifecycleState)
                        identity.saveMeta(deviceId, baseUrl, resolvedState)
                        if (DeviceStatusResolver.isOperationalState(resolvedState)) {
                            HeartbeatWorker.enqueueImmediate(this@MainActivity)
                            CommandPollWorker.enqueueImmediate(this@MainActivity)
                            HeartbeatWorker.schedule(this@MainActivity)
                            CommandPollWorker.schedule(this@MainActivity)
                        } else if (DeviceStatusResolver.isQuarantinedOrRevokedState(resolvedState)) {
                            HeartbeatWorker.cancel(this@MainActivity)
                            CommandPollWorker.cancel(this@MainActivity)
                        }
                        withContext(Dispatchers.Main) {
                            renderStatusState(resolvedState, isLiveVerified = true)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            val cached = identity.loadMeta()?.optString("lifecycleState", "UNKNOWN") ?: "UNKNOWN"
                            renderStatusState(cached, isLiveVerified = false)
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    val cached = identity.loadMeta()?.optString("lifecycleState", "UNKNOWN") ?: "UNKNOWN"
                    renderStatusState(cached, isLiveVerified = false)
                }
            }
        }
    }

    private fun renderStatusState(lifecycle: String, isLiveVerified: Boolean) {
        val badgeStatus = findViewById<TextView>(R.id.badgeStatus) ?: return
        val txtSyncSource = findViewById<TextView>(R.id.txtSyncSource)
        val cardPending = findViewById<LinearLayout>(R.id.cardPendingApproval)
        val btnResumePending = findViewById<MaterialButton>(R.id.btnResumePendingApproval)

        val cardOverview = findViewById<LinearLayout>(R.id.cardSecurityOverview)
        val iconContainerOverview = findViewById<FrameLayout>(R.id.iconContainerOverview)
        val imgOverviewShield = findViewById<ImageView>(R.id.imgOverviewShield)
        val txtHeadline = findViewById<TextView>(R.id.txtOverviewHeadline)
        val txtSubtext = findViewById<TextView>(R.id.txtOverviewSubtext)

        val isPending = lifecycle == "ENROLLMENT_PENDING" || lifecycle == "PENDING"
        cardPending?.visibility = if (isPending) View.VISIBLE else View.GONE
        btnResumePending?.visibility = if (isPending) View.VISIBLE else View.GONE

        txtSyncSource?.text = if (isLiveVerified) "Verified with console" else "Cached local state"

        when (lifecycle) {
            "ACTIVE", "TRUSTED" -> {
                badgeStatus.text = if (isLiveVerified) "● PROTECTED · VERIFIED" else "● PROTECTED · CACHED"
                badgeStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (isLiveVerified) R.color.vajra_jade else R.color.vajra_text_secondary
                    )
                )
                badgeStatus.setBackgroundResource(
                    if (isLiveVerified) R.drawable.bg_badge_jade else R.drawable.bg_badge_neutral
                )

                cardOverview?.setBackgroundResource(R.drawable.bg_card_jade_accent)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_jade)
                imgOverviewShield?.setImageResource(R.drawable.ic_shield_check)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_jade))
                txtHeadline?.text = "Device Protected & Authorized"
                txtSubtext?.text = if (isLiveVerified) {
                    "Console verified this endpoint's ECDSA P-256 identity. Signed heartbeats and recovery polling are active."
                } else {
                    "Local ECDSA P-256 identity active. Live console reachability will refresh when connected."
                }
            }
            "OFFLINE" -> {
                badgeStatus.text = "● OFFLINE"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_amber))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_gold)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_gold_accent)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_gold)
                imgOverviewShield?.setImageResource(R.drawable.ic_pulse_heartbeat)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_amber))
                txtHeadline?.text = "Endpoint Offline"
                txtSubtext?.text = "The console marks this device offline because recent signed check-ins elapsed. Send a heartbeat when network connectivity is ready."
            }
            "ENROLLMENT_PENDING", "PENDING" -> {
                badgeStatus.text = "● AWAITING APPROVAL"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_gold)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_gold_accent)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_gold)
                imgOverviewShield?.setImageResource(R.drawable.ic_lock_pin)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_gold))
                txtHeadline?.text = "Pending Owner Approval"
                txtSubtext?.text = "Keystore public key was submitted. Confirm the fingerprint in your VAJRA web console to authorize protection."
            }
            "COMPROMISED" -> {
                badgeStatus.text = "● COMPROMISED"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_crimson)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_error)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_crimson)
                imgOverviewShield?.setImageResource(R.drawable.ic_shield_alert)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_crimson))
                txtHeadline?.text = "Device Quarantined (Compromised)"
                txtSubtext?.text = "The console flagged this endpoint as compromised. Trusted Plane-2 operations are quarantined until the device is revoked and re-enrolled."
            }
            "REVOKED" -> {
                badgeStatus.text = "● REVOKED"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_crimson)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_error)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_crimson)
                imgOverviewShield?.setImageResource(R.drawable.ic_shield_alert)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_crimson))
                txtHeadline?.text = "Device Credential Revoked"
                txtSubtext?.text = "This device was revoked on the VAJRA console. Disconnect below to clear local keys and re-pair if needed."
            }
            "DECOMMISSIONED" -> {
                badgeStatus.text = "● DECOMMISSIONED"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_crimson))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_crimson)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_error)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_crimson)
                imgOverviewShield?.setImageResource(R.drawable.ic_shield_alert)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_crimson))
                txtHeadline?.text = "Device Decommissioned"
                txtSubtext?.text = "This endpoint has been permanently archived on the VAJRA console. Disconnect below to remove local Keystore credentials."
            }
            else -> {
                badgeStatus.text = "● $lifecycle"
                badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_neutral)

                cardOverview?.setBackgroundResource(R.drawable.bg_card_surface)
                iconContainerOverview?.setBackgroundResource(R.drawable.bg_icon_badge_neutral)
                imgOverviewShield?.setImageResource(R.drawable.ic_shield_check)
                imgOverviewShield?.setColorFilter(ContextCompat.getColor(this, R.color.vajra_text_secondary))
                txtHeadline?.text = "Endpoint State: $lifecycle"
                txtSubtext?.text = "Use Refresh Status to check connectivity with your configured VAJRA server."
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
