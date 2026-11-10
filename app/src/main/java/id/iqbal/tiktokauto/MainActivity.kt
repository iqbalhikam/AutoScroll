package id.iqbal.tiktokauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var syncing = false

    private lateinit var statusPill: TextView
    private lateinit var enableSub: TextView
    private lateinit var swEnable: Switch
    private lateinit var rowAccessibility: android.widget.LinearLayout
    private lateinit var accStatus: TextView
    private lateinit var rowOverlay: android.widget.LinearLayout
    private lateinit var ovStatus: TextView
    private lateinit var rowNotif: android.widget.LinearLayout
    private lateinit var notifStatus: TextView

    private lateinit var segProgress: TextView
    private lateinit var segHybrid: TextView
    private lateinit var segTimer: TextView
    private lateinit var modeHint: TextView
    private lateinit var endGroup: android.widget.LinearLayout
    private lateinit var timerGroup: android.widget.LinearLayout
    private lateinit var thresholdBar: SeekBar
    private lateinit var thresholdValue: TextView
    private lateinit var intervalBar: SeekBar
    private lateinit var intervalValue: TextView

    private lateinit var delayBar: SeekBar
    private lateinit var delayValue: TextView
    private lateinit var durationBar: SeekBar
    private lateinit var durationValue: TextView
    private lateinit var distanceBar: SeekBar
    private lateinit var distanceValue: TextView

    private lateinit var swOnlyTiktok: Switch
    private lateinit var swSkipAds: Switch
    private lateinit var swFallback: Switch
    private lateinit var swVibrate: Switch
    private lateinit var swBubble: Switch

    private lateinit var swBlink: Switch
    private lateinit var blinkStatus: TextView
    private lateinit var rowBlinkPerm: android.widget.LinearLayout
    private lateinit var rowBlinkTest: android.widget.LinearLayout
    private lateinit var blinkPermStatus: TextView
    private var blinkSyncing = false
    private var pendingBlink = false

    private lateinit var presetSantai: TextView
    private lateinit var presetSeimbang: TextView
    private lateinit var presetCepat: TextView
    private lateinit var countValue: TextView

    private lateinit var updateVersion: TextView
    private lateinit var btnCheckUpdate: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var rowInstallPerm: android.widget.LinearLayout
    private lateinit var installStatus: TextView
    private lateinit var rowUpdateSource: android.widget.LinearLayout
    private lateinit var updateUrlValue: TextView
    private lateinit var swAutoUpdate: Switch

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val checkIntervalMs = 6L * 60 * 60 * 1000
    private var busy = false
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        bind()
        setupSeekBars()
        setupMode()
        setupSwitches()
        setupBlink()
        setupActions()
        setupUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.blinkEnabled) startBlinkService()
        refresh()
        maybeInstallPending()
        maybeAutoCheck()
        AutoScrollService.instance?.get()?.onUiOpened()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AutoScrollService.instance?.get()?.onUiClosed()
            stopService(Intent(this, BlinkScrollService::class.java))
        }
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                if (pendingBlink) {
                    prefs.blinkEnabled = true
                    startBlinkService()
                    toast(getString(R.string.toast_blink_on))
                }
            } else {
                prefs.blinkEnabled = false
                toast(getString(R.string.toast_blink_need_perm))
            }
            pendingBlink = false
        }
        refresh()
    }

    private fun bind() {
        statusPill = findViewById(R.id.statusPill)
        enableSub = findViewById(R.id.enableSub)
        swEnable = findViewById(R.id.swEnable)
        rowAccessibility = findViewById(R.id.rowAccessibility)
        accStatus = findViewById(R.id.accStatus)
        rowOverlay = findViewById(R.id.rowOverlay)
        ovStatus = findViewById(R.id.ovStatus)
        rowNotif = findViewById(R.id.rowNotif)
        notifStatus = findViewById(R.id.notifStatus)

        segProgress = findViewById(R.id.segProgress)
        segHybrid = findViewById(R.id.segHybrid)
        segTimer = findViewById(R.id.segTimer)
        modeHint = findViewById(R.id.modeHint)
        endGroup = findViewById(R.id.endGroup)
        timerGroup = findViewById(R.id.timerGroup)
        thresholdBar = findViewById(R.id.thresholdBar)
        thresholdValue = findViewById(R.id.thresholdValue)
        intervalBar = findViewById(R.id.intervalBar)
        intervalValue = findViewById(R.id.intervalValue)

        delayBar = findViewById(R.id.delayBar)
        delayValue = findViewById(R.id.delayValue)
        durationBar = findViewById(R.id.durationBar)
        durationValue = findViewById(R.id.durationValue)
        distanceBar = findViewById(R.id.distanceBar)
        distanceValue = findViewById(R.id.distanceValue)

        swOnlyTiktok = findViewById(R.id.swOnlyTiktok)
        swSkipAds = findViewById(R.id.swSkipAds)
        swFallback = findViewById(R.id.swFallback)
        swVibrate = findViewById(R.id.swVibrate)
        swBubble = findViewById(R.id.swBubble)

        swBlink = findViewById(R.id.swBlink)
        blinkStatus = findViewById(R.id.blinkStatus)
        rowBlinkPerm = findViewById(R.id.rowBlinkPerm)
        rowBlinkTest = findViewById(R.id.rowBlinkTest)
        blinkPermStatus = findViewById(R.id.blinkPermStatus)

        presetSantai = findViewById(R.id.presetSantai)
        presetSeimbang = findViewById(R.id.presetSeimbang)
        presetCepat = findViewById(R.id.presetCepat)
        countValue = findViewById(R.id.countValue)

        updateVersion = findViewById(R.id.updateVersion)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        updateStatus = findViewById(R.id.updateStatus)
        updateProgress = findViewById(R.id.updateProgress)
        rowInstallPerm = findViewById(R.id.rowInstallPerm)
        installStatus = findViewById(R.id.installStatus)
        rowUpdateSource = findViewById(R.id.rowUpdateSource)
        updateUrlValue = findViewById(R.id.updateUrlValue)
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
    }

    private fun setupSeekBars() {
        wireSeek(thresholdBar, thresholdValue, { "$it%" }, { prefs.threshold }) { prefs.threshold = it }
        wireSeek(intervalBar, intervalValue, { "$it dtk" }, { prefs.timerInterval / 1000 }) {
            prefs.timerInterval = it * 1000
        }
        wireSeek(delayBar, delayValue, { formatSeconds(it) }, { prefs.postScrollDelay }) {
            prefs.postScrollDelay = it
        }
        wireSeek(durationBar, durationValue, { "$it ms" }, { prefs.swipeDuration }) {
            prefs.swipeDuration = it
        }
        wireSeek(distanceBar, distanceValue, { "$it%" }, { prefs.swipeDistance }) {
            prefs.swipeDistance = it
        }
    }

    private fun wireSeek(
        bar: SeekBar,
        label: TextView,
        format: (Int) -> String,
        load: () -> Int,
        save: (Int) -> Unit
    ) {
        val initial = load().coerceIn(bar.min, bar.max)
        bar.progress = initial
        label.text = format(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = format(progress)
                if (fromUser) save(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun setupMode() {
        segProgress.setOnClickListener { setMode(Prefs.MODE_PROGRESS) }
        segHybrid.setOnClickListener { setMode(Prefs.MODE_HYBRID) }
        segTimer.setOnClickListener { setMode(Prefs.MODE_TIMER) }
    }

    private fun setMode(mode: String) {
        prefs.mode = mode
        refreshMode()
    }

    private fun setupSwitches() {
        swEnable.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.enabled = checked
            if (checked) ensureReady()
            refreshStatus()
            AutoScrollService.instance?.get()?.refreshBubble()
        }
        swOnlyTiktok.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.onlyTikTok = checked
        }
        swSkipAds.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.skipAds = checked
        }
        swFallback.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.autoFallback = checked
        }
        swVibrate.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.vibrate = checked
        }
        swBubble.setOnCheckedChangeListener { _, checked ->
            if (!syncing) {
                prefs.bubble = checked
                if (checked && !Settings.canDrawOverlays(this)) requestOverlayPermission()
                AutoScrollService.instance?.get()?.refreshBubble()
            }
        }
        swAutoUpdate.setOnCheckedChangeListener { _, checked ->
            if (!syncing) prefs.autoCheckUpdates = checked
        }
    }

    private fun setupBlink() {
        swBlink.setOnCheckedChangeListener { _, checked ->
            if (blinkSyncing) return@setOnCheckedChangeListener
            if (checked) enableBlink() else disableBlink()
        }
        rowBlinkPerm.setOnClickListener { requestCameraPermission() }
        rowBlinkTest.setOnClickListener {
            startActivity(Intent(this, BlinkTestActivity::class.java))
        }
    }

    private fun enableBlink() {
        if (!isAccessibilityEnabled()) toast(getString(R.string.toast_blink_need_acc))
        if (!cameraGranted()) {
            pendingBlink = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            return
        }
        prefs.blinkEnabled = true
        requestNotificationPermission()
        startBlinkService()
        refreshBlink()
    }

    private fun disableBlink() {
        pendingBlink = false
        prefs.blinkEnabled = false
        stopService(Intent(this, BlinkScrollService::class.java))
        toast(getString(R.string.toast_blink_off))
        refreshBlink()
    }

    private fun startBlinkService() {
        if (!cameraGranted()) {
            prefs.blinkEnabled = false
            return
        }
        if (BlinkScrollService.isRunning) return
        try {
            startForegroundService(Intent(this, BlinkScrollService::class.java))
        } catch (_: Exception) {
            toast(getString(R.string.blink_status_error))
        }
    }

    private fun requestCameraPermission() {
        if (cameraGranted()) {
            toast(getString(R.string.toast_camera_on))
            return
        }
        pendingBlink = false
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
    }

    private fun cameraGranted(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun refreshBlink() {
        blinkSyncing = true
        try {
            if (swBlink.isChecked != prefs.blinkEnabled) {
                swBlink.isChecked = prefs.blinkEnabled
            }
        } finally {
            blinkSyncing = false
        }
        paintGrant(blinkPermStatus, cameraGranted())
        blinkStatus.text = getString(
            when {
                !prefs.blinkEnabled -> R.string.blink_status_off
                !cameraGranted() -> R.string.blink_status_no_perm
                else -> when (BlinkScrollService.state) {
                    BlinkScrollService.STATE_FACE -> R.string.blink_status_face
                    BlinkScrollService.STATE_ACTIVE -> R.string.blink_status_active
                    BlinkScrollService.STATE_ERROR -> R.string.blink_status_error
                    else -> R.string.blink_status_waiting
                }
            }
        )
    }

    private fun setupActions() {
        rowAccessibility.setOnClickListener { openAccessibilitySettings() }
        rowOverlay.setOnClickListener { requestOverlayPermission() }
        rowNotif.setOnClickListener { openNotificationSettings() }

        findViewById<TextView>(R.id.btnTest).setOnClickListener {
            val service = AutoScrollService.instance?.get()
            if (service == null) {
                toast(getString(R.string.toast_service_off))
            } else if (launchTikTok()) {
                service.testSwipe()
                toast(getString(R.string.toast_test_scheduled))
            } else {
                toast(getString(R.string.toast_no_tiktok))
            }
        }

        findViewById<TextView>(R.id.btnReset).setOnClickListener {
            prefs.resetCount()
            refreshStats()
            toast(getString(R.string.toast_reset))
        }

        presetSantai.setOnClickListener {
            applyPreset(99, 2500, 260, 34, 20000, getString(R.string.preset_calm))
        }
        presetSeimbang.setOnClickListener {
            applyPreset(97, 1200, 220, 38, 15000, getString(R.string.preset_balanced))
        }
        presetCepat.setOnClickListener {
            applyPreset(94, 700, 180, 45, 9000, getString(R.string.preset_fast))
        }
    }

    private fun setupUpdate() {
        btnCheckUpdate.setOnClickListener { manualCheck() }
        rowInstallPerm.setOnClickListener { openInstallPermission() }
        rowUpdateSource.setOnClickListener { editUpdateUrl() }
    }

    private fun refreshUpdate() {
        updateVersion.text = getString(
            R.string.update_version,
            UpdateManager.currentVersionName(this)
        )
        updateUrlValue.text = prefs.updateUrl.ifBlank { getString(R.string.update_url_unset) }
        if (swAutoUpdate.isChecked != prefs.autoCheckUpdates) {
            swAutoUpdate.isChecked = prefs.autoCheckUpdates
        }
        paintGrant(installStatus, canInstallPackages())
    }

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun openInstallPermission() {
        if (canInstallPackages()) {
            toast(getString(R.string.toast_install_perm_on))
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun manualCheck() {
        if (busy) return
        if (prefs.updateUrl.isBlank()) {
            updateStatus.text = getString(R.string.update_status_unset)
            editUpdateUrl()
            return
        }
        checkForUpdates(true)
    }

    private fun maybeAutoCheck() {
        if (busy || !prefs.autoCheckUpdates) return
        if (prefs.updateUrl.isBlank()) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < checkIntervalMs) return
        checkForUpdates(false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (busy) return
        val url = prefs.updateUrl
        if (url.isBlank()) {
            if (manual) editUpdateUrl()
            return
        }

        setBusy(true)
        prefs.lastUpdateCheck = System.currentTimeMillis()
        updateStatus.text = getString(R.string.update_checking)
        setUpdating(false)

        io.execute {
            val result = runCatching { UpdateManager.fetch(url) }
            onUi {
                setBusy(false)
                result.onSuccess { info ->
                    if (info.versionCode > UpdateManager.currentVersionCode(this)) {
                        updateStatus.text = getString(
                            R.string.update_available,
                            info.versionName
                        )
                        showUpdateDialog(info)
                    } else {
                        updateStatus.text = getString(
                            R.string.update_latest,
                            UpdateManager.currentVersionName(this)
                        )
                        if (manual) toast(getString(R.string.toast_update_latest))
                    }
                }.onFailure { error ->
                    updateStatus.text = getString(
                        R.string.update_failed,
                        error.message ?: getString(R.string.update_install_unknown)
                    )
                    if (manual) toast(getString(R.string.toast_update_failed))
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, info.versionName))
            .setMessage(info.notes.ifBlank { getString(R.string.update_notes_empty) })
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        if (busy) return
        setBusy(true)
        updateProgress.isIndeterminate = false
        setUpdating(true, 0)
        updateStatus.text = getString(R.string.update_downloading, 0)

        io.execute {
            val result = runCatching {
                val apk = File(cacheDir, "update.apk")
                UpdateManager.download(info, apk) { pct ->
                    onUi {
                        if (pct < 0) {
                            updateProgress.isIndeterminate = true
                            updateStatus.text =
                                getString(R.string.update_downloading_unknown)
                        } else {
                            updateProgress.isIndeterminate = false
                            updateProgress.progress = pct
                            updateStatus.text = getString(R.string.update_downloading, pct)
                        }
                    }
                }
                apk
            }
            onUi {
                setBusy(false)
                setUpdating(false)
                updateProgress.isIndeterminate = false
                result.onSuccess { apk ->
                    updateStatus.text = getString(R.string.update_ready)
                    pendingApk = apk
                    tryInstall(apk)
                }.onFailure { error ->
                    updateStatus.text = getString(
                        R.string.update_failed,
                        error.message ?: getString(R.string.update_install_unknown)
                    )
                    toast(getString(R.string.toast_update_failed))
                }
            }
        }
    }

    private fun tryInstall(apk: File) {
        if (!canInstallPackages()) {
            updateStatus.text = getString(R.string.update_need_perm)
            AlertDialog.Builder(this)
                .setTitle(R.string.update_perm_title)
                .setMessage(R.string.update_perm_msg)
                .setPositiveButton(R.string.update_perm_open) { _, _ -> openInstallPermission() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        pendingApk = null
        runCatching { UpdateManager.install(this, apk) }
            .onSuccess {
                updateStatus.text = getString(R.string.update_installing)
            }
            .onFailure { error ->
                updateStatus.text = getString(
                    R.string.update_failed,
                    error.message ?: getString(R.string.update_install_unknown)
                )
                toast(getString(R.string.toast_update_failed))
            }
    }

    private fun maybeInstallPending() {
        val apk = pendingApk ?: return
        if (!apk.exists()) {
            pendingApk = null
            return
        }
        if (!canInstallPackages()) return
        tryInstall(apk)
    }

    private fun editUpdateUrl() {
        val input = EditText(this).apply {
            setText(prefs.updateUrl)
            hint = getString(R.string.update_source_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(
            input,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_source_title)
            .setView(container)
            .setPositiveButton(R.string.update_source_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && !value.startsWith("https://", ignoreCase = true)) {
                    toast(getString(R.string.update_source_invalid))
                    return@setOnClickListener
                }
                prefs.updateUrl = value
                refreshUpdate()
                updateStatus.text = getString(
                    if (value.isBlank()) R.string.update_status_unset
                    else R.string.update_status_idle
                )
                dialog.dismiss()
                if (value.isNotEmpty()) toast(getString(R.string.update_source_saved))
            }
        }
        dialog.show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        btnCheckUpdate.isEnabled = !value
        btnCheckUpdate.alpha = if (value) 0.55f else 1f
    }

    private fun setUpdating(show: Boolean, pct: Int = 0) {
        updateProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateProgress.progress = pct.coerceIn(0, 100)
    }

    private fun onUi(block: () -> Unit) {
        ui.post { if (!isFinishing && !isDestroyed) block() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ensureReady() {
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.toast_enable_acc))
            openAccessibilitySettings()
        }
        if (prefs.bubble && !Settings.canDrawOverlays(this)) requestOverlayPermission()
        requestNotificationPermission()
    }

    private fun applyPreset(
        threshold: Int,
        delay: Int,
        duration: Int,
        distance: Int,
        interval: Int,
        name: String
    ) {
        prefs.threshold = threshold
        prefs.postScrollDelay = delay
        prefs.swipeDuration = duration
        prefs.swipeDistance = distance
        prefs.timerInterval = interval
        refresh()
        toast(getString(R.string.toast_preset, name))
    }

    private fun refresh() {
        syncing = true
        try {
            swEnable.isChecked = prefs.enabled
            swOnlyTiktok.isChecked = prefs.onlyTikTok
            swSkipAds.isChecked = prefs.skipAds
            swFallback.isChecked = prefs.autoFallback
            swVibrate.isChecked = prefs.vibrate
            swBubble.isChecked = prefs.bubble
            swAutoUpdate.isChecked = prefs.autoCheckUpdates

            thresholdBar.progress = prefs.threshold
            intervalBar.progress = prefs.timerInterval / 1000
            delayBar.progress = prefs.postScrollDelay
            durationBar.progress = prefs.swipeDuration
            distanceBar.progress = prefs.swipeDistance

            thresholdValue.text = "${prefs.threshold}%"
            intervalValue.text = "${prefs.timerInterval / 1000} dtk"
            delayValue.text = formatSeconds(prefs.postScrollDelay)
            durationValue.text = "${prefs.swipeDuration} ms"
            distanceValue.text = "${prefs.swipeDistance}%"

            refreshMode()
            refreshStatus()
            refreshStats()
            refreshUpdate()
            refreshBlink()
        } finally {
            syncing = false
        }
    }

    private fun refreshMode() {
        val mode = prefs.mode
        paintSegment(segProgress, mode == Prefs.MODE_PROGRESS)
        paintSegment(segHybrid, mode == Prefs.MODE_HYBRID)
        paintSegment(segTimer, mode == Prefs.MODE_TIMER)

        endGroup.visibility = if (mode == Prefs.MODE_TIMER) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        modeHint.text = getString(
            when (mode) {
                Prefs.MODE_HYBRID -> R.string.mode_hint_hybrid
                Prefs.MODE_TIMER -> R.string.mode_hint_timer
                else -> R.string.mode_hint_progress
            }
        )
    }

    private fun paintSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_on else R.drawable.bg_segment
        )
        view.setTextColor(getColor(if (selected) R.color.accent else R.color.textSecondary))
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notif = notificationsEnabled()

        paintGrant(accStatus, acc)
        paintGrant(ovStatus, overlay)
        paintGrant(notifStatus, notif)

        when {
            !acc -> {
                statusPill.text = getString(R.string.status_need)
                statusPill.setBackgroundResource(R.drawable.bg_pill_warn)
                statusPill.setTextColor(getColor(R.color.warn))
            }

            prefs.enabled -> {
                statusPill.text = getString(R.string.status_on)
                statusPill.setBackgroundResource(R.drawable.bg_pill_on)
                statusPill.setTextColor(getColor(R.color.accent))
            }

            else -> {
                statusPill.text = getString(R.string.status_off)
                statusPill.setBackgroundResource(R.drawable.bg_pill_off)
                statusPill.setTextColor(getColor(R.color.textSecondary))
            }
        }
        enableSub.text = getString(
            if (prefs.enabled) R.string.enable_sub_on else R.string.enable_sub_off
        )
    }

    private fun paintGrant(view: TextView, granted: Boolean) {
        view.text = getString(if (granted) R.string.granted else R.string.not_granted)
        view.setTextColor(getColor(if (granted) R.color.accent else R.color.textMuted))
    }

    private fun refreshStats() {
        countValue.text = prefs.scrollCount().toString()
    }

    private fun launchTikTok(): Boolean {
        for (pkg in TIKTOK_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            startActivity(intent)
            return true
        }
        return false
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AutoScrollService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    private fun notificationsEnabled(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_overlay_on))
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun formatSeconds(ms: Int): String =
        String.format(Locale("id", "ID"), "%.1f dtk", ms / 1000f)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
