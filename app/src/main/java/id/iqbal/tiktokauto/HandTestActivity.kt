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

class HandTestActivity : Activity() {

    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictSub: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnStart: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var lastSwipes = -1
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
        setContentView(R.layout.activity_hand_test)

        tvVerdict = findViewById(R.id.tvVerdict)
        tvVerdictSub = findViewById(R.id.tvVerdictSub)
        tvStats = findViewById(R.id.tvStats)
        btnStart = findViewById(R.id.btnStart)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnStart.setOnClickListener { startHand() }

        if (Prefs(this).handEnabled) startServiceIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        HandScrollService.testMode = true
        startServiceIfNeeded()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        HandScrollService.testMode = false
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        HandScrollService.testMode = false
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startHand()
        }
    }

    private fun startHand() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            return
        }
        Prefs(this).handEnabled = true
        startServiceIfNeeded()
        render()
    }

    private fun startServiceIfNeeded() {
        if (!Prefs(this).handEnabled) return
        if (HandScrollService.isRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            startForegroundService(Intent(this, HandScrollService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun render() {
        val enabled = Prefs(this).handEnabled
        val state = HandScrollService.state
        val now = SystemClock.elapsedRealtime()

        if (HandScrollService.swipes > lastSwipes) {
            if (lastSwipes >= 0) triggeredUntil = now + 3500L
            lastSwipes = HandScrollService.swipes
        }

        btnStart.visibility = if (enabled) TextView.GONE else TextView.VISIBLE

        when {
            !enabled -> {
                tvVerdict.setText(R.string.hand_test_status_off)
                tvVerdictSub.setText(R.string.hand_test_hint)
            }
            now < triggeredUntil -> {
                tvVerdict.setText(R.string.hand_test_status_triggered)
                tvVerdictSub.setText(R.string.hand_test_status_triggered_sub)
            }
            state == HandScrollService.STATE_NO_PERM -> {
                tvVerdict.setText(R.string.hand_test_status_no_perm)
                tvVerdictSub.setText(R.string.hand_test_hint)
            }
            state == HandScrollService.STATE_ERROR -> {
                tvVerdict.setText(R.string.hand_test_status_error)
                tvVerdictSub.text = HandScrollService.lastError
                    ?: getString(R.string.hand_test_error_unknown)
            }
            state == HandScrollService.STATE_HAND -> {
                tvVerdict.setText(R.string.hand_test_status_hand)
                tvVerdictSub.setText(R.string.hand_test_status_hand_sub)
            }
            state == HandScrollService.STATE_ACTIVE -> {
                if (HandScrollService.framesReceived == 0L && HandScrollService.cameraOpen) {
                    tvVerdict.setText(R.string.hand_test_status_no_frames)
                    tvVerdictSub.setText(R.string.hand_test_status_no_frames_sub)
                } else {
                    tvVerdict.setText(R.string.hand_test_status_active)
                    tvVerdictSub.setText(R.string.hand_test_status_active_sub)
                }
            }
            else -> {
                tvVerdict.setText(R.string.hand_test_status_waiting)
                tvVerdictSub.setText(R.string.hand_test_status_waiting_sub)
            }
        }

        val statusText = when {
            !enabled -> getString(R.string.hand_status_off)
            state == HandScrollService.STATE_HAND -> getString(R.string.hand_status_hand)
            state == HandScrollService.STATE_ACTIVE -> getString(R.string.hand_status_active)
            state == HandScrollService.STATE_ERROR -> getString(R.string.hand_status_error)
            state == HandScrollService.STATE_NO_PERM -> getString(R.string.hand_status_no_perm)
            else -> getString(R.string.hand_status_waiting)
        }
        val cameraText = if (HandScrollService.cameraOpen) {
            "${getString(R.string.hand_test_camera_on)} ${HandScrollService.previewLabel}"
        } else {
            getString(R.string.hand_test_camera_off)
        }
        val palmText = if (HandScrollService.palmY < 0f) {
            "-"
        } else {
            String.format(Locale.US, "%.2f (v=%.2f)", HandScrollService.palmY, HandScrollService.palmVel)
        }

        tvStats.text = buildString {
            line(R.string.hand_test_row_status, statusText)
            line(R.string.hand_test_row_camera, cameraText)
            line(
                R.string.hand_test_row_frames,
                "${HandScrollService.framesReceived} / ${HandScrollService.framesAnalyzed}"
            )
            line(R.string.hand_test_row_hand, HandScrollService.handFrames.toString())
            line(R.string.hand_test_row_palm, palmText)
            line(R.string.hand_test_row_swipes, HandScrollService.swipes.toString())
            line(R.string.hand_test_row_error, HandScrollService.lastError ?: "-")
        }
    }

    private fun StringBuilder.line(labelRes: Int, value: String) {
        val label = getString(labelRes)
        append(label.padEnd(18))
        append(": ")
        append(value)
        append('\n')
    }
}
