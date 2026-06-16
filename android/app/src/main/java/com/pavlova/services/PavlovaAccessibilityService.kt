package com.pavlova.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service that observes **scroll gestures** in other
 * apps (TikTok / Instagram Reels / YouTube Shorts) so the capture pipeline can
 * pinpoint exactly when the user swiped to the next short video.
 *
 * This is the only Android-sanctioned way to observe cross-app input: an
 * overlay window cannot read touches that pass through to the app below it.
 * We deliberately keep this minimal and privacy-preserving:
 *
 *   - We do **not** retrieve window content (`canRetrieveWindowContent=false`
 *     in the config) — only the *type* of event (a scroll happened) is used.
 *   - No text, coordinates, or screen content is read here.
 *   - Entirely opt-in: the user must enable it in Settings → Accessibility,
 *     and capture works fine (visual-only segmentation) without it.
 *
 * Scroll events are published to [ScrollSignal] for [com.pavlova.ml.FeedAnalyzer].
 */
class PavlovaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PavlovaA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScrollSignal.accessibilityConnected = true
        Log.d(TAG, "Accessibility service connected — scroll detection active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            ScrollSignal.recordScroll()
        }
    }

    override fun onInterrupt() {
        // No-op: we hold no transient state to clear.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ScrollSignal.accessibilityConnected = false
        Log.d(TAG, "Accessibility service disconnected")
        return super.onUnbind(intent)
    }
}
