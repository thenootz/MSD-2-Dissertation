package com.pavlova.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.os.SystemClock

/**
 * Lightweight cross-component bridge between [PavlovaAccessibilityService] and
 * the capture pipeline ([com.pavlova.ml.FeedAnalyzer]).
 *
 * The accessibility service runs in its own lifecycle and can't be passed
 * directly to the analyzer, so it publishes "a scroll just happened" timestamps
 * here. `FeedAnalyzer` polls [millisSinceLastScroll] on each frame to fuse this
 * real input signal with its visual [com.pavlova.ml.VideoSegmenter] guess.
 *
 * Uses [SystemClock.elapsedRealtime] (monotonic) so it's immune to wall-clock
 * changes.
 */
object ScrollSignal {

    @Volatile private var lastScrollAt: Long = 0L

    /** True while the accessibility service is connected and delivering events. */
    @Volatile var accessibilityConnected: Boolean = false

    /** Called by the accessibility service on each scroll event. */
    fun recordScroll() {
        lastScrollAt = SystemClock.elapsedRealtime()
    }

    /** Milliseconds since the last observed scroll, or [Long.MAX_VALUE] if none. */
    fun millisSinceLastScroll(): Long {
        val t = lastScrollAt
        return if (t == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - t
    }

    /** Whether scroll detection is actually usable right now. */
    fun isActive(): Boolean = accessibilityConnected

    /**
     * Whether the user has enabled [PavlovaAccessibilityService] in system
     * Accessibility settings. This is the authoritative source of truth for the
     * Settings UI: unlike [isActive], it does not depend on the service instance
     * already being (re)connected in the current process, so it stays correct
     * across the process restart that often happens when the user returns from
     * the system Accessibility screen.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, PavlovaAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            val parsed = ComponentName.unflattenFromString(component)
            if (parsed != null && parsed == expected) return true
        }
        return false
    }
}
