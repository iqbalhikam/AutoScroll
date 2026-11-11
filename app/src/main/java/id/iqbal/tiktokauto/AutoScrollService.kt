package id.iqbal.tiktokauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.lang.ref.WeakReference

class AutoScrollService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var bubbleAttemptAt = 0L
    private var flashView: TextView? = null
    private var flashHide: Runnable? = null

    private var currentPkg = ""
    private var foregroundTikTok = false
    private var wasTikTok = false
    private var lastScanPct = -1f
    private var endSince = 0L
    private var noProgressSince = 0L
    private var lastSwipeAt = 0L
    private var lastProgressPct = -1f
    private var lastProgressAt = 0L
    private var gestureBusy = false
    private var uiClosed = false
    private var adSince = 0L
    private var lastNotifyKey = ""
    private var lastNotifyAt = 0L

    private val tiktokPackages = TIKTOK_PACKAGES

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE) toggle()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            PREF_ENABLED -> {
                resetScanState()
                if (!prefs.enabled) setBubble(BubbleView.STATE_OFF, -1f)
                updateNotification()
            }
            PREF_BUBBLE -> {
                syncBubble()
                updateNotification()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: Throwable) {
            }
            main.postDelayed(this, if (foregroundTikTok) 300L else 1200L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        prefs.listen(prefListener)
        instance = WeakReference(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        uiClosed = !prefs.uiOpen

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        ensureChannel()
        if (!uiClosed) {
            syncBubble()
            updateNotification()
            main.removeCallbacks(poll)
            main.post(poll)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty()) currentPkg = pkg
        }
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::prefs.isInitialized) onUiClosed()
        super.onTaskRemoved(rootIntent)
    }

    private fun cleanup() {
        main.removeCallbacks(poll)
        try {
            unregisterReceiver(toggleReceiver)
        } catch (_: Exception) {
        }
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        removeBubble()
        dismissFlash()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
        if (instance?.get() === this) instance = null
    }

    private fun tick() {
        if (!::prefs.isInitialized) return
        if (uiClosed) return

        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (!power.isInteractive) {
            setBubble(BubbleView.STATE_IDLE, -1f)
            resetScanState()
            return
        }

        val root = rootInActiveWindow
        if (root != null) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty()) currentPkg = pkg
        }

        foregroundTikTok = currentPkg in tiktokPackages
        val now = SystemClock.elapsedRealtime()

        if (foregroundTikTok && !wasTikTok) {
            resetScanState()
            if (now - lastSwipeAt > 10_000L) lastSwipeAt = now
        }
        wasTikTok = foregroundTikTok

        if (!prefs.enabled) {
            setBubble(BubbleView.STATE_OFF, -1f)
            maybeNotify()
            return
        }

        if (prefs.bubble) maybeAddBubble()

        if (prefs.onlyTikTok && !foregroundTikTok) {
            setBubble(BubbleView.STATE_IDLE, -1f)
            resetScanState()
            maybeNotify()
            return
        }
        if (root == null) return

        val scan = scan(root)

        if (scan.inputFocused) {
            setBubble(BubbleView.STATE_IDLE, scan.pct)
            resetScanState()
            return
        }

        setBubble(
            if (scan.pct >= 0f) BubbleView.STATE_ACTIVE else BubbleView.STATE_SCAN,
            scan.pct
        )
        maybeNotify()

        if (gestureBusy) return
        if (now - lastSwipeAt < prefs.postScrollDelay + 500L) return

        if (scan.ad && prefs.skipAds) {
            if (adSince == 0L) adSince = now
            if (now - adSince >= 800L) {
                adSince = 0L
                doSwipe(viaAd = true)
                return
            }
        } else {
            adSince = 0L
        }

        val mode = prefs.mode
        val fallbackOn = mode != Prefs.MODE_PROGRESS || prefs.autoFallback

        if (scan.pct < 0f) {
            lastScanPct = -1f
            lastProgressPct = -1f
            lastProgressAt = 0L
            if (noProgressSince == 0L) noProgressSince = now
            val wait = maxOf(
                prefs.timerInterval.toLong(),
                if (mode == Prefs.MODE_TIMER) 0L else 2500L
            )
            if (fallbackOn && now - noProgressSince >= wait) doSwipe()
            return
        }
        noProgressSince = 0L

        if (lastProgressPct < 0f || scan.pct != lastProgressPct) {
            lastProgressPct = scan.pct
            lastProgressAt = now
        }

        if (mode == Prefs.MODE_TIMER) {
            if (now - lastSwipeAt >= prefs.timerInterval) doSwipe()
            lastScanPct = scan.pct
            return
        }

        if (scan.pct >= prefs.threshold) {
            if (endSince == 0L) endSince = now
            if (now - endSince >= 450L) {
                endSince = 0L
                doSwipe()
                return
            }
        } else {
            endSince = 0L
        }

        val previous = lastScanPct
        if (previous >= 70f && scan.pct <= 15f && previous - scan.pct >= 40f) {
            lastScanPct = scan.pct
            doSwipe()
            return
        }

        if (fallbackOn && lastProgressAt > 0L &&
            now - lastProgressAt >= maxOf(prefs.timerInterval.toLong(), 5000L)
        ) {
            doSwipe()
            return
        }

        lastScanPct = scan.pct
    }

    private fun resetScanState() {
        lastScanPct = -1f
        endSince = 0L
        noProgressSince = 0L
        lastProgressPct = -1f
        lastProgressAt = 0L
        adSince = 0L
    }

    private class Scan(val pct: Float, val ad: Boolean, val inputFocused: Boolean)

    private fun isAdLabel(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (AD_LABELS.any { value.contains(it, true) }) return true
        return value.length <= 40 && AD_SHORT_LABELS.any { value.equals(it, true) }
    }

    private fun isAdCta(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 45) return false
        return AD_CTAS.any { value.contains(it, true) }
    }

    private fun findAdByHint(root: AccessibilityNodeInfo): Boolean {
        for (label in AD_LABELS) {
            val found = try {
                root.findAccessibilityNodeInfosByText(label)
            } catch (_: Exception) {
                null
            } ?: continue
            if (found.any { it.isVisibleToUser }) return true
        }
        return false
    }

    private fun scan(root: AccessibilityNodeInfo): Scan {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        var ad = findAdByHint(root)
        var input = false
        var visited = 0
        val bounds = Rect()
        val screenHeight = resources.displayMetrics.heightPixels
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++

            val cls = node.className?.toString().orEmpty()
            if (!input && (cls == "android.widget.EditText" || node.isEditable)) input = true

            if (!ad) {
                val desc = node.contentDescription?.toString().orEmpty()
                val text = node.text?.toString().orEmpty()
                if (isAdLabel(desc) || isAdLabel(text)) {
                    ad = true
                } else if (isAdCta(desc) || isAdCta(text)) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top >= screenHeight * 0.35f) ad = true
                }
            }

            val range = node.rangeInfo
            if (range != null && range.max > range.min) {
                var score = 10
                if (cls.contains("SeekBar")) score += 100
                node.getBoundsInScreen(bounds)
                score += (bounds.width() / 40).coerceIn(0, 40)
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        var pct = -1f
        val target = best
        if (target != null) {
            val range = target.rangeInfo
            if (range != null && range.max > range.min) {
                pct = ((range.current - range.min) / (range.max - range.min) * 100f)
                    .coerceIn(0f, 100f)
            }
        }
        return Scan(pct, ad, input)
    }

    private fun doSwipe(
        force: Boolean = false,
        viaHand: Boolean = false,
        viaAd: Boolean = false
    ) {
        if (!::prefs.isInitialized) return
        if ((!prefs.enabled && !force) || gestureBusy) return

        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val x = width * 0.5f
        val startY = height * (prefs.swipeStart / 100f)
        val endY = startY - height * (prefs.swipeDistance / 100f)
        if (startY <= 0f || endY < height * 0.1f) return

        val duration = prefs.swipeDuration.toLong().coerceIn(100L, 1000L)
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        gestureBusy = true
        lastSwipeAt = SystemClock.elapsedRealtime()
        resetScanState()
        if (viaHand) flashChip(getString(R.string.swipe_source_hand), "#4ADE80")
        if (viaAd) flashChip(getString(R.string.swipe_source_ad), "#FBBF24")

        val launched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusy = false
                try {
                    prefs.addScroll()
                    feedback(viaHand)
                } catch (_: Throwable) {
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusy = false
            }
        }, null)

        if (!launched) {
            gestureBusy = false
            return
        }
        main.postDelayed({ gestureBusy = false }, duration + 500L)
    }

    fun testSwipe() {
        main.postDelayed({
            val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (pkg in tiktokPackages) {
                foregroundTikTok = true
                currentPkg = pkg
                doSwipe(force = true)
            }
        }, 5000L)
    }

    fun isTikTokForeground(): Boolean =
        currentPkg in tiktokPackages || foregroundTikTok

    fun externalSwipe() {
        if (!::prefs.isInitialized) return
        main.post { doSwipe(force = true, viaHand = true) }
    }

    fun onUiOpened() {
        if (!::prefs.isInitialized) return
        uiClosed = false
        prefs.uiOpen = true
        syncBubble()
        updateNotification()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    fun onUiClosed() {
        if (!::prefs.isInitialized) return
        uiClosed = true
        prefs.uiOpen = false
        main.removeCallbacks(poll)
        removeBubble()
        dismissFlash()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
    }

    fun toggle() {
        if (!::prefs.isInitialized) return
        prefs.enabled = !prefs.enabled
        resetScanState()
        setBubble(if (prefs.enabled) BubbleView.STATE_SCAN else BubbleView.STATE_OFF, -1f)
        updateNotification()
    }

    fun refreshBubble() {
        if (::prefs.isInitialized) syncBubble()
    }

    private fun feedback(viaHand: Boolean) {
        if (prefs.vibrate) {
            val vibrator = getSystemService(Vibrator::class.java)
            if (viaHand) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 30L, 60L, 30L), -1)
                )
            } else {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(16L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
        setBubble(BubbleView.STATE_ACTIVE, 0f)
        updateNotification()
    }

    private fun flashChip(message: String, accentColor: String) {
        val manager = wm ?: return
        dismissFlash()
        val dm = resources.displayMetrics
        val view = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E6111827"))
                setStroke(dp(1), Color.parseColor(accentColor))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = (dm.heightPixels * 0.12f).toInt()
        try {
            manager.addView(view, params)
            flashView = view
            val hide = Runnable { dismissFlash() }
            flashHide = hide
            main.postDelayed(hide, 1300L)
        } catch (_: Exception) {
            flashView = null
            flashHide = null
        }
    }

    private fun dismissFlash() {
        flashHide?.let { main.removeCallbacks(it) }
        flashHide = null
        val view = flashView ?: return
        flashView = null
        try {
            wm?.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun setBubble(state: Int, pct: Float) {
        bubble?.set(state, pct)
    }

    private fun maybeAddBubble() {
        if (bubble != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - bubbleAttemptAt < 4000L) return
        bubbleAttemptAt = now
        syncBubble()
    }

    private fun syncBubble() {
        if (!::prefs.isInitialized) return
        if (prefs.bubble && bubble == null) addBubble() else if (!prefs.bubble) removeBubble()
    }

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) return
        val manager = wm ?: return
        val size = dp(62)
        val view = BubbleView(this)
        view.onTap = { toggle() }
        view.onLongPress = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val dm = resources.displayMetrics
        params.x = if (prefs.bubbleX >= 0) {
            prefs.bubbleX.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        } else {
            (dm.widthPixels - size - dp(8)).coerceAtLeast(0)
        }
        params.y = if (prefs.bubbleY >= 0) {
            prefs.bubbleY.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        } else {
            dm.heightPixels / 3
        }

        view.onMove = { dx, dy ->
            params.x = (params.x + dx.toInt()).coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
            params.y = (params.y + dy.toInt()).coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
            try {
                manager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
        view.onDragEnd = {
            prefs.bubbleX = params.x
            prefs.bubbleY = params.y
        }

        try {
            manager.addView(view, params)
            bubble = view
            view.set(if (prefs.enabled) BubbleView.STATE_SCAN else BubbleView.STATE_OFF, -1f)
        } catch (_: Exception) {
        }
    }

    private fun removeBubble() {
        val view = bubble ?: return
        bubble = null
        try {
            wm?.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun maybeNotify() {
        if (!::prefs.isInitialized) return
        if (uiClosed) return
        val key = "${prefs.enabled}|${prefs.mode}|${prefs.bubble}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastNotifyKey && now - lastNotifyAt < 6000L) return
        updateNotification()
    }

    private fun updateNotification() {
        if (!::prefs.isInitialized) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (uiClosed) {
            nm.cancel(NOTIFY_ID)
            return
        }
        lastNotifyKey = "${prefs.enabled}|${prefs.mode}|${prefs.bubble}"
        lastNotifyAt = SystemClock.elapsedRealtime()

        val open = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            21,
            Intent(ACTION_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(if (prefs.enabled) R.string.action_pause else R.string.action_start),
            toggleIntent
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                getString(if (prefs.enabled) R.string.notif_on_title else R.string.notif_off_title)
            )
            .setContentText(
                getString(if (prefs.enabled) R.string.notif_on_text else R.string.notif_off_text)
            )
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(prefs.enabled)
            .addAction(action)

        try {
            nm.notify(NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "autoscroll.status"
        private const val NOTIFY_ID = 1001
        private const val ACTION_TOGGLE = "id.iqbal.tiktokauto.action.TOGGLE"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_BUBBLE = "bubble"

        private val AD_LABELS = arrayOf(
            "disponsori", "bersponsor", "sponsor", "sponsored",
            "paid partnership", "advertisement"
        )
        private val AD_SHORT_LABELS = arrayOf("iklan")
        private val AD_CTAS = arrayOf(
            "pelajari selengkapnya", "kunjungi situs web", "belanja sekarang",
            "unduh sekarang", "pasang sekarang", "pesan sekarang", "daftar sekarang",
            "hubungi kami", "lihat penawaran", "learn more", "shop now",
            "visit website", "download now", "install now", "sign up", "buy now"
        )

        var instance: WeakReference<AutoScrollService>? = null
            private set
    }
}
