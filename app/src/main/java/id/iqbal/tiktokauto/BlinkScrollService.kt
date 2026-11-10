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
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import kotlin.math.abs

class BlinkScrollService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    private var camera: Camera? = null
    private var surface: SurfaceTexture? = null
    private var detector: FaceDetector? = null

    private var frameWidth = 0
    private var frameHeight = 0
    private var frameRotation = 0

    @Volatile
    private var processing = false

    @Volatile
    private var lastFrameAt = 0L

    private var nextOpenAt = 0L

    private var eyesClosedAt = 0L
    private var firstBlinkAt = 0L
    private var lastTriggerAt = 0L
    private var frameFailures = 0

    private val loop = object : Runnable {
        override fun run() {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val tiktok = AutoScrollService.instance?.get()?.isTikTokForeground() == true
            if (power.isInteractive && tiktok) {
                openCamera()
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
        val now = SystemClock.elapsedRealtime()
        if (processing || now - lastFrameAt < FRAME_INTERVAL_MS) {
            cam.addCallbackBuffer(data)
            return@PreviewCallback
        }

        processing = true
        lastFrameAt = now
        val frame = data.copyOf()
        cam.addCallbackBuffer(data)

        val faceDetector = detector
        if (faceDetector == null) {
            processing = false
            return@PreviewCallback
        }
        val image = InputImage.fromByteArray(
            frame,
            frameWidth,
            frameHeight,
            frameRotation,
            InputImage.IMAGE_FORMAT_NV21
        )
        faceDetector.process(image)
            .addOnSuccessListener(mainExecutor) { faces -> onFaces(faces) }
            .addOnFailureListener(mainExecutor) {
                processing = false
                frameFailures++
                if (frameFailures == FAILURES_BEFORE_ERROR) setState(STATE_ERROR)
            }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).blinkEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundNow()) return START_NOT_STICKY
        isRunning = true
        setState(if (hasCamera()) STATE_WAITING else STATE_NO_PERM)
        if (detector == null) detector = createDetector()

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

    override fun onDestroy() {
        main.removeCallbacks(loop)
        stopCamera()
        detector?.close()
        detector = null
        isRunning = false
        state = STATE_OFF
        super.onDestroy()
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun createDetector(): FaceDetector? = try {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build()
        )
    } catch (error: Exception) {
        Log.w(TAG, "detector tidak tersedia", error)
        null
    }

    private fun openCamera() {
        if (camera != null) return
        if (!hasCamera()) {
            setState(STATE_NO_PERM)
            return
        }
        if (SystemClock.elapsedRealtime() < nextOpenAt) return
        if (detector == null) {
            detector = createDetector()
            if (detector == null) {
                nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
                setState(STATE_ERROR)
                return
            }
        }

        val cameraId = frontCameraId() ?: run {
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

            val texture = SurfaceTexture(0)
            opened.setPreviewTexture(texture)
            opened.startPreview()

            frameWidth = size.width
            frameHeight = size.height
            frameRotation = rotationFor(info)
            processing = false
            lastFrameAt = 0L

            val bufferSize =
                size.width * size.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(2) { opened.addCallbackBuffer(ByteArray(bufferSize)) }
            opened.setPreviewCallbackWithBuffer(frameCallback)

            camera = opened
            surface = texture
            setState(STATE_ACTIVE)
        } catch (error: Exception) {
            Log.w(TAG, "gagal membuka kamera", error)
            try {
                opened?.release()
            } catch (_: Exception) {
            }
            camera = null
            surface = null
            processing = false
            nextOpenAt = SystemClock.elapsedRealtime() + RETRY_MS
            setState(STATE_ERROR)
        }
    }

    private fun stopCamera() {
        val cam = camera ?: return
        camera = null
        processing = false
        eyesClosedAt = 0L
        firstBlinkAt = 0L
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

    private fun onFaces(faces: List<Face>) {
        processing = false
        frameFailures = 0
        if (!isRunning || camera == null) return

        val face = faces.firstOrNull {
            it.leftEyeOpenProbability != null && it.rightEyeOpenProbability != null
        }
        if (face == null) {
            eyesClosedAt = 0L
            setState(STATE_ACTIVE)
            return
        }

        setState(STATE_FACE)
        val open = minOf(
            face.leftEyeOpenProbability ?: 1f,
            face.rightEyeOpenProbability ?: 1f
        )
        val now = SystemClock.elapsedRealtime()

        if (open < EYES_CLOSED) {
            if (eyesClosedAt == 0L) eyesClosedAt = now
            return
        }
        if (open < EYES_OPEN) return

        val closedAt = eyesClosedAt
        eyesClosedAt = 0L
        if (closedAt == 0L) return
        if (now - closedAt !in BLINK_MIN_MS..BLINK_MAX_MS) return
        registerBlink(now)
    }

    private fun registerBlink(now: Long) {
        if (now - lastTriggerAt < TRIGGER_COOLDOWN_MS) return

        if (firstBlinkAt == 0L || now - firstBlinkAt > DOUBLE_WINDOW_MS) {
            firstBlinkAt = now
            return
        }
        if (now - firstBlinkAt < DOUBLE_MIN_GAP_MS) return

        firstBlinkAt = 0L
        lastTriggerAt = now
        AutoScrollService.instance?.get()?.externalSwipe()
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
            (360 - (info.orientation + degrees) % 360) % 360
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
                getString(R.string.notif_blink_channel),
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
            Intent(this, BlinkScrollService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(getString(R.string.notif_blink_title))
            .setContentText(getString(statusTextRes()))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.action_stop_blink),
                    stop
                ).build()
            )
            .build()
    }

    private fun statusTextRes(): Int = when (state) {
        STATE_ACTIVE -> R.string.blink_status_active
        STATE_FACE -> R.string.blink_status_face
        STATE_ERROR -> R.string.blink_status_error
        STATE_NO_PERM -> R.string.blink_status_no_perm
        else -> R.string.blink_status_waiting
    }

    companion object {
        private const val TAG = "AutoScrollBlink"
        private const val CHANNEL_ID = "autoscroll.blink"
        private const val NOTIFY_ID = 1002
        private const val ACTION_STOP = "id.iqbal.tiktokauto.action.STOP_BLINK"

        private const val LOOP_MS = 1200L
        private const val RETRY_MS = 8000L
        private const val FRAME_INTERVAL_MS = 60L
        private const val FAILURES_BEFORE_ERROR = 25
        private const val TARGET_W = 320
        private const val TARGET_H = 240

        private const val EYES_CLOSED = 0.35f
        private const val EYES_OPEN = 0.7f
        private const val BLINK_MIN_MS = 40L
        private const val BLINK_MAX_MS = 900L
        private const val DOUBLE_WINDOW_MS = 2200L
        private const val DOUBLE_MIN_GAP_MS = 120L
        private const val TRIGGER_COOLDOWN_MS = 2000L

        const val STATE_OFF = 0
        const val STATE_WAITING = 1
        const val STATE_ACTIVE = 2
        const val STATE_FACE = 3
        const val STATE_ERROR = 4
        const val STATE_NO_PERM = 5

        @Volatile
        var state = STATE_OFF
            private set

        @Volatile
        var isRunning = false
            private set
    }
}
