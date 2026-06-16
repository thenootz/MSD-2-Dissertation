package com.pavlova.debug

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.pavlova.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists screen captures and their OCR text to disk for debugging.
 *
 * For each captured frame we write two files in [getExternalFilesDir]/debug_captures:
 *   - <timestamp>.jpg : downscaled JPEG of the captured bitmap
 *   - <timestamp>.txt : the OCR-extracted text (UTF-8)
 *
 * Toggleable at runtime via [setEnabled]. Defaults to `true` for debug builds,
 * `false` for release builds. Keeps only the most recent [MAX_ENTRIES] captures
 * to avoid filling storage indefinitely.
 */
object DebugCaptureStore {
    private const val TAG = "DebugCaptureStore"
    private const val DIR_NAME = "debug_captures"
    private const val PREFS_NAME = "pavlova_debug_prefs"
    private const val KEY_ENABLED = "debug_capture_enabled"
    private const val MAX_ENTRIES = 100
    private const val JPEG_QUALITY = 75
    private const val MAX_DIMENSION = 1080

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    @Volatile private var appContext: Context? = null
    @Volatile private var enabled: Boolean = BuildConfig.DEBUG

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs()
        enabled = prefs?.getBoolean(KEY_ENABLED, BuildConfig.DEBUG) ?: BuildConfig.DEBUG
        Log.d(TAG, "Initialized. enabled=$enabled, dir=${getDir()?.absolutePath}")
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        Log.d(TAG, "Debug capture enabled=$value")
    }

    /**
     * Save a bitmap and its OCR text. No-op if disabled or not initialized.
     */
    fun save(bitmap: Bitmap, ocrText: String) {
        if (!enabled) return
        val dir = getDir() ?: return
        try {
            val stamp = timestampFormat.format(Date())
            val imageFile = File(dir, "$stamp.jpg")
            val textFile = File(dir, "$stamp.txt")

            val scaled = downscale(bitmap)
            FileOutputStream(imageFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()

            textFile.writeText(ocrText, Charsets.UTF_8)

            pruneOldEntries(dir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug capture", e)
        }
    }

    /**
     * Returns captures sorted newest-first. Image and text paths come from
     * matching `<timestamp>.jpg` / `<timestamp>.txt` pairs.
     */
    fun listCaptures(): List<DebugCapture> {
        val dir = getDir() ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        val images = files.filter { it.extension == "jpg" }
        return images
            .sortedByDescending { it.lastModified() }
            .map { image ->
                val text = File(dir, "${image.nameWithoutExtension}.txt")
                DebugCapture(
                    name = image.nameWithoutExtension,
                    imagePath = image.absolutePath,
                    textPath = text.absolutePath.takeIf { text.exists() },
                    sizeBytes = image.length(),
                    timestamp = image.lastModified()
                )
            }
    }

    fun readText(textPath: String?): String {
        if (textPath == null) return ""
        return try {
            File(textPath).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read text $textPath", e)
            ""
        }
    }

    fun clearAll() {
        val dir = getDir() ?: return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        Log.d(TAG, "Cleared all debug captures")
    }

    fun getDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun downscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val maxSide = maxOf(w, h)
        if (maxSide <= MAX_DIMENSION) return src
        val scale = MAX_DIMENSION.toFloat() / maxSide
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun pruneOldEntries(dir: File) {
        val images = dir.listFiles { f -> f.extension == "jpg" } ?: return
        if (images.size <= MAX_ENTRIES) return
        val sorted = images.sortedByDescending { it.lastModified() }
        sorted.drop(MAX_ENTRIES).forEach { old ->
            runCatching { old.delete() }
            runCatching { File(dir, "${old.nameWithoutExtension}.txt").delete() }
        }
    }
}

data class DebugCapture(
    val name: String,
    val imagePath: String,
    val textPath: String?,
    val sizeBytes: Long,
    val timestamp: Long
)
