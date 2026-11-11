package id.iqbal.tiktokauto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.abs

class HandScrollService : Service() {

    private val main = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var surface: SurfaceTexture? = null
    private var landmarker: HandLandmarker? = null

    private var frameWidth = 0
    private var frameHeight = 0
    private var frameRotation = 0

    @Volatile
    private var processing = false

    @Volatile
    private var lastFrameAt = 0L

    private var nextOpenAt = 0L
    private var cameraOpenedAt = 0L

    private var mpTimestampMs = 0L
    private var reusableBitmap: Bitmap? = null
    private var frameFailures = 0
    private var lastTriggerAt = 0L

    private class TrackPoint(val at: Long, val y: Float)

    private val track = ArrayDeque<TrackPoint>()

    private val prefs by lazy { Prefs(this) }

    private val loop = object : Runnable {
        override fun run() {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val tiktok = AutoScrollService.instance?.get()?.isTikTokForeground() == true
            if (power.isInteractive && (tiktok || testMode)) {
                openCamera()
                if (camera != null && lastFrameAt == 0L &&
                    SystemClock.elapsedRealtime() - cameraOpenedAt > 3500L
                ) {
                    lastError = "Kamera terbuka tetapi tidak mengirim frame"
                    stopCamera()
                    nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
                    setState(STATE_ERROR)
                }
            } else {
                stopCamera()
                setState(STATE_WAITING)
            }
            main.postDelayed(this, LOOP_MS)
        }
    }

    private val frameCallback = Camera.PreviewCallback { data, cam ->
        if (data == null) return@PreviewCallback
        if (data.isEmpty()) {
            cam.addCallbackBuffer(data)
            return@PreviewCallback
        }
        framesReceived++
        val now = SystemClock.elapsedRealtime()
        if (processing || now - lastFrameAt < FRAME_MIN_MS) {
            cam.addCallbackBuffer(data)
            return@PreviewCallback
        }

        processing = true
        lastFrameAt = now
        val frame = data.copyOf()
        cam.addCallbackBuffer(data)

        try {
            processFrame(frame)
            frameFailures = 0
            lastError = null
        } catch (error: Exception) {
            frameFailures++
            lastError = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "deteksi tangan gagal", error)
            if (frameFailures == FAILURES_BEFORE_ERROR) setState(STATE_ERROR)
        } finally {
            processing = false
        }
    }

    private fun processFrame(data: ByteArray) {
        val marker = landmarker ?: return
        val w = frameWidth
        val h = frameHeight
        if (w <= 0 || h <= 0) return

        val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
        val options = BitmapFactory.Options().apply { inBitmap = reusableBitmap }
        val bitmap = try {
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size(), options)
        } catch (_: IllegalArgumentException) {
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } ?: return
        reusableBitmap = bitmap

        val image = BitmapImageBuilder(bitmap).build()
        mpTimestampMs += FRAME_MIN_MS
        val processing = ImageProcessingOptions.builder()
            .setRotationDegrees(frameRotation)
            .build()
        val result = marker.detectForVideo(image, processing, mpTimestampMs)
        image.close()
        onHandResult(result)
    }

    private fun onHandResult(result: HandLandmarkerResult) {
        framesAnalyzed++
        if (!isRunning || camera == null) return

        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < 21) {
            handVisible = false
            palmY = -1f
            palmVel = 0f
            synchronized(track) {
                val oldest = track.peekFirst()
                if (oldest == null || SystemClock.elapsedRealtime() - oldest.at > 600L) track.clear()
            }
            setState(STATE_ACTIVE)
            return
        }

        handVisible = true
        handFrames++
        val y = (hand[0].y() + hand[5].y() + hand[9].y() + hand[13].y() + hand[17].y()) / 5f
        val now = SystemClock.elapsedRealtime()
        palmY = y

        val swiped = synchronized(track) {
            if (track.isEmpty() || now - track.peekLast().at > 400L) track.clear()
            track.addLast(TrackPoint(now, y))
            while (track.isNotEmpty() && now - track.peekFirst().at > SWIPE_WINDOW_MS) {
                track.pollFirst()
            }
            palmVel = -computeVelocity(track)
            if (now - lastTriggerAt < TRIGGER_COOLDOWN_MS) false else checkSwipe(track)
        }
        if (swiped) {
            lastTriggerAt = now
            swipes++
            synchronized(track) { track.clear() }
            AutoScrollService.instance?.get()?.externalSwipe()
        }
        setState(STATE_HAND)
    }

    private fun computeVelocity(points: ArrayDeque<TrackPoint>): Float {
        val first = points.peekFirst() ?: return 0f
        val last = points.peekLast() ?: return 0f
        val dur = (last.at - first.at) / 1000f
        if (dur <= 0f) return 0f
        return (last.y - first.y) / dur
    }

    private fun checkSwipe(points: ArrayDeque<TrackPoint>): Boolean {
        if (points.size < 4) return false
        val first = points.peekFirst()
        val last = points.peekLast()
        val dur = last.at - first.at
        if (dur < 120L || dur > SWIPE_WINDOW_MS) return false
        val dy = last.y - first.y
        val minDp = prefs.handSensitivity.coerceIn(10, 30) / 100f
        if (-dy < minDp) return false

        var downMoves = 0
        var prev = points.peekFirst()
        val iterator = points.iterator()
        iterator.next()
        while (iterator.hasNext()) {
            val cur = iterator.next()
            if (cur.y > prev.y + 0.005f) downMoves++
            prev = cur
        }
        return downMoves <= (points.size - 1) / 3
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).handEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundNow()) return START_NOT_STICKY
        if (!isRunning) resetStats()
        isRunning = true
        setState(if (hasCamera()) STATE_WAITING else STATE_NO_PERM)
        if (landmarker == null) landmarker = createLandmarker()

        main.removeCallbacks(loop)
        main.post(loop)
        return START_STICKY
    }

    private fun startForegroundNow(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFY_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFY_ID, notification)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "startForeground gagal", error)
            stopSelf()
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(loop)
        stopCamera()
        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null
        synchronized(track) { track.clear() }
        isRunning = false
        state = STATE_OFF
        super.onDestroy()
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun createLandmarker(): HandLandmarker? = try {
        HandLandmarker.createFromOptions(
            this,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_PATH)
                        .build()
                )
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (error: Exception) {
        Log.w(TAG, "detektor tangan tidak tersedia", error)
        lastError = error.message ?: error.javaClass.simpleName
        null
    }

    private fun openCamera() {
        if (camera != null) return
        if (!hasCamera()) {
            setState(STATE_NO_PERM)
            return
        }
        if (SystemClock.elapsedRealtime() < nextOpenAt) return
        if (landmarker == null) {
            landmarker = createLandmarker()
            if (landmarker == null) {
                nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
                setState(STATE_ERROR)
                return
            }
        }

        val cameraId = frontCameraId() ?: run {
            lastError = "Kamera depan tidak ditemukan"
            setState(STATE_ERROR)
            return
        }

        var opened: Camera? = null
        try {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)

            opened = Camera.open(cameraId)
            val params = opened.parameters
            params.previewFormat = ImageFormat.NV21
            val size = pickPreviewSize(params.supportedPreviewSizes)
            params.setPreviewSize(size.width, size.height)
            opened.parameters = params

            frameWidth = size.width
            frameHeight = size.height
            frameRotation = rotationFor(info)
            processing = false
            lastFrameAt = 0L
            lastError = null
            palmY = -1f
            palmVel = 0f
            handVisible = false

            val texture = SurfaceTexture(0)
            texture.setDefaultBufferSize(size.width, size.height)
            opened.setPreviewTexture(texture)

            val bufferSize =
                size.width * size.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(2) { opened.addCallbackBuffer(ByteArray(bufferSize)) }
            opened.setPreviewCallbackWithBuffer(frameCallback)
            opened.setErrorCallback { error, _ ->
                lastError = "Kamera error $error"
                main.post {
                    stopCamera()
                    nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
                    setState(STATE_ERROR)
                }
            }
            opened.startPreview()

            camera = opened
            surface = texture
            cameraOpenedAt = SystemClock.elapsedRealtime()
            previewLabel = "${size.width}x${size.height} rot $frameRotation"
            cameraOpen = true
            setState(STATE_ACTIVE)
        } catch (error: Exception) {
            Log.w(TAG, "gagal membuka kamera", error)
            lastError = error.message ?: error.javaClass.simpleName
            try {
                opened?.release()
            } catch (_: Exception) {
            }
            camera = null
            surface = null
            cameraOpen = false
            processing = false
            nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
            setState(STATE_ERROR)
        }
    }

    private fun stopCamera() {
        val cam = camera ?: return
        camera = null
        cameraOpen = false
        processing = false
        palmY = -1f
        palmVel = 0f
        handVisible = false
        synchronized(track) { track.clear() }
        try {
            cam.setPreviewCallbackWithBuffer(null)
        } catch (_: Exception) {
        }
        try {
            cam.stopPreview()
        } catch (_: Exception) {
        }
        try {
            cam.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
    }

    private fun frontCameraId(): Int? {
        val count = Camera.getNumberOfCameras()
        val info = Camera.CameraInfo()
        for (id in 0 until count) {
            Camera.getCameraInfo(id, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return id
        }
        return if (count > 0) 0 else null
    }

    private fun pickPreviewSize(sizes: List<Camera.Size>): Camera.Size =
        sizes.minByOrNull { abs(it.width - TARGET_W) + abs(it.height - TARGET_H) }
            ?: sizes.first()

    private fun rotationFor(info: Camera.CameraInfo): Int {
        val degrees = try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            when (wm.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (info.orientation + degrees) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
    }

    private fun setState(value: Int) {
        if (state == value) return
        state = value
        if (isRunning) updateNotification()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_hand_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFY_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            40,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            41,
            Intent(this, HandScrollService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(getString(R.string.notif_hand_title))
            .setContentText(getString(statusTextRes()))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.action_stop_hand),
                    stop
                ).build()
            )
            .build()
    }

    private fun statusTextRes(): Int = when (state) {
        STATE_ACTIVE -> R.string.hand_status_active
        STATE_HAND -> R.string.hand_status_hand
        STATE_ERROR -> R.string.hand_status_error
        STATE_NO_PERM -> R.string.hand_status_no_perm
        else -> R.string.hand_status_waiting
    }

    companion object {
        private const val TAG = "AutoScrollHand"
        private const val CHANNEL_ID = "autoscroll.hand"
        private const val NOTIFY_ID = 1002
        private const val ACTION_STOP = "id.iqbal.tiktokauto.action.STOP_HAND"
        private const val MODEL_PATH = "hand_landmarker.task"

        private const val LOOP_MS = 1200L
        private const val RETRY_MS = 8000L
        private const val FRAME_MIN_MS = 33L
        private const val FAILURES_BEFORE_ERROR = 25
        private const val JPEG_QUALITY = 85
        private const val TARGET_W = 640
        private const val TARGET_H = 480

        private const val SWIPE_WINDOW_MS = 600L
        private const val TRIGGER_COOLDOWN_MS = 1500L

        const val STATE_OFF = 0
        const val STATE_WAITING = 1
        const val STATE_ACTIVE = 2
        const val STATE_HAND = 3
        const val STATE_ERROR = 4
        const val STATE_NO_PERM = 5

        @Volatile
        var state = STATE_OFF
            private set

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var testMode = false

        @Volatile
        var cameraOpen = false
            private set

        @Volatile
        var framesReceived = 0L
            private set

        @Volatile
        var framesAnalyzed = 0L
            private set

        @Volatile
        var handVisible = false
            private set

        @Volatile
        var handFrames = 0L
            private set

        @Volatile
        var swipes = 0
            private set

        @Volatile
        var palmY = -1f
            private set

        @Volatile
        var palmVel = 0f
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var previewLabel = "-"
            private set

        private fun resetStats() {
            framesReceived = 0L
            framesAnalyzed = 0L
            handFrames = 0L
            swipes = 0
            palmY = -1f
            palmVel = 0f
            handVisible = false
            lastError = null
            previewLabel = "-"
        }
    }
}
