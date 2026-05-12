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
     * Extract text from a raw RGBA byte array frame.
     */
    suspend fun extractText(imageData: ByteArray, width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageData))
        return extractText(bitmap)
    }

    /**
     * Extract text from a Bitmap.
     */
    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val text = result.text
                Log.d(TAG, "OCR extracted ${text.length} chars, ${result.textBlocks.size} blocks")
                cont.resume(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                cont.resume("")
            }
    }

    fun close() {
        recognizer.close()
    }
}
