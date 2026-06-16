package com.pavlova.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Draws a small, non-interactive alert banner on top of every app (a
 * "heads-up" warning) while an audit session is running.
 *
 * It is used by [com.pavlova.ml.FeedAnalyzer] to surface threshold-crossing
 * warnings — high toxicity, strong feed-shaping, or feed isolation/echo
 * chamber — directly over the social-media app the user is scrolling.
 *
 * Requires the `SYSTEM_ALERT_WINDOW` (draw-over-other-apps) permission;
 * callers should check [canDrawOverlays] first. The banner is
 * `FLAG_NOT_TOUCHABLE`, so it never steals input from the underlying app and
 * auto-dismisses after [AUTO_DISMISS_MS].
 */
class OverlayManager(context: Context) {

    /** Severity of an alert — drives the banner colour. */
    enum class Level { INFO, WARNING, CRITICAL }

    companion object {
        private const val TAG = "OverlayManager"
        private const val AUTO_DISMISS_MS = 6_000L
    }

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bannerView: LinearLayout? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var added = false
    private var dismissJob: Job? = null

    /** Whether we currently hold the draw-over-other-apps permission. */
    fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else true

    /**
     * Show (or update) the alert banner with [title] + [body]. No-op if the
     * overlay permission is missing. Auto-dismisses after a few seconds.
     */
    fun showAlert(title: String, body: String, level: Level = Level.WARNING) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission not granted — skipping alert: $title")
            return
        }
        main.launch {
            try {
                ensureBanner()
                titleView?.text = title
                bodyView?.text = body
                bannerView?.background = bannerBackground(level)
                bannerView?.visibility = View.VISIBLE

                dismissJob?.cancel()
                dismissJob = main.launch {
                    delay(AUTO_DISMISS_MS)
                    bannerView?.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show alert overlay", e)
            }
        }
    }

    /** Hide the banner immediately (kept attached for re-use). */
    fun hide() {
        main.launch {
            dismissJob?.cancel()
            bannerView?.visibility = View.GONE
        }
    }

    /** Detach and release the overlay window. Call on session end / destroy. */
    fun cleanup() {
        main.launch {
            try {
                dismissJob?.cancel()
                bannerView?.let { if (added) windowManager.removeView(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay", e)
            } finally {
                added = false
                bannerView = null
                titleView = null
                bodyView = null
            }
        }
    }

    // --- view construction --------------------------------------------

    private fun ensureBanner() {
        if (bannerView != null && added) return

        val title = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val body = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            background = bannerBackground(Level.WARNING)
            addView(title)
            addView(body)
        }

        windowManager.addView(container, layoutParams())
        bannerView = container
        titleView = title
        bodyView = body
        added = true
        Log.d(TAG, "Alert banner attached")
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
    }

    private fun bannerBackground(level: Level): GradientDrawable {
        val color = when (level) {
            Level.INFO -> Color.parseColor("#CC2E7D32")      // green
            Level.WARNING -> Color.parseColor("#CCE65100")   // amber
            Level.CRITICAL -> Color.parseColor("#CCB71C1C")  // red
        }
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
