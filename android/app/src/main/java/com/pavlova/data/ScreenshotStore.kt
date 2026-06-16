package com.pavlova.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Persists a small downscaled JPEG thumbnail per captured [com.pavlova.data.model.ContentItem]
 * so the session detail screen can show what was actually on screen.
 *
 * Files are written to app-private storage (no permissions required) at
 * `<filesDir>/captures/<sessionId>_<itemId>.jpg`. Total stored thumbnails are
 * capped at [MAX_TOTAL] — oldest files are pruned first.
 */
object ScreenshotStore {
    private const val TAG = "ScreenshotStore"
    private const val DIR_NAME = "captures"
    private const val MAX_DIMENSION = 480
    private const val JPEG_QUALITY = 70
    private const val MAX_TOTAL = 2000

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Downscale and save [bitmap] as a JPEG thumbnail for the given session/item.
     * Returns the absolute file path, or null if saving failed.
     */
    fun save(sessionId: String, itemId: Long, bitmap: Bitmap): String? {
        val dir = getDir() ?: return null
        return try {
            val file = File(dir, "${sessionId}_$itemId.jpg")
            val scaled = downscale(bitmap)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            pruneOldEntries(dir)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot for $sessionId/$itemId", e)
            null
        }
    }

    private fun getDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Delete every stored thumbnail. Used when verbose/demo mode is toggled OFF. */
    fun clearAll(): Int {
        val dir = getDir() ?: return 0
        val files = dir.listFiles { f -> f.extension == "jpg" } ?: return 0
        var deleted = 0
        for (f in files) {
            if (runCatching { f.delete() }.getOrDefault(false)) deleted++
        }
        Log.d(TAG, "Cleared $deleted screenshot thumbnails")
        return deleted
    }

    private fun downscale(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= MAX_DIMENSION) return src
        val scale = MAX_DIMENSION.toFloat() / maxSide
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun pruneOldEntries(dir: File) {
        val files = dir.listFiles { f -> f.extension == "jpg" } ?: return
        if (files.size <= MAX_TOTAL) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_TOTAL)
            .forEach { runCatching { it.delete() } }
    }
}
