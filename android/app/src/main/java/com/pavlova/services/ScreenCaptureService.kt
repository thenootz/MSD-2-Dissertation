package com.pavlova.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pavlova.MainActivity
import com.pavlova.R
import com.pavlova.ml.FrameProcessor
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        private const val MAX_IMAGES = 2
        private const val TARGET_FPS = 10
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameProcessor: FrameProcessor? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isCapturing = AtomicBoolean(false)
    
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0
    
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        createNotificationChannel()
        frameProcessor = FrameProcessor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service start command received")
        
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            startCapture(resultCode, resultData)
        } else {
            Log.e(TAG, "Invalid MediaProjection permission")
            stopSelf()
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing")
            return
        }
        
        try {
            // Get display metrics
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = windowManager.defaultDisplay
                display.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }
            
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            displayDensity = metrics.densityDpi
            
            Log.d(TAG, "Display: ${displayWidth}x${displayHeight} @ ${displayDensity}dpi")
            
            // Create MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                displayWidth,
                displayHeight,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            ).apply {
                setOnImageAvailableListener({ reader ->
                    handleNewFrame(reader)
                }, null)
            }
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PavlovaCapture",
                displayWidth,
                displayHeight,
                displayDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            
            isCapturing.set(true)
            Log.d(TAG, "Screen capture started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopSelf()
        }
    }

    private fun handleNewFrame(reader: ImageReader) {
        // Rate limiting
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
            return
        }
        lastFrameTime = currentTime
        
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image != null) {
                // Process frame asynchronously
                serviceScope.launch {
                    try {
                        frameProcessor?.processFrame(image)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing frame", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image", e)
        } finally {
            image?.close()
        }
    }

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            return
        }
        
        Log.d(TAG, "Stopping capture...")
        
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            Log.d(TAG, "Capture stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        
        stopCapture()
        
        serviceScope.cancel()
        frameProcessor?.cleanup()
        
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active screen content filtering"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pavlova Active")
            .setContentText("Protecting your screen content")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
