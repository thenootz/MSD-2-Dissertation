package com.pavlova.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Extracts text from screen captures using Google ML Kit on-device OCR.
 * This is the entry point for the content understanding pipeline:
 *   Frame → OCR → NLP Classification → ContentAnalysis
 */
object TextExtractor {
    private const val TAG = "TextExtractor"

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Build a Bitmap from a raw RGBA byte array frame.
     */
    fun bitmapFromRgba(imageData: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageData))
        return bitmap
    }

    /**
     * Extract text from a Bitmap (full concatenated text, no positions).
     * Convenience wrapper around [extractStructured] for callers that only
     * need the flat text.
     */
    suspend fun extractText(bitmap: Bitmap): String =
        extractStructured(bitmap).fullText

    /**
     * Extract text from a Bitmap **with per-line bounding boxes**, normalized
     * to the bitmap dimensions. Used by [CreatorDetector] to filter to the
     * bottom of the screen where the creator name lives on TikTok / Instagram
     * Reels / YouTube Shorts.
     */
    suspend fun extractStructured(bitmap: Bitmap): StructuredOcr =
        suspendCancellableCoroutine { cont ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val blocks = ArrayList<OcrBlock>(result.textBlocks.size * 2)
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val r = line.boundingBox ?: block.boundingBox ?: continue
                            val cy = (r.top + r.bottom) / 2f / h
                            val cx = (r.left + r.right) / 2f / w
                            val lineHeight = (r.bottom - r.top) / h
                            blocks.add(OcrBlock(
                                text = line.text,
                                cx = cx.coerceIn(0f, 1f),
                                cy = cy.coerceIn(0f, 1f),
                                heightFrac = lineHeight.coerceIn(0f, 1f)
                            ))
                        }
                    }
                    Log.d(TAG, "OCR: ${result.text.length} chars, ${result.textBlocks.size} blocks, ${blocks.size} lines")
                    cont.resume(StructuredOcr(result.text, blocks))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    cont.resume(StructuredOcr("", emptyList()))
                }
        }

    fun close() {
        recognizer.close()
    }
}

/**
 * One OCR-detected line and where it sits on the captured frame. Coordinates
 * are normalized to `[0, 1]` so they're independent of device resolution.
 */
data class OcrBlock(
    val text: String,
    /** Normalized horizontal center of the line (0 = left edge, 1 = right edge). */
    val cx: Float,
    /** Normalized vertical center of the line (0 = top, 1 = bottom). */
    val cy: Float,
    /** Line height as a fraction of frame height. */
    val heightFrac: Float
)

/** Structured OCR result: full concatenated text plus per-line bounding boxes. */
data class StructuredOcr(
    val fullText: String,
    val blocks: List<OcrBlock>
)

