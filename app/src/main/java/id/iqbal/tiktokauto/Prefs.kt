package id.iqbal.tiktokauto

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autoscroll", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_PROGRESS) ?: MODE_PROGRESS
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    var threshold: Int
        get() = sp.getInt(KEY_THRESHOLD, 97)
        set(value) = sp.edit().putInt(KEY_THRESHOLD, value).apply()

    var postScrollDelay: Int
        get() = sp.getInt(KEY_DELAY, 1200)
        set(value) = sp.edit().putInt(KEY_DELAY, value).apply()

    var swipeDuration: Int
        get() = sp.getInt(KEY_DURATION, 220)
        set(value) = sp.edit().putInt(KEY_DURATION, value).apply()

    var swipeDistance: Int
        get() = sp.getInt(KEY_DISTANCE, 38)
        set(value) = sp.edit().putInt(KEY_DISTANCE, value).apply()

    var swipeStart: Int
        get() = sp.getInt(KEY_START, 72)
        set(value) = sp.edit().putInt(KEY_START, value).apply()

    var timerInterval: Int
        get() = sp.getInt(KEY_INTERVAL, 15000)
        set(value) = sp.edit().putInt(KEY_INTERVAL, value).apply()

    var onlyTikTok: Boolean
        get() = sp.getBoolean(KEY_ONLY_TIKTOK, true)
        set(value) = sp.edit().putBoolean(KEY_ONLY_TIKTOK, value).apply()

    var bubble: Boolean
        get() = sp.getBoolean(KEY_BUBBLE, true)
        set(value) = sp.edit().putBoolean(KEY_BUBBLE, value).apply()

    var vibrate: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) = sp.edit().putBoolean(KEY_VIBRATE, value).apply()

    var skipAds: Boolean
        get() = sp.getBoolean(KEY_SKIP_ADS, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_ADS, value).apply()

    var autoFallback: Boolean
        get() = sp.getBoolean(KEY_AUTO_FALLBACK, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_FALLBACK, value).apply()

    var blinkEnabled: Boolean
        get() = sp.getBoolean(KEY_BLINK, false)
        set(value) = sp.edit().putBoolean(KEY_BLINK, value).apply()

    var uiOpen: Boolean
        get() = sp.getBoolean(KEY_UI_OPEN, true)
        set(value) = sp.edit().putBoolean(KEY_UI_OPEN, value).apply()

    var updateUrl: String
        get() = sp.getString(KEY_UPDATE_URL, "").orEmpty().ifBlank { BuildConfig.UPDATE_URL }
        set(value) = sp.edit().putString(KEY_UPDATE_URL, value).apply()

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK, value).apply()

    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_Y, value).apply()

    fun addScroll() {
        rolloverDay()
        sp.edit().putInt(KEY_COUNT, sp.getInt(KEY_COUNT, 0) + 1).apply()
    }

    fun scrollCount(): Int {
        rolloverDay()
        return sp.getInt(KEY_COUNT, 0)
    }

    fun resetCount() {
        sp.edit().putInt(KEY_COUNT, 0).putString(KEY_COUNT_DATE, today()).apply()
    }

    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun rolloverDay() {
        if (sp.getString(KEY_COUNT_DATE, "") != today()) {
            sp.edit().putInt(KEY_COUNT, 0).putString(KEY_COUNT_DATE, today()).apply()
        }
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    companion object {
        const val MODE_PROGRESS = "progress"
        const val MODE_HYBRID = "hybrid"
        const val MODE_TIMER = "timer"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DELAY = "post_scroll_delay"
        private const val KEY_DURATION = "swipe_duration"
        private const val KEY_DISTANCE = "swipe_distance"
        private const val KEY_START = "swipe_start"
        private const val KEY_INTERVAL = "timer_interval"
        private const val KEY_ONLY_TIKTOK = "only_tiktok"
        private const val KEY_BUBBLE = "bubble"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SKIP_ADS = "skip_ads"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"
        private const val KEY_BLINK = "blink_enabled"
        private const val KEY_UI_OPEN = "ui_open"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_COUNT = "scroll_count"
        private const val KEY_COUNT_DATE = "scroll_count_date"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_AUTO_CHECK = "auto_check_updates"
        private const val KEY_LAST_CHECK = "last_update_check"
    }
}
