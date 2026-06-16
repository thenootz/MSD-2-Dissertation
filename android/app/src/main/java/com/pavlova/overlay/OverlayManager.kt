package com.pavlova.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ImageView? = null
    private var isOverlayVisible = false

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    /**
     * Show overlay with blurred/pixelated content
     */
    suspend fun showOverlay(blurredBitmap: Bitmap) = withContext(Dispatchers.Main) {
        try {
            if (overlayView == null) {
                overlayView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                }
                windowManager.addView(overlayView, layoutParams)
                isOverlayVisible = true
                Log.d(TAG, "Overlay view created and added")
            }
            
            overlayView?.setImageBitmap(blurredBitmap)
            overlayView?.visibility = View.VISIBLE
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    /**
     * Hide the overlay (content is safe)
     */
    suspend fun hideOverlay() = withContext(Dispatchers.Main) {
        try {
            overlayView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
    }

    /**
     * Update overlay with new blurred content
     */
    suspend fun updateOverlay(blurredBitmap: Bitmap) {
        if (isOverlayVisible) {
            showOverlay(blurredBitmap)
        }
    }

    /**
     * Remove overlay completely
     */
    fun removeOverlay() {
        try {
            overlayView?.let {
                if (isOverlayVisible) {
                    windowManager.removeView(it)
                    isOverlayVisible = false
                }
            }
            overlayView = null
            Log.d(TAG, "Overlay removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay", e)
        }
    }

    /**
     * Check if overlay is currently visible
     */
    fun isVisible(): Boolean = isOverlayVisible && overlayView?.visibility == View.VISIBLE

    /**
     * Cleanup resources
     */
    fun cleanup() {
        removeOverlay()
    }
}
