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
import com.pavlova.ml.FeedAnalyzer
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
        private const val TARGET_FPS = 2  // Lower rate for OCR-based analysis
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var feedAnalyzer: FeedAnalyzer? = null
    
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
        feedAnalyzer = FeedAnalyzer(this)
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
            
            // Fix: Register a callback before creating VirtualDisplay (required on Android 14+)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Fired when the user ends the screen share from the system
                    // UI (cast notification / "Stop sharing"). Tear everything
                    // down and bring Pavlova back to the foreground so the
                    // session is cleanly closed and visible.
                    Log.d(TAG, "MediaProjection stopped by system/user")
                    onProjectionEnded()
                }
            }, null)
            
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
            CaptureState.setCapturing(true)

            // Start an auditing session
            serviceScope.launch {
                feedAnalyzer?.startSession()
            }

            Log.d(TAG, "Screen capture started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopSelf()
        }
    }

    private fun handleNewFrame(reader: ImageReader) {
        var image: android.media.Image? = null
        try {
            // Always acquire (and close in finally) so the ImageReader's buffer queue
            // keeps draining. If we rate-limit before acquiring, unacquired buffers
            // pile up (MAX_IMAGES) and the virtual display stalls — no further
            // onImageAvailable callbacks ever fire, so only the first frame is ever
            // processed for the whole session.
            image = reader.acquireLatestImage()
            if (image != null) {
                // Rate limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                    return
                }
                lastFrameTime = currentTime

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val width = image.width
                val height = image.height

                // ImageReader rows are often padded to a row stride wider than
                // width * pixelStride. Strip that padding so the byte array is a
                // tightly-packed RGBA buffer matching width x height, otherwise
                // Bitmap.copyPixelsFromBuffer skews every row and OCR finds nothing.
                val imageData: ByteArray
                if (rowStride == pixelStride * width) {
                    imageData = ByteArray(buffer.remaining())
                    buffer.get(imageData)
                } else {
                    val rowBytes = pixelStride * width
                    imageData = ByteArray(rowBytes * height)
                    val rowBuffer = ByteArray(rowStride)
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        buffer.get(rowBuffer, 0, minOf(rowStride, buffer.remaining()))
                        System.arraycopy(rowBuffer, 0, imageData, row * rowBytes, rowBytes)
                    }
                }
                buffer.rewind()

                serviceScope.launch {
                    try {
                        feedAnalyzer?.processFrame(imageData, width, height)
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

    /**
     * Called when the screen share ends from outside the app (the system
     * "Stop sharing" affordance fires [MediaProjection.Callback.onStop]).
     * Fully stops the service (which ends the audit session via [onDestroy])
     * and relaunches Pavlova so the user lands back on the dashboard.
     */
    private fun onProjectionEnded() {
        stopCapture()
        reopenApp()
        // stopSelf() → onDestroy() ends the session, closes the analyzer, and
        // clears CaptureState so the dashboard button flips to "Start".
        stopSelf()
    }

    /** Bring the Pavlova dashboard back to the foreground. */
    private fun reopenApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reopen app after projection end", e)
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

        CaptureState.setCapturing(false)

        // End the auditing session
        runBlocking {
            feedAnalyzer?.endSession()
        }
        feedAnalyzer?.destroy()

        stopCapture()

        serviceScope.cancel()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Feed Audit",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Auditing social media feed recommendations"
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
            .setContentTitle("Pavlova Auditing")
            .setContentText("Analyzing feed recommendations")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
