package id.iqbal.tiktokauto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

class BlinkTestActivity : Activity() {

    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictSub: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnStart: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var lastTriggers = -1
    private var triggeredUntil = 0L

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_blink_test)

        tvVerdict = findViewById(R.id.tvVerdict)
        tvVerdictSub = findViewById(R.id.tvVerdictSub)
        tvStats = findViewById(R.id.tvStats)
        btnStart = findViewById(R.id.btnStart)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnStart.setOnClickListener { startBlink() }

        if (Prefs(this).blinkEnabled) startServiceIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        BlinkScrollService.testMode = true
        startServiceIfNeeded()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        BlinkScrollService.testMode = false
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        BlinkScrollService.testMode = false
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startBlink()
        }
    }

    private fun startBlink() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            return
        }
        Prefs(this).blinkEnabled = true
        startServiceIfNeeded()
        render()
    }

    private fun startServiceIfNeeded() {
        if (!Prefs(this).blinkEnabled) return
        if (BlinkScrollService.isRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            startForegroundService(Intent(this, BlinkScrollService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun render() {
        val enabled = Prefs(this).blinkEnabled
        val state = BlinkScrollService.state
        val now = SystemClock.elapsedRealtime()

        if (BlinkScrollService.triggers > lastTriggers) {
            if (lastTriggers >= 0) triggeredUntil = now + 3500L
            lastTriggers = BlinkScrollService.triggers
        }

        btnStart.visibility = if (enabled) TextView.GONE else TextView.VISIBLE

        when {
            !enabled -> {
                tvVerdict.setText(R.string.blink_test_status_off)
                tvVerdictSub.setText(R.string.blink_test_hint)
            }
            now < triggeredUntil -> {
                tvVerdict.setText(R.string.blink_test_status_triggered)
                tvVerdictSub.setText(R.string.blink_test_status_triggered_sub)
            }
            state == BlinkScrollService.STATE_NO_PERM -> {
                tvVerdict.setText(R.string.blink_test_status_no_perm)
                tvVerdictSub.setText(R.string.blink_test_hint)
            }
            state == BlinkScrollService.STATE_ERROR -> {
                tvVerdict.setText(R.string.blink_test_status_error)
                tvVerdictSub.text = BlinkScrollService.lastError
                    ?: getString(R.string.blink_test_error_unknown)
            }
            state == BlinkScrollService.STATE_FACE -> {
                if (BlinkScrollService.blinksDetected > 0) {
                    tvVerdict.setText(R.string.blink_test_status_one_blink)
                    tvVerdictSub.setText(R.string.blink_test_status_one_blink_sub)
                } else {
                    tvVerdict.setText(R.string.blink_test_status_face)
                    tvVerdictSub.setText(R.string.blink_test_status_face_sub)
                }
            }
            state == BlinkScrollService.STATE_ACTIVE -> {
                if (BlinkScrollService.framesReceived == 0L && BlinkScrollService.cameraOpen) {
                    tvVerdict.setText(R.string.blink_test_status_no_frames)
                    tvVerdictSub.setText(R.string.blink_test_status_no_frames_sub)
                } else {
                    tvVerdict.setText(R.string.blink_test_status_active)
                    tvVerdictSub.setText(R.string.blink_test_status_active_sub)
                }
            }
            else -> {
                tvVerdict.setText(R.string.blink_test_status_waiting)
                tvVerdictSub.setText(R.string.blink_test_status_waiting_sub)
            }
        }

        val statusText = when {
            !enabled -> getString(R.string.blink_status_off)
            state == BlinkScrollService.STATE_FACE -> getString(R.string.blink_status_face)
            state == BlinkScrollService.STATE_ACTIVE -> getString(R.string.blink_status_active)
            state == BlinkScrollService.STATE_ERROR -> getString(R.string.blink_status_error)
            state == BlinkScrollService.STATE_NO_PERM -> getString(R.string.blink_status_no_perm)
            else -> getString(R.string.blink_status_waiting)
        }
        val cameraText = if (BlinkScrollService.cameraOpen) {
            "${getString(R.string.blink_test_camera_on)} ${BlinkScrollService.previewLabel}"
        } else {
            getString(R.string.blink_test_camera_off)
        }
        val eyeText = "${prob(BlinkScrollService.leftOpen)} / ${prob(BlinkScrollService.rightOpen)}"

        tvStats.text = buildString {
            line(R.string.blink_test_row_status, statusText)
            line(R.string.blink_test_row_camera, cameraText)
            line(
                R.string.blink_test_row_frames,
                "${BlinkScrollService.framesReceived} / ${BlinkScrollService.framesAnalyzed}"
            )
            line(R.string.blink_test_row_face, BlinkScrollService.faceHits.toString())
            line(R.string.blink_test_row_eyes, eyeText)
            line(R.string.blink_test_row_blinks, BlinkScrollService.blinksDetected.toString())
            line(R.string.blink_test_row_swipes, BlinkScrollService.triggers.toString())
            line(R.string.blink_test_row_error, BlinkScrollService.lastError ?: "-")
        }
    }

    private fun StringBuilder.line(labelRes: Int, value: String) {
        val label = getString(labelRes)
        append(label.padEnd(18))
        append(": ")
        append(value)
        append('\n')
    }

    private fun prob(value: Float): String =
        if (value < 0f) "-" else String.format(Locale.US, "%.2f", value)
}
