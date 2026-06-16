package com.pavlova.ml

/**
 * Identifies the creator of the currently-playing feed item from a stream of
 * OCR snapshots.
 *
 * The problem: on TikTok / Instagram Reels / YouTube Shorts the creator name
 * usually appears as plain text near the lower-left of the screen
 * (e.g. *"officialbrand"*, *"jane.doe"*) — only mentions inside captions
 * carry an explicit `@` prefix, so a naïve `@(\w+)` regex misses almost all
 * real creators.
 *
 * The signal that **does** work cross-platform: while the user is watching a
 * single video, the bottom of the screen (the creator label and the music
 * row immediately below it) stays nearly identical across consecutive
 * captured frames, while the rest of the screen (caption, engagement
 * counts, gestures) churns. So we:
 *
 *   1. On every frame, snapshot the cleaned, plausible candidate strings
 *      sitting in the bottom band.
 *   2. Detect **video-segment boundaries** by comparing the current frame's
 *      candidate set to the previous frame's. When overlap drops below
 *      [segmentBoundaryJaccard] we treat it as a scroll to a new video and
 *      start a fresh segment (history is cleared).
 *   3. Within a segment, score `(text, vertical band)` by sighting count.
 *      The first candidate that's seen in at least [minStableFrames] frames
 *      wins and stays locked for the rest of the segment, so [submit]
 *      keeps returning the same handle on every subsequent frame.
 *   4. [submit] returns a [Result] with both the resolved creator and the
 *      current `segmentId`, so the caller can back-fill earlier
 *      `ContentItem`s belonging to the same video.
 *
 * State is per-instance; [FeedAnalyzer] creates a fresh detector per
 * session and calls [reset] when ending the session.
 */
class CreatorDetector(
    private val historyDepth: Int = 8,
    private val minStableFrames: Int = 2,
    /** Vertical fraction below which we look for creator names. 0.55 = bottom 45 %. */
    private val bottomBandStart: Float = 0.55f,
    /** Skip the very bottom edge (system nav / app bottom-bar). */
    private val bottomBandEnd: Float = 0.97f,
    /**
     * Jaccard similarity threshold between two consecutive frames' candidate
     * bag. Below this we declare a new video segment and reset state. The
     * default is permissive enough that one captured frame mid-video doesn't
     * accidentally start a new segment just because the caption changed.
     */
    private val segmentBoundaryJaccard: Float = 0.34f
) {

    /** Returned from [submit]. `creator` is null while the detector is still
     *  gathering evidence for the current segment. `segmentId` increments
     *  every time a video boundary is detected; callers can use it to know
     *  which previously-stored items should share this creator. */
    data class Result(val segmentId: Long, val creator: String?)

    private data class Sighting(val key: String, val exemplar: String, val band: Int, val hadAt: Boolean)

    private val history = ArrayDeque<List<Sighting>>()
    private var previousKeys: Set<String> = emptySet()
    private var segmentId: Long = 0L
    private var lockedCreator: String? = null

    fun submit(blocks: List<OcrBlock>): Result {
        // 1. Build this frame's candidates from the bottom band.
        val frame = blocks
            .filter { it.cy in bottomBandStart..bottomBandEnd }
            .mapNotNull { block ->
                val (cleaned, hadAt) = cleanText(block.text) ?: return@mapNotNull null
                if (!isPlausibleHandle(cleaned)) return@mapNotNull null
                Sighting(
                    key = cleaned.lowercase(),
                    exemplar = cleaned,
                    band = bandOf(block.cy),
                    hadAt = hadAt
                )
            }
            .distinctBy { it.key to it.band }

        val keysNow = frame.mapTo(HashSet()) { it.key }

        // 2. Detect a new-video boundary by comparing candidate bags.
        if (previousKeys.isNotEmpty() && keysNow.isNotEmpty()) {
            val sim = jaccard(previousKeys, keysNow)
            if (sim < segmentBoundaryJaccard) {
                segmentId += 1
                history.clear()
                lockedCreator = null
            }
        } else if (previousKeys.isNotEmpty() && keysNow.isEmpty()) {
            // A frame with no candidate text in the bottom band (transition,
            // black frame) doesn't change the segment, just skips evidence.
        }
        previousKeys = keysNow

        history.addLast(frame)
        while (history.size > historyDepth) history.removeFirst()

        // 3. Once locked for this segment, stay locked.
        lockedCreator?.let { return Result(segmentId, it) }

        if (history.size < minStableFrames) return Result(segmentId, null)

        // 4. Score (text → hits, @-prefix count, band, exemplar) inside this segment only.
        data class Score(val hits: Int, val band: Int, val withAt: Int, val exemplar: String)
        val scores = HashMap<String, Score>()
        for (f in history) {
            for (s in f) {
                val prev = scores[s.key]
                scores[s.key] = Score(
                    hits = (prev?.hits ?: 0) + 1,
                    band = maxOf(prev?.band ?: 0, s.band),
                    withAt = (prev?.withAt ?: 0) + if (s.hadAt) 1 else 0,
                    exemplar = prev?.exemplar ?: s.exemplar
                )
            }
        }

        val best = scores.entries
            .filter { it.value.hits >= minStableFrames }
            .maxWithOrNull(
                compareBy(
                    { it.value.hits },
                    { it.value.withAt },
                    { it.value.band }
                )
            ) ?: return Result(segmentId, null)

        lockedCreator = best.value.exemplar
        return Result(segmentId, lockedCreator)
    }

    /** Reset all state. Call when a new audit session starts. */
    fun reset() {
        history.clear()
        previousKeys = emptySet()
        segmentId = 0L
        lockedCreator = null
    }

    // --- Heuristics ----------------------------------------------------

    private fun cleanText(raw: String): Pair<String, Boolean>? {
        val stripped = raw
            .replace(MUSIC_GLYPHS, "")
            .replace(VERIFIED_GLYPHS, "")
            .trim()
            .trim('·', '•', '·', '-', '|', ':', ',', '.', ' ')
            .trim()
        if (stripped.isEmpty()) return null
        val hadAt = stripped.startsWith("@")
        val withoutAt = if (hadAt) stripped.removePrefix("@").trim() else stripped
        if (withoutAt.isEmpty()) return null
        return withoutAt to hadAt
    }

    private fun isPlausibleHandle(text: String): Boolean {
        if (text.length < 2 || text.length > 40) return false
        val lower = text.lowercase()
        if (text.none { it.isLetter() }) return false
        if (METRIC_PATTERN.matches(text)) return false
        if (BANNED_LITERALS.contains(lower)) return false
        if (BANNED_PREFIXES.any { lower.startsWith(it) }) return false
        if (BANNED_CONTAINS.any { lower.contains(it) }) return false
        if (text.count { it.isLetter() } < 2) return false
        if (text.split(WHITESPACE).size > 4) return false
        return true
    }

    private fun bandOf(cy: Float): Int = (cy * BAND_RESOLUTION).toInt()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        var intersection = 0
        for (k in a) if (k in b) intersection++
        val union = a.size + b.size - intersection
        return if (union == 0) 0f else intersection / union.toFloat()
    }

    companion object {
        private const val BAND_RESOLUTION = 20

        private val METRIC_PATTERN = Regex("^[\\d.,]+\\s?[KMB]?$", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")
        private val MUSIC_GLYPHS = Regex("[\\u266A\\u266B\\u266C\\u266D\\u266E\\u266F\\u2669\\u25B6\\u25C0\\uD83C\\uDFB5\\uD83C\\uDFB6\\uD83C\\uDFA7\\uD83D\\uDD0A]")
        private val VERIFIED_GLYPHS = Regex("[\\u2713\\u2714\\u2611\\uD83D\\uDD11]")

        private val BANNED_LITERALS = setOf(
            "discovery", "discover", "inbox", "notifications", "activity",
            "following", "follow", "follower", "followers",
            "for you", "foryou", "for you page", "fyp",
            "explore", "reels", "shorts", "home", "search",
            "profile", "live", "now playing", "trending",
            "friends", "messages", "create",
            "like", "likes", "share", "comment", "comments", "subscribe",
            "save", "send", "add comment", "add a comment", "post",
            "tap to skip", "play", "pause", "mute", "unmute",
            "sound", "original sound", "use sound", "use this sound",
            "music", "song",
            "see translation", "see more", "show more", "tap for more",
            "swipe up", "swipe down", "view all",
            "ad", "sponsored", "promoted"
        )
        private val BANNED_PREFIXES = setOf(
            "original sound", "send a message", "add a comment",
            "watch later", "you may like", "suggested for you",
            "share to", "share with", "shop now", "learn more"
        )
        private val BANNED_CONTAINS = setOf(
            "tap to ", "swipe to ", "view profile", "view all comments",
            "go live", "see all", "buy now"
        )
    }
}
