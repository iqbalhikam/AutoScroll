package id.iqbal.tiktokauto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

class BubbleView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private var state = STATE_OFF
    private var pct = -1f
    private var pressed = false
    private var longPressed = false

    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0141720")
        setShadowLayer(dp(6f), 0f, dp(2f), Color.parseColor("#80000000"))
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#33FFFFFF")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPath = Path()
    private val arcRect = RectF()

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = context.getString(R.string.bubble_desc)
        isClickable = true
    }

    fun set(state: Int, pct: Float) {
        if (this.state == state && abs(this.pct - pct) < 0.5f) return
        this.state = state
        this.pct = pct
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(5f)
        val color = when (state) {
            STATE_ACTIVE -> Color.parseColor("#4ADE80")
            STATE_SCAN -> Color.parseColor("#38BDF8")
            STATE_IDLE -> Color.parseColor("#FBBF24")
            else -> Color.parseColor("#6B7280")
        }

        bgPaint.color = Color.parseColor(if (pressed) "#F01B2230" else "#F0141720")
        canvas.drawCircle(cx, cy, radius, bgPaint)

        arcRect.set(
            cx - radius + dp(8f),
            cy - radius + dp(8f),
            cx + radius - dp(8f),
            cy + radius - dp(8f)
        )
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
        if (pct >= 0f && state != STATE_OFF) {
            arcPaint.color = color
            val sweep = 360f * (pct.coerceIn(0f, 100f) / 100f)
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
        }

        glyphPaint.color = color
        glyphPath.reset()
        val s = dp(4.5f)
        glyphPath.moveTo(cx - s, cy + s * 0.45f)
        glyphPath.lineTo(cx, cy - s * 0.55f)
        glyphPath.lineTo(cx + s, cy + s * 0.45f)
        canvas.drawPath(glyphPath, glyphPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                longPressed = false
                pressed = true
                invalidate()
                main.postDelayed(longPressRunnable, 520L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPressRunnable)
                    }
                }
                if (dragging) {
                    onMove?.invoke(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                pressed = false
                invalidate()
                if (dragging) {
                    onDragEnd?.invoke()
                } else if (!longPressed) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressRunnable)
                pressed = false
                if (dragging) onDragEnd?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val STATE_OFF = 0
        const val STATE_IDLE = 1
        const val STATE_SCAN = 2
        const val STATE_ACTIVE = 3
    }
}
