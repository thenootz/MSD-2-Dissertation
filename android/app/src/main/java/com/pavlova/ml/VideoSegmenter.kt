package com.pavlova.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects video boundaries within a feed session by fusing the **visual effect
 * of the user's input** with an optional real scroll signal.
 *
 * MediaProjection doesn't expose raw touch events, but their result is visible:
 *
 *  - A **scroll** to the next short video translates the whole screen — a large
 *    inter-frame difference *and* a coherent vertical row-shift.
 *  - A **tap** (play/pause), a like, or a comment barely changes the frame.
 *  - In-video motion (the clip playing, scene cuts) changes the *centre* of the
 *    screen but the bottom row — creator handle + music label — stays put, and
 *    is not a clean vertical translation.
 *
 * A new video is declared when a **scroll-like** frame coincides with a
 * **bottom-band text change** (+ a short cooldown). A frame is scroll-like when
 * any of:
 *   1. an `externalScrollHint` is set (the accessibility scroll detector saw a
 *      real swipe), or
 *   2. the full-frame difference exceeds [frameDiffThreshold], or
 *   3. a coherent vertical translation of ≥ [minVerticalShift] rows is detected
 *      (directional scroll, distinct from in-video motion).
 *
 * The bottom-band change rejects in-video scene cuts where the creator label
 * persists; the [cooldownFrames] refractory period prevents a single
 * multi-frame scroll (or a fling firing many accessibility events) from being
 * counted as several boundaries.
 */
class VideoSegmenter(
    private val frameDiffThreshold: Float = 0.18f,
    private val bottomJaccardThreshold: Float = 0.5f,
    private val cooldownFrames: Int = 1,
    /** Minimum vertical row-shift (in grid rows) to count as a directional scroll. */
    private val minVerticalShift: Int = 2,
    /** Row-profile error must improve by at least this ratio at the best shift. */
    private val shiftImprovement: Float = 0.30f
) {
    private var prevSignature: IntArray? = null
    private var prevRowProfile: IntArray? = null
    private var prevBottomKeys: Set<String> = emptySet()
    private var framesSinceBoundary = Int.MAX_VALUE
    private var started = false

    /** Normalised full-frame difference (0..1) computed on the last [update]. */
    var lastFrameDiff: Float = 0f
        private set

    /** Best vertical row-shift detected on the last [update] (signed; rows). */
    var lastVerticalShift: Int = 0
        private set

    /**
     * Feed the latest frame. Returns `true` when this frame begins a new video
     * (always `true` for the very first frame of a session).
     *
     * @param signature   downsampled luminance grid from [signatureFromRgba]
     * @param bottomKeys   lowercased text lines currently in the bottom band
     * @param externalScrollHint  true when an out-of-band signal (e.g. the
     *        accessibility scroll detector) recently observed a real scroll;
     *        lets a gentle swipe that misses the visual magnitude threshold
     *        still register, while the bottom-band guard + cooldown prevent a
     *        single fling from creating multiple boundaries.
     */
    fun update(
        signature: IntArray,
        bottomKeys: Set<String>,
        externalScrollHint: Boolean = false
    ): Boolean {
        framesSinceBoundary++
        val rowProfile = rowProfile(signature)

        val prev = prevSignature
        val prevProfile = prevRowProfile
        if (!started || prev == null || prevProfile == null) {
            started = true
            prevSignature = signature
            prevRowProfile = rowProfile
            if (bottomKeys.isNotEmpty()) prevBottomKeys = bottomKeys
            framesSinceBoundary = 0
            lastFrameDiff = 0f
            lastVerticalShift = 0
            return true // first video of the session
        }

        val diff = normalizedMad(prev, signature)
        lastFrameDiff = diff
        val verticalShift = bestVerticalShift(prevProfile, rowProfile)
        lastVerticalShift = verticalShift
        prevSignature = signature
        prevRowProfile = rowProfile

        // Bottom-band change: if we have no bottom text this frame we can't use
        // it to confirm/deny, so don't let it block a clear scroll.
        val bottomChanged = bottomKeys.isEmpty() ||
            prevBottomKeys.isEmpty() ||
            jaccard(prevBottomKeys, bottomKeys) < bottomJaccardThreshold
        if (bottomKeys.isNotEmpty()) prevBottomKeys = bottomKeys

        // A "scroll-like" frame: a real accessibility scroll, OR a big overall
        // change, OR a clear directional vertical translation of content.
        val scrollLike = externalScrollHint ||
            diff >= frameDiffThreshold ||
            kotlin.math.abs(verticalShift) >= minVerticalShift

        val isBoundary = scrollLike &&
            bottomChanged &&
            framesSinceBoundary > cooldownFrames

        if (isBoundary) framesSinceBoundary = 0
        return isBoundary
    }

    fun reset() {
        prevSignature = null
        prevRowProfile = null
        prevBottomKeys = emptySet()
        framesSinceBoundary = Int.MAX_VALUE
        started = false
        lastFrameDiff = 0f
        lastVerticalShift = 0
    }

    /** Per-row average luminance (length GRID), used to detect vertical scroll. */
    private fun rowProfile(signature: IntArray): IntArray {
        val profile = IntArray(GRID)
        for (gy in 0 until GRID) {
            var sum = 0
            for (gx in 0 until GRID) sum += signature[gy * GRID + gx]
            profile[gy] = sum / GRID
        }
        return profile
    }

    /**
     * Find the vertical shift (in rows) that best aligns [prev] onto [cur] by
     * cross-correlating their row profiles. A coherent vertical scroll shows a
     * non-zero best shift whose alignment error is clearly lower than the
     * no-shift error; in-video motion / scene cuts do not. Returns 0 when no
     * confident directional shift is found.
     */
    private fun bestVerticalShift(prev: IntArray, cur: IntArray): Int {
        val zeroErr = shiftError(prev, cur, 0)
        if (zeroErr == 0f) return 0
        var bestShift = 0
        var bestErr = zeroErr
        val maxShift = GRID / 2
        for (s in -maxShift..maxShift) {
            if (s == 0) continue
            val err = shiftError(prev, cur, s)
            if (err < bestErr) { bestErr = err; bestShift = s }
        }
        // Require the best shift to materially beat the no-shift alignment.
        return if (bestShift != 0 && bestErr <= zeroErr * (1f - shiftImprovement)) bestShift else 0
    }

    /** Mean abs difference of overlapping rows when [prev] is shifted by [shift]. */
    private fun shiftError(prev: IntArray, cur: IntArray, shift: Int): Float {
        var sum = 0L
        var count = 0
        for (i in prev.indices) {
            val j = i + shift
            if (j in cur.indices) {
                sum += abs(prev[i] - cur[j])
                count++
            }
        }
        return if (count == 0) Float.MAX_VALUE else sum.toFloat() / count
    }

    private fun normalizedMad(a: IntArray, b: IntArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0L
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return (sum.toFloat() / n) / 255f
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        var intersection = 0
        for (k in a) if (k in b) intersection++
        val union = a.size + b.size - intersection
        return if (union == 0) 0f else intersection / union.toFloat()
    }

    companion object {
        private const val GRID = 16 // 16x16 luminance signature

        /**
         * Build a compact [GRID]×[GRID] grayscale signature from a tightly-packed
         * RGBA frame. Cheap (sub-samples each cell) so it can run per frame.
         */
        fun signatureFromRgba(data: ByteArray, width: Int, height: Int): IntArray {
            val sig = IntArray(GRID * GRID)
            if (width <= 0 || height <= 0) return sig
            val cellW = max(1, width / GRID)
            val cellH = max(1, height / GRID)

            for (gy in 0 until GRID) {
                val y0 = gy * cellH
                val y1 = min(height, y0 + cellH)
                val stepY = max(1, (y1 - y0) / 4)
                for (gx in 0 until GRID) {
                    val x0 = gx * cellW
                    val x1 = min(width, x0 + cellW)
                    val stepX = max(1, (x1 - x0) / 4)

                    var sum = 0L
                    var count = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            val idx = (y * width + x) * 4
                            if (idx + 2 < data.size) {
                                val r = data[idx].toInt() and 0xFF
                                val g = data[idx + 1].toInt() and 0xFF
                                val b = data[idx + 2].toInt() and 0xFF
                                // Rec. 601 luma
                                sum += (r * 30 + g * 59 + b * 11) / 100
                                count++
                            }
                            x += stepX
                        }
                        y += stepY
                    }
                    sig[gy * GRID + gx] = if (count > 0) (sum / count).toInt() else 0
                }
            }
            return sig
        }
    }
}
