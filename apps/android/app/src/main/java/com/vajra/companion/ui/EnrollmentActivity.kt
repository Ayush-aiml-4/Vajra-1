package com.vajra.companion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.Editable
import android.text.TextWatcher
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vajra.companion.BuildConfig
import com.vajra.companion.R
import com.vajra.companion.crypto.DeviceRequestSigner
import com.vajra.companion.identity.AndroidKeystoreIdentity
import com.vajra.companion.identity.DeviceActivityLog
import com.vajra.companion.identity.DeviceStatusResolver
import com.vajra.companion.network.ServerUrlValidator
import com.vajra.companion.network.VajraApiClient
import com.vajra.companion.work.CommandPollWorker
import com.vajra.companion.work.HeartbeatWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EnrollmentActivity : AppCompatActivity() {

    data class ParsedPairingBundle(
        val serverUrl: String?,
        val enrollmentToken: String,
        val deviceId: String,
        val challengeNonce: String
    )

    private var activeBundle: ParsedPairingBundle? = null
    private var pendingDeviceId: String? = null
    private var pendingBaseUrl: String? = null
    private var isPollingApproval = false
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var passcodeHasError = false

    // Camera2 in-app viewfinder and frame decoder resources
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: android.media.ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val isQrProcessed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val qrDecoder = com.vajra.companion.qr.QrCodeFrameDecoder()

    private val barcodeScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val contents = res.data?.getStringExtra("SCAN_RESULT")
                if (!contents.isNullOrBlank()) {
                    val parsed = parsePairingBundleJson(contents)
                    if (parsed != null) {
                        onBundleReadyCallback?.invoke(parsed)
                    } else {
                        showStatusBanner(getString(R.string.bundle_invalid), isError = true)
                    }
                }
            }
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val layoutDenied = findViewById<LinearLayout>(R.id.layoutCameraDenied)
            if (isGranted) {
                layoutDenied?.visibility = View.GONE
                clearStatusBanner()
                startInAppCameraScanner()
            } else {
                layoutDenied?.visibility = View.VISIBLE
                showStatusBanner(getString(R.string.camera_denied_desc), isError = true)
                findViewById<LinearLayout>(R.id.layoutManualFields)?.visibility = View.VISIBLE
                findViewById<TextView>(R.id.btnToggleManual)?.text = getString(R.string.toggle_manual_hide)
            }
        }

    private var onBundleReadyCallback: ((ParsedPairingBundle) -> Unit)? = null

    private fun showStatusBanner(message: String, isError: Boolean) {
        val banner = findViewById<LinearLayout>(R.id.layoutEnrollErrorBanner)
        val txt = findViewById<TextView>(R.id.txtEnrollResult)
        if (message.isBlank()) {
            banner?.visibility = View.GONE
            txt?.text = ""
            return
        }
        banner?.setBackgroundResource(if (isError) R.drawable.bg_card_error else R.drawable.bg_card_gold_accent)
        txt?.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.vajra_text_primary else R.color.vajra_gold
            )
        )
        txt?.text = message
        banner?.visibility = View.VISIBLE
    }

    private fun clearStatusBanner() {
        findViewById<LinearLayout>(R.id.layoutEnrollErrorBanner)?.visibility = View.GONE
        findViewById<TextView>(R.id.txtEnrollResult)?.text = ""
    }

    private fun startInAppCameraScanner() {
        val layoutCameraContainer = findViewById<RelativeLayout>(R.id.layoutCameraContainer)
        val textureView = findViewById<TextureView>(R.id.viewfinderTexture)

        isQrProcessed.set(false)
        layoutCameraContainer?.visibility = View.VISIBLE

        if (textureView != null) {
            if (textureView.isAvailable) {
                openCamera(textureView.surfaceTexture!!)
            } else {
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        openCamera(surface)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        closeCamera()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        startBackgroundThread()
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val yuvSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()
            val analysisSize = yuvSizes.firstOrNull { it.width <= 1280 && it.height <= 720 && it.width >= 640 }
                ?: yuvSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                ?: android.util.Size(640, 480)

            imageReader?.close()
            val reader = android.media.ImageReader.newInstance(
                analysisSize.width,
                analysisSize.height,
                android.graphics.ImageFormat.YUV_420_888,
                2
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (isQrProcessed.get()) {
                        return@setOnImageAvailableListener
                    }

                    val decodedText = qrDecoder.decodeImage(image, sensorOrientation)
                    if (!decodedText.isNullOrBlank()) {
                        if (isQrProcessed.compareAndSet(false, true)) {
                            runOnUiThread {
                                val parsed = parsePairingBundleJson(decodedText)
                                if (parsed != null) {
                                    onBundleReadyCallback?.invoke(parsed)
                                } else {
                                    isQrProcessed.set(false)
                                    showStatusBanner(getString(R.string.bundle_invalid), isError = true)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient per-frame decode errors
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCameraPreview(camera, surfaceTexture)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (_: Exception) {
            // Camera hardware open exception handled gracefully
        }
    }

    private fun startCameraPreview(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        try {
            val previewSurface = Surface(surfaceTexture)
            val readerSurface = imageReader?.surface
            val surfaces = listOfNotNull(previewSurface, readerSurface)

            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                if (readerSurface != null) {
                    addTarget(readerSurface)
                }
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                    } catch (_: Exception) {
                        // ignore session error
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (_: Exception) {
            // ignore preview setup error
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {}
        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}

        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("VajraCameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        fun parsePairingBundleJson(rawJson: String): ParsedPairingBundle? {
            return try {
                val obj = JSONObject(rawJson.trim())

                // Reject if protocol does not match VAJRA_ENROLL_V1
                val protocol = obj.optString("protocol").trim()
                if (protocol.isNotEmpty() && protocol != "VAJRA_ENROLL_V1") {
                    return null
                }

                val token = obj.optString("enrollmentToken").trim()
                    .ifEmpty { obj.optString("token").trim() }
                val deviceId = obj.optString("deviceId").trim()
                val challengeNonce = obj.optString("challengeNonce").trim()
                if (token.isEmpty() || deviceId.isEmpty() || challengeNonce.isEmpty()) {
                    return null
                }

                // Reject expired or malformed QR expiration payload
                val expiresAtStr = obj.optString("expiresAt").trim()
                if (expiresAtStr.isNotEmpty()) {
                    try {
                        val parsedEpoch = java.time.Instant.parse(expiresAtStr).toEpochMilli()
                        if (parsedEpoch <= System.currentTimeMillis()) {
                            return null
                        }
                    } catch (_: Exception) {
                        return null
                    }
                }

                val rawOrigin = obj.optString("serverUrl").trim()
                    .ifEmpty { obj.optString("origin").trim() }
                    .ifEmpty { obj.optString("serverBaseUrl").trim() }
                    .ifEmpty { obj.optString("server").trim() }

                val origin = ServerUrlValidator.normalizeAndValidate(rawOrigin)
                    ?: return null

                ParsedPairingBundle(
                    serverUrl = origin,
                    enrollmentToken = token,
                    deviceId = deviceId,
                    challengeNonce = challengeNonce
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enrollment)

        val prefs = getSharedPreferences("vajra_settings", MODE_PRIVATE)
        val defaultServer = prefs.getString("serverBaseUrl", getString(R.string.default_server_url))
            ?: getString(R.string.default_server_url)

        val scrollEnrollment = findViewById<ScrollView>(R.id.scrollEnrollment)
        val btnEnrollBack = findViewById<ImageButton>(R.id.btnEnrollBack)
        btnEnrollBack?.setOnClickListener { finish() }

        // Stepper views
        val stepNodes = listOf(
            findViewById<TextView>(R.id.stepNode1),
            findViewById<TextView>(R.id.stepNode2),
            findViewById<TextView>(R.id.stepNode3),
            findViewById<TextView>(R.id.stepNode4)
        )
        val stepTabs = listOf(
            findViewById<TextView>(R.id.stepTab1),
            findViewById<TextView>(R.id.stepTab2),
            findViewById<TextView>(R.id.stepTab3),
            findViewById<TextView>(R.id.stepTab4)
        )
        val stepConnectors = listOf(
            findViewById<View>(R.id.stepConnector12),
            findViewById<View>(R.id.stepConnector23),
            findViewById<View>(R.id.stepConnector34)
        )

        val layoutStepScan = findViewById<LinearLayout>(R.id.layoutStepScan)
        val layoutStepPasscode = findViewById<LinearLayout>(R.id.layoutStepPasscode)
        val layoutStepAwaitingApproval = findViewById<LinearLayout>(R.id.layoutStepAwaitingApproval)
        val layoutStepConnected = findViewById<LinearLayout>(R.id.layoutStepConnected)
        val layoutManualFields = findViewById<LinearLayout>(R.id.layoutManualFields)
        val layoutCameraContainer = findViewById<RelativeLayout>(R.id.layoutCameraContainer)

        val btnScanQr = findViewById<Button>(R.id.btnScanQr)
        val btnCloseScanner = findViewById<TextView>(R.id.btnCloseScanner)
        val btnRetryCamera = findViewById<Button>(R.id.btnRetryCamera)
        val btnPasteBundle = findViewById<Button>(R.id.btnPasteBundle)
        val btnToggleManual = findViewById<TextView>(R.id.btnToggleManual)
        val btnManualContinue = findViewById<Button>(R.id.btnManualContinue)

        val inputServerUrl = findViewById<EditText>(R.id.inputServerUrl)
        val inputToken = findViewById<EditText>(R.id.inputToken)
        val inputDeviceId = findViewById<EditText>(R.id.inputDeviceId)
        val inputChallenge = findViewById<EditText>(R.id.inputChallenge)

        val txtScannedTarget = findViewById<TextView>(R.id.txtScannedTarget)
        val inputPasscode = findViewById<EditText>(R.id.inputPasscode)
        val passcodeSlots = listOf(
            findViewById<TextView>(R.id.passcodeSlot0),
            findViewById<TextView>(R.id.passcodeSlot1),
            findViewById<TextView>(R.id.passcodeSlot2),
            findViewById<TextView>(R.id.passcodeSlot3),
            findViewById<TextView>(R.id.passcodeSlot4),
            findViewById<TextView>(R.id.passcodeSlot5)
        )
        val txtPasscodeError = findViewById<TextView>(R.id.txtPasscodeError)
        val btnSubmitEnroll = findViewById<Button>(R.id.btnSubmitEnroll)
        val btnBackToScan = findViewById<TextView>(R.id.btnBackToScan)

        val iconContainerStep3 = findViewById<FrameLayout>(R.id.iconContainerStep3)
        val progressAwaiting = findViewById<ProgressBar>(R.id.progressAwaiting)
        val imgStep3StateIcon = findViewById<ImageView>(R.id.imgStep3StateIcon)
        val badgeStep3State = findViewById<TextView>(R.id.badgeStep3State)
        val txtAwaitingTitle = findViewById<TextView>(R.id.txtAwaitingTitle)
        val txtAwaitingDesc = findViewById<TextView>(R.id.txtAwaitingDesc)
        val txtStep3DeviceModel = findViewById<TextView>(R.id.txtStep3DeviceModel)
        val txtStep3KeyBacking = findViewById<TextView>(R.id.txtStep3KeyBacking)
        val txtStep3ServerUrl = findViewById<TextView>(R.id.txtStep3ServerUrl)
        val txtFingerprintSummary = findViewById<TextView>(R.id.txtFingerprintSummary)
        val btnCopyEnrollFingerprint = findViewById<Button>(R.id.btnCopyEnrollFingerprint)
        val txtStep3DiagnosticBox = findViewById<TextView>(R.id.txtStep3DiagnosticBox)
        val btnCheckStatusNow = findViewById<Button>(R.id.btnCheckStatusNow)
        val btnRestartPairing = findViewById<Button>(R.id.btnRestartPairing)
        val btnDone = findViewById<Button>(R.id.btnDone)

        inputServerUrl.setText(defaultServer)

        fun renderPasscodeBoxes(digits: String) {
            val gold = ContextCompat.getColor(this, R.color.vajra_gold)
            val ivory = ContextCompat.getColor(this, R.color.vajra_text_primary)
            val muted = ContextCompat.getColor(this, R.color.vajra_text_muted)
            val activeIndex = digits.length.coerceAtMost(5)
            for (i in 0 until 6) {
                val slot = passcodeSlots[i] ?: continue
                if (i < digits.length) {
                    slot.text = digits[i].toString()
                    slot.setTextColor(ivory)
                    slot.setBackgroundResource(
                        if (passcodeHasError) R.drawable.bg_passcode_cell_error else R.drawable.bg_passcode_cell_filled
                    )
                } else if (i == activeIndex) {
                    slot.text = ""
                    slot.setTextColor(gold)
                    slot.setBackgroundResource(
                        if (passcodeHasError) R.drawable.bg_passcode_cell_error else R.drawable.bg_passcode_cell_active
                    )
                } else {
                    slot.text = ""
                    slot.setTextColor(muted)
                    slot.setBackgroundResource(
                        if (passcodeHasError) R.drawable.bg_passcode_cell_error else R.drawable.bg_passcode_cell_empty
                    )
                }
            }
        }

        renderPasscodeBoxes("")

        var updatingPasscode = false
        inputPasscode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingPasscode) return
                val raw = s?.toString().orEmpty()
                val clean = raw.filter { it.isDigit() }.take(6)
                if (clean != raw) {
                    updatingPasscode = true
                    inputPasscode.setText(clean)
                    inputPasscode.setSelection(clean.length)
                    updatingPasscode = false
                }
                if (passcodeHasError && clean.isNotEmpty()) {
                    passcodeHasError = false
                    txtPasscodeError?.visibility = View.GONE
                }
                renderPasscodeBoxes(clean)
            }
        })

        fun populateStep3IdentityCard(baseUrl: String, fingerprint: String?) {
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            txtStep3DeviceModel?.text = "$deviceModel · ECDSA P-256"
            val secLevel = try {
                AndroidKeystoreIdentity(this).getDetailedSecurityLevel()
            } catch (_: Exception) {
                AndroidKeystoreIdentity.KeystoreSecurityLevel.UNKNOWN
            }
            when (secLevel) {
                AndroidKeystoreIdentity.KeystoreSecurityLevel.STRONGBOX -> {
                    txtStep3KeyBacking?.text = "StrongBox HSM Keystore (Hardware-Bound)"
                    txtStep3KeyBacking?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                }
                AndroidKeystoreIdentity.KeystoreSecurityLevel.TEE -> {
                    txtStep3KeyBacking?.text = "Trusted Execution Environment (TEE Keystore)"
                    txtStep3KeyBacking?.setTextColor(ContextCompat.getColor(this, R.color.vajra_jade))
                }
                else -> {
                    txtStep3KeyBacking?.text = "Android Keystore Identity"
                    txtStep3KeyBacking?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
                }
            }
            txtStep3ServerUrl?.text = "Server: $baseUrl"
            txtFingerprintSummary?.text = fingerprint?.takeIf { it.isNotBlank() } ?: "Fingerprint unavailable"
        }

        fun setStep3AwaitingVisual() {
            iconContainerStep3?.setBackgroundResource(R.drawable.bg_icon_badge_gold)
            progressAwaiting?.visibility = View.VISIBLE
            imgStep3StateIcon?.visibility = View.GONE
            badgeStep3State?.setBackgroundResource(R.drawable.bg_badge_gold)
            badgeStep3State?.setTextColor(ContextCompat.getColor(this, R.color.vajra_gold))
            badgeStep3State?.text = "PENDING CONSOLE AUTHORIZATION"
            txtAwaitingTitle?.text = getString(R.string.step_awaiting_title)
            txtAwaitingDesc?.text = getString(R.string.step_awaiting_desc)
            txtStep3DiagnosticBox?.setBackgroundResource(R.drawable.bg_card_elevated)
            txtStep3DiagnosticBox?.setTextColor(ContextCompat.getColor(this, R.color.vajra_text_secondary))
            txtStep3DiagnosticBox?.text = "Polling console authorization status every 2.5 seconds…"
            btnCheckStatusNow?.visibility = View.VISIBLE
            btnRestartPairing?.visibility = View.VISIBLE
        }

        fun updateStepVisuals(step: Int) {
            val goldColor = ContextCompat.getColor(this, R.color.vajra_gold)
            val jadeColor = ContextCompat.getColor(this, R.color.vajra_jade)
            val mutedColor = ContextCompat.getColor(this, R.color.vajra_text_muted)
            val onAccentColor = ContextCompat.getColor(this, R.color.vajra_text_on_accent)
            val borderColor = ContextCompat.getColor(this, R.color.vajra_border_subtle)

            for (i in 0 until 4) {
                val node = stepNodes[i]
                val tab = stepTabs[i]
                val stepNum = i + 1
                when {
                    stepNum < step || (step == 4 && stepNum == 4) -> {
                        node?.setBackgroundResource(R.drawable.bg_step_node_done)
                        node?.text = "✓"
                        node?.setTextColor(jadeColor)
                        tab?.setTextColor(jadeColor)
                    }
                    stepNum == step -> {
                        node?.setBackgroundResource(R.drawable.bg_step_node_active)
                        node?.text = stepNum.toString()
                        node?.setTextColor(onAccentColor)
                        tab?.setTextColor(goldColor)
                    }
                    else -> {
                        node?.setBackgroundResource(R.drawable.bg_step_node_inactive)
                        node?.text = stepNum.toString()
                        node?.setTextColor(mutedColor)
                        tab?.setTextColor(mutedColor)
                    }
                }
            }

            for (i in 0 until 3) {
                val conn = stepConnectors[i]
                val done = (i + 1) < step || step == 4
                conn?.setBackgroundColor(if (done) jadeColor else borderColor)
            }

            if (step == 3) {
                setStep3AwaitingVisual()
            }

            layoutStepScan.visibility = if (step == 1) View.VISIBLE else View.GONE
            layoutStepPasscode.visibility = if (step == 2) View.VISIBLE else View.GONE
            layoutStepAwaitingApproval.visibility = if (step == 3) View.VISIBLE else View.GONE
            layoutStepConnected.visibility = if (step == 4) View.VISIBLE else View.GONE
            scrollEnrollment?.post { scrollEnrollment.smoothScrollTo(0, 0) }
        }

        fun onApprovalConnected() {
            clearStatusBanner()
            updateStepVisuals(4)
            lifecycleScope.launch {
                delay(2000)
                if (!isFinishing && !isDestroyed) {
                    val intent = Intent(this@EnrollmentActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }

        btnCopyEnrollFingerprint?.setOnClickListener {
            val fp = txtFingerprintSummary?.text?.toString().orEmpty()
            if (fp.isNotBlank() && fp != "Fingerprint unavailable") {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("VAJRA Public Key Fingerprint", fp))
                Toast.makeText(this, "Fingerprint copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnCheckStatusNow?.setOnClickListener {
            val targetBaseUrl = pendingBaseUrl
            val targetDeviceId = pendingDeviceId
            if (!targetBaseUrl.isNullOrBlank() && !targetDeviceId.isNullOrBlank()) {
                pollingJob?.cancel()
                isPollingApproval = false
                clearStatusBanner()
                updateStepVisuals(3)
                txtStep3DiagnosticBox?.text = "Checking console approval status now…"
                startPollingApproval(targetBaseUrl, targetDeviceId, immediate = true) {
                    onApprovalConnected()
                }
            }
        }

        btnRestartPairing?.setOnClickListener {
            pollingJob?.cancel()
            isPollingApproval = false
            HeartbeatWorker.cancel(this)
            CommandPollWorker.cancel(this)
            AndroidKeystoreIdentity(this).deleteKey()
            activeBundle = null
            pendingDeviceId = null
            pendingBaseUrl = null
            passcodeHasError = false
            inputPasscode.setText("")
            txtPasscodeError?.visibility = View.GONE
            clearStatusBanner()
            updateStepVisuals(1)
        }

        btnBackToScan?.setOnClickListener {
            activeBundle = null
            passcodeHasError = false
            inputPasscode.setText("")
            txtPasscodeError?.visibility = View.GONE
            clearStatusBanner()
            updateStepVisuals(1)
        }

        fun onBundleReady(bundle: ParsedPairingBundle) {
            closeCamera()
            layoutCameraContainer?.visibility = View.GONE
            activeBundle = bundle
            val targetUrl = bundle.serverUrl ?: defaultServer
            prefs.edit().putString("serverBaseUrl", targetUrl).apply()

            txtScannedTarget.text = "Device: ${bundle.deviceId}\nServer: $targetUrl"
            passcodeHasError = false
            inputPasscode.setText("")
            txtPasscodeError?.visibility = View.GONE
            clearStatusBanner()
            updateStepVisuals(2)
            inputPasscode.postDelayed({
                inputPasscode.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(inputPasscode, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
        onBundleReadyCallback = ::onBundleReady

        btnCloseScanner?.setOnClickListener {
            closeCamera()
            layoutCameraContainer?.visibility = View.GONE
        }

        btnToggleManual.setOnClickListener {
            val opening = layoutManualFields.visibility != View.VISIBLE
            layoutManualFields.visibility = if (opening) View.VISIBLE else View.GONE
            btnToggleManual.text = getString(
                if (opening) R.string.toggle_manual_hide else R.string.toggle_manual_entry
            )
        }

        btnPasteBundle.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (clipText.isEmpty()) {
                showStatusBanner(getString(R.string.bundle_empty_clipboard), isError = true)
                return@setOnClickListener
            }
            val parsed = parsePairingBundleJson(clipText)
            if (parsed == null) {
                showStatusBanner(getString(R.string.bundle_invalid), isError = true)
                return@setOnClickListener
            }
            onBundleReady(parsed)
        }

        btnScanQr.setOnClickListener {
            clearStatusBanner()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startInAppCameraScanner()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnRetryCamera?.setOnClickListener {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnManualContinue.setOnClickListener {
            val configuredUrl = inputServerUrl.text.toString().trim().trimEnd('/')
            val t = inputToken.text.toString().trim()
            val d = inputDeviceId.text.toString().trim()
            val c = inputChallenge.text.toString().trim()

            if (configuredUrl.isEmpty() || t.isEmpty() || d.isEmpty() || c.isEmpty()) {
                showStatusBanner(getString(R.string.enroll_fields_required), isError = true)
                return@setOnClickListener
            }
            val urlValidation = ServerUrlValidator.validate(configuredUrl)
            if (!urlValidation.valid || urlValidation.normalizedUrl == null) {
                showStatusBanner(
                    urlValidation.errorMessage ?: getString(R.string.invalid_server_url),
                    isError = true
                )
                return@setOnClickListener
            }

            val bundle = ParsedPairingBundle(
                serverUrl = urlValidation.normalizedUrl,
                enrollmentToken = t,
                deviceId = d,
                challengeNonce = c
            )
            onBundleReady(bundle)
        }

        fun submitPasscodeVerification() {
            val bundle = activeBundle
            if (bundle == null) {
                showStatusBanner("Pairing challenge missing. Please scan QR code first.", isError = true)
                updateStepVisuals(1)
                return
            }

            val passcode = inputPasscode.text.toString().filter { it.isDigit() }
            if (passcode.length < 6) {
                passcodeHasError = true
                renderPasscodeBoxes(passcode)
                txtPasscodeError?.text = getString(R.string.passcode_incomplete)
                txtPasscodeError?.visibility = View.VISIBLE
                return
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(inputPasscode.windowToken, 0)

            passcodeHasError = false
            txtPasscodeError?.visibility = View.GONE
            val targetServer = bundle.serverUrl ?: defaultServer
            btnSubmitEnroll.isEnabled = false
            showStatusBanner(getString(R.string.enroll_submitting), isError = false)

            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    executeEnrollmentSubmission(targetServer, bundle, passcode)
                }

                btnSubmitEnroll.isEnabled = true
                if (outcome.success) {
                    clearStatusBanner()
                    pendingDeviceId = bundle.deviceId
                    pendingBaseUrl = targetServer
                    populateStep3IdentityCard(targetServer, outcome.fingerprint)
                    updateStepVisuals(3)
                    startPollingApproval(targetServer, bundle.deviceId, immediate = false) {
                        onApprovalConnected()
                    }
                } else {
                    passcodeHasError = true
                    renderPasscodeBoxes(passcode)
                    val msg = outcome.errorMessage ?: "Enrollment submission failed."
                    txtPasscodeError?.text = msg
                    txtPasscodeError?.visibility = View.VISIBLE
                    showStatusBanner(msg, isError = true)
                }
            }
        }

        inputPasscode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPasscodeVerification()
                true
            } else {
                false
            }
        }

        btnSubmitEnroll.setOnClickListener {
            submitPasscodeVerification()
        }

        btnDone.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        }

        // Resume Step 3 automatically if Activity restarted while awaiting owner confirmation
        val existingIdentity = AndroidKeystoreIdentity(this)
        val savedMeta = existingIdentity.loadMeta()
        if (
            savedMeta != null &&
            savedMeta.optString("lifecycleState") == "ENROLLMENT_PENDING" &&
            existingIdentity.hasKey()
        ) {
            val savedDeviceId = savedMeta.optString("deviceId")
            val savedUrl = savedMeta.optString("serverBaseUrl").ifBlank { defaultServer }
            if (savedDeviceId.isNotBlank()) {
                val fp = savedMeta.optString("pendingFingerprint")
                    .ifBlank { existingIdentity.exportPublicIdentity().fingerprint }
                pendingDeviceId = savedDeviceId
                pendingBaseUrl = savedUrl
                populateStep3IdentityCard(savedUrl, fp)
                updateStepVisuals(3)
                startPollingApproval(savedUrl, savedDeviceId, immediate = true) {
                    onApprovalConnected()
                }
            }
        } else {
            updateStepVisuals(1)
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        closeCamera()
    }

    private data class EnrollmentOutcome(
        val success: Boolean,
        val fingerprint: String? = null,
        val errorMessage: String? = null
    )

    private fun executeEnrollmentSubmission(
        baseUrl: String,
        bundle: ParsedPairingBundle,
        passcode: String
    ): EnrollmentOutcome {
        return try {
            val identity = AndroidKeystoreIdentity(this)
            val pub = identity.rotateKey()

            val timestamp = DeviceRequestSigner.utcTimestampIso()
            val clientNonce = DeviceRequestSigner.newNonce()
            val transcript = DeviceRequestSigner.buildEnrollmentTranscript(
                bundle.deviceId, bundle.enrollmentToken, bundle.challengeNonce, pub.fingerprint, timestamp, clientNonce
            )
            val signature = DeviceRequestSigner.signEcdsaSha256(identity.getPrivateKey(), transcript)

            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val agentVersion = BuildConfig.VERSION_NAME

            val body = JSONObject()
                .put("enrollmentToken", bundle.enrollmentToken)
                .put("verificationPasscode", passcode)
                .put("publicKeyPem", pub.publicKeyPem)
                .put("keyAlgorithm", "ECDSA_P256")
                .put("signatureBase64", signature)
                .put("timestampIso", timestamp)
                .put("clientNonce", clientNonce)
                .put("deviceName", deviceModel)
                .put("osVersion", osVersion)
                .put("agentVersion", agentVersion)
                .put("hardwareBacked", pub.hardwareBacked)

            val api = VajraApiClient(identity) { baseUrl }
            api.enrollmentSubmit(body).use { res ->
                val respStr = res.body?.string().orEmpty()
                if (res.code in 300..399) {
                    val location = res.header("Location").orEmpty()
                    return EnrollmentOutcome(
                        success = false,
                        errorMessage = "Server redirected enrollment request (HTTP ${res.code}${if (location.isNotBlank()) " → $location" else ""}). Ensure the server URL reaches the VAJRA API directly without an auth portal."
                    )
                }

                if (!res.isSuccessful) {
                    val errMsg = try {
                        val errObj = JSONObject(respStr)
                        errObj.optJSONObject("error")?.optString("message") ?: "Server error (HTTP ${res.code})"
                    } catch (_: Exception) {
                        "Server error (HTTP ${res.code})"
                    }
                    return EnrollmentOutcome(success = false, errorMessage = errMsg)
                }

                val parsedOk = try {
                    val json = JSONObject(respStr)
                    val status = json.optString("status")
                    status == "KEY_SUBMITTED_AWAITING_OWNER_CONFIRMATION" || json.has("device")
                } catch (_: Exception) {
                    false
                }

                if (!parsedOk) {
                    return EnrollmentOutcome(
                        success = false,
                        errorMessage = "Received non-JSON or unexpected enrollment response from $baseUrl. Verify the server URL reaches the VAJRA backend API."
                    )
                }

                identity.saveMeta(bundle.deviceId, baseUrl, "ENROLLMENT_PENDING", pub.fingerprint)
                DeviceActivityLog.record(
                    context = this,
                    category = "ENROLLMENT",
                    status = "PENDING",
                    title = "Enrollment Identity Submitted",
                    detail = "ECDSA P-256 proof-of-possession sent to $baseUrl; awaiting owner approval."
                )

                EnrollmentOutcome(success = true, fingerprint = pub.fingerprint)
            }
        } catch (e: Exception) {
            EnrollmentOutcome(success = false, errorMessage = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun startPollingApproval(
        baseUrl: String,
        deviceId: String,
        immediate: Boolean = false,
        onConnected: () -> Unit
    ) {
        if (isPollingApproval) return
        isPollingApproval = true

        val iconContainerStep3 = findViewById<FrameLayout>(R.id.iconContainerStep3)
        val progressAwaiting = findViewById<ProgressBar>(R.id.progressAwaiting)
        val imgStep3StateIcon = findViewById<ImageView>(R.id.imgStep3StateIcon)
        val badgeStep3State = findViewById<TextView>(R.id.badgeStep3State)
        val txtAwaitingTitle = findViewById<TextView>(R.id.txtAwaitingTitle)
        val txtAwaitingDesc = findViewById<TextView>(R.id.txtAwaitingDesc)
        val txtStep3DiagnosticBox = findViewById<TextView>(R.id.txtStep3DiagnosticBox)
        val btnCheckStatusNow = findViewById<Button>(R.id.btnCheckStatusNow)
        val btnRestartPairing = findViewById<Button>(R.id.btnRestartPairing)

        fun renderStep3TerminalState(
            badgeText: String,
            title: String,
            desc: String,
            diagnostic: String,
            allowManualRetry: Boolean
        ) {
            isPollingApproval = false
            progressAwaiting?.visibility = View.GONE
            imgStep3StateIcon?.visibility = View.VISIBLE
            imgStep3StateIcon?.setColorFilter(ContextCompat.getColor(this@EnrollmentActivity, R.color.vajra_crimson))
            iconContainerStep3?.setBackgroundResource(R.drawable.bg_icon_badge_crimson)
            badgeStep3State?.setBackgroundResource(R.drawable.bg_badge_crimson)
            badgeStep3State?.setTextColor(ContextCompat.getColor(this@EnrollmentActivity, R.color.vajra_crimson))
            badgeStep3State?.text = badgeText
            txtAwaitingTitle?.text = title
            txtAwaitingDesc?.text = desc
            txtStep3DiagnosticBox?.setBackgroundResource(R.drawable.bg_card_error)
            txtStep3DiagnosticBox?.setTextColor(ContextCompat.getColor(this@EnrollmentActivity, R.color.vajra_text_primary))
            txtStep3DiagnosticBox?.text = diagnostic
            btnCheckStatusNow?.visibility = if (allowManualRetry) View.VISIBLE else View.GONE
            btnRestartPairing?.visibility = View.VISIBLE
        }

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/devices/$deviceId/enrollment/status"
            var attempts = 0
            var consecutiveNetworkErrors = 0
            var consecutiveHttpErrors = 0
            val maxAttempts = 120

            while (isActive && isPollingApproval && attempts < maxAttempts) {
                if (attempts > 0 || !immediate) {
                    delay(2500)
                }
                if (!isPollingApproval || !isActive) break
                attempts++

                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(req).execute().use { res ->
                        consecutiveNetworkErrors = 0

                        if (res.code in 300..399) {
                            consecutiveHttpErrors++
                            val location = res.header("Location").orEmpty()
                            withContext(Dispatchers.Main) {
                                txtStep3DiagnosticBox?.text =
                                    "Server redirected (HTTP ${res.code}${if (location.isNotBlank()) " → $location" else ""}) on check $attempts. Ensure Server URL reaches the VAJRA API without a browser login wall."
                            }
                            if (consecutiveHttpErrors >= 5) {
                                withContext(Dispatchers.Main) {
                                    renderStep3TerminalState(
                                        badgeText = "GATEWAY REDIRECT",
                                        title = "Gateway Authentication Required",
                                        desc = "The server at $baseUrl is redirecting API requests instead of returning VAJRA JSON.",
                                        diagnostic = "Repeated HTTP ${res.code} redirect detected. Configure a direct public VAJRA server URL and tap Check Approval Status Now.",
                                        allowManualRetry = true
                                    )
                                }
                                return@launch
                            }
                            return@use
                        }

                        if (res.isSuccessful) {
                            val respBody = res.body?.string().orEmpty()
                            val json = try {
                                JSONObject(respBody)
                            } catch (_: Exception) {
                                consecutiveHttpErrors++
                                withContext(Dispatchers.Main) {
                                    txtStep3DiagnosticBox?.text =
                                        "Received unexpected non-JSON response format (attempt $attempts). Retrying…"
                                }
                                if (consecutiveHttpErrors >= 6) {
                                    withContext(Dispatchers.Main) {
                                        renderStep3TerminalState(
                                            badgeText = "UNEXPECTED RESPONSE",
                                            title = "Invalid Server Response",
                                            desc = "The server returned non-JSON content (for example an HTML gateway or error page) instead of device enrollment status.",
                                            diagnostic = "Stopped after $attempts attempts due to repeated non-JSON responses from $baseUrl.",
                                            allowManualRetry = true
                                        )
                                    }
                                    return@launch
                                }
                                return@use
                            }

                            consecutiveHttpErrors = 0
                            val status = json.optString("status")
                            val lifecycleState = json.optString("lifecycleState")
                            val resolvedState = DeviceStatusResolver.resolveLifecycleState(status, lifecycleState)

                            if (resolvedState == "TRUSTED" || resolvedState == "ACTIVE") {
                                val identity = AndroidKeystoreIdentity(this@EnrollmentActivity)
                                identity.saveMeta(deviceId, baseUrl, resolvedState)

                                DeviceActivityLog.record(
                                    context = this@EnrollmentActivity,
                                    category = "ENROLLMENT",
                                    status = "SUCCESS",
                                    title = "Owner Approved Device",
                                    detail = "Device transitioned to $resolvedState; Keystore credential activated."
                                )

                                HeartbeatWorker.enqueueImmediate(this@EnrollmentActivity)
                                CommandPollWorker.enqueueImmediate(this@EnrollmentActivity)
                                HeartbeatWorker.schedule(this@EnrollmentActivity)
                                CommandPollWorker.schedule(this@EnrollmentActivity)

                                withContext(Dispatchers.Main) {
                                    isPollingApproval = false
                                    onConnected()
                                }
                                return@launch
                            } else if (resolvedState == "REVOKED" || resolvedState == "DECOMMISSIONED" || resolvedState == "COMPROMISED") {
                                AndroidKeystoreIdentity(this@EnrollmentActivity).saveMeta(deviceId, baseUrl, resolvedState)
                                DeviceActivityLog.record(
                                    context = this@EnrollmentActivity,
                                    category = "ENROLLMENT",
                                    status = "FAILED",
                                    title = "Owner Declined Enrollment",
                                    detail = "Enrollment ended in state $resolvedState on the VAJRA console."
                                )
                                withContext(Dispatchers.Main) {
                                    renderStep3TerminalState(
                                        badgeText = if (resolvedState == "COMPROMISED") "QUARANTINED" else "DECLINED BY OWNER",
                                        title = getString(R.string.step_rejected_title),
                                        desc = getString(R.string.step_rejected_desc),
                                        diagnostic = "Authorization halted by console (lifecycleState=$resolvedState, status=$status). Restart pairing to generate a new challenge.",
                                        allowManualRetry = false
                                    )
                                }
                                return@launch
                            } else {
                                withContext(Dispatchers.Main) {
                                    txtStep3DiagnosticBox?.text =
                                        "Waiting for console authorization… (check $attempts of $maxAttempts)"
                                }
                            }
                        } else if (res.code == 410) {
                            withContext(Dispatchers.Main) {
                                renderStep3TerminalState(
                                    badgeText = "CHALLENGE EXPIRED",
                                    title = getString(R.string.step_expired_title),
                                    desc = getString(R.string.step_expired_desc),
                                    diagnostic = "Server returned HTTP 410 Gone. Generate a fresh QR code in the VAJRA console.",
                                    allowManualRetry = false
                                )
                            }
                            return@launch
                        } else if (res.code == 404) {
                            consecutiveHttpErrors++
                            if (consecutiveHttpErrors >= 3) {
                                withContext(Dispatchers.Main) {
                                    renderStep3TerminalState(
                                        badgeText = "SESSION NOT FOUND",
                                        title = "Enrollment Session Not Found",
                                        desc = "Device session was not found on the server (HTTP 404). Ensure the QR code was scanned from the active VAJRA backend.",
                                        diagnostic = "Stopped after 3 consecutive HTTP 404 responses for device $deviceId.",
                                        allowManualRetry = false
                                    )
                                }
                                return@launch
                            } else {
                                withContext(Dispatchers.Main) {
                                    txtStep3DiagnosticBox?.text = "Verifying enrollment session (attempt $attempts)…"
                                }
                            }
                        } else {
                            consecutiveHttpErrors++
                            withContext(Dispatchers.Main) {
                                txtStep3DiagnosticBox?.text = "Server returned HTTP ${res.code} (attempt $attempts). Retrying…"
                            }
                            if (consecutiveHttpErrors >= 10) {
                                withContext(Dispatchers.Main) {
                                    renderStep3TerminalState(
                                        badgeText = "COMMUNICATION ERROR",
                                        title = "Server Communication Error",
                                        desc = "Received repeated HTTP ${res.code} errors while checking approval status.",
                                        diagnostic = "Tap Check Approval Status Now once the server at $baseUrl recovers.",
                                        allowManualRetry = true
                                    )
                                }
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    consecutiveNetworkErrors++
                    val errDetail = e.localizedMessage ?: e.javaClass.simpleName
                    withContext(Dispatchers.Main) {
                        txtStep3DiagnosticBox?.text = "Network check failed: $errDetail (attempt $attempts). Retrying…"
                    }
                    if (consecutiveNetworkErrors >= 10) {
                        withContext(Dispatchers.Main) {
                            renderStep3TerminalState(
                                badgeText = "CONNECTION FAILURE",
                                title = "Unable to Reach Server",
                                desc = "Could not connect to $baseUrl after $attempts attempts.",
                                diagnostic = "Last network error: $errDetail. Verify connectivity and tap Check Approval Status Now.",
                                allowManualRetry = true
                            )
                        }
                        return@launch
                    }
                }
            }

            if (isActive && isPollingApproval) {
                withContext(Dispatchers.Main) {
                    renderStep3TerminalState(
                        badgeText = "POLLING PAUSED",
                        title = "Approval Check Paused",
                        desc = "Automatic polling paused after $maxAttempts checks. Approve the device in your VAJRA console and tap below to check again.",
                        diagnostic = "Timed out waiting for owner confirmation at $baseUrl.",
                        allowManualRetry = true
                    )
                }
            }
        }
    }
}
