# Pavlova - Complete Implementation Plan

## Master's Thesis Prototype - Development Roadmap

**Target Timeline**: 12-16 weeks
**Complexity**: High (Hybrid Android/Rust, ML integration, System-level APIs)

---

## Phase 1: Project Setup & Infrastructure (Week 1-2)

### 1.1 Android Project Initialization

```bash
# Create new Android project
android create project \
  --target android-35 \
  --name Pavlova \
  --path ./pavlova-android \
  --activity MainActivity \
  --package com.thesis.pavlova
```

**Gradle Configuration** (`build.gradle.kts`):
```kotlin
plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.20"
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.3"
}

android {
    namespace = "com.thesis.pavlova"
    compileSdk = 35
    
    defaultConfig {
        applicationId = "com.thesis.pavlova"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-thesis"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }
    
    buildFeatures {
        viewBinding = true
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // MediaProjection & Graphics
    implementation("androidx.media:media:1.7.0")
    
    // WorkManager (for background tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

cargo {
    module = "./rust"
    libname = "pavlova_rust"
    targets = listOf("arm64", "arm", "x86_64")
    profile = "release"
}
```

---

### 1.2 Rust Library Setup

**Directory Structure**:
```
pavlova/
├── rust/
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs
│   │   ├── jni_bridge.rs
│   │   ├── inference/
│   │   │   ├── mod.rs
│   │   │   ├── tflite.rs
│   │   │   └── onnx.rs
│   │   ├── image/
│   │   │   ├── mod.rs
│   │   │   ├── conversion.rs
│   │   │   ├── resize.rs
│   │   │   └── effects.rs
│   │   ├── policy/
│   │   │   ├── mod.rs
│   │   │   └── engine.rs
│   │   └── utils/
│   │       ├── mod.rs
│   │       └── pool.rs
│   └── build.rs
└── android/
    └── ... (Android project)
```

**Cargo.toml**:
```toml
[package]
name = "pavlova_rust"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib", "staticlib"]

[dependencies]
# JNI bindings
jni = "0.21"

# Image processing
image = { version = "0.24", default-features = false, features = ["png", "jpeg"] }
fast_image_resize = "3.0"

# ML inference options
tflite = { version = "0.3", optional = true }
ort = { version = "2.0", optional = true }
tract-onnx = { version = "0.21", optional = true }

# Performance
rayon = "1.8"
crossbeam = "0.8"

# Serialization
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"

# Logging
log = "0.4"
android_logger = "0.13"

[features]
default = ["tflite-inference"]
tflite-inference = ["tflite"]
onnx-inference = ["ort"]
tract-inference = ["tract-onnx"]

[profile.release]
opt-level = 3
lto = true
codegen-units = 1
strip = true
```

---

## Phase 2: Core Android Components (Week 3-5)

### 2.1 Permission Management System

**File**: `app/src/main/kotlin/com/thesis/pavlova/permissions/PermissionManager.kt`

```kotlin
package com.thesis.pavlova.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

class PermissionManager(private val context: Context) {
    
    sealed class PermissionState {
        object Granted : PermissionState()
        object Denied : PermissionState()
        object PermanentlyDenied : PermissionState()
        object NotRequested : PermissionState()
    }
    
    fun checkOverlayPermission(): PermissionState {
        return if (Settings.canDrawOverlays(context)) {
            PermissionState.Granted
        } else {
            PermissionState.Denied
        }
    }
    
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }
    
    fun checkAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.contains(context.packageName)
    }
    
    fun requestAccessibilityPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivity(intent)
    }
    
    fun checkNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        
        return enabledListeners.contains(context.packageName)
    }
    
    fun requestNotificationListenerPermission(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        activity.startActivity(intent)
    }
    
    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
        const val REQUEST_MEDIA_PROJECTION = 1002
    }
}
```

---

### 2.2 MediaProjection Capture Service

**File**: `app/src/main/kotlin/com/thesis/pavlova/capture/ScreenCaptureService.kt`

```kotlin
package com.thesis.pavlova.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.thesis.pavlova.R
import com.thesis.pavlova.processing.FrameProcessor
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameProcessor: FrameProcessor? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // Frame capture configuration
    private val captureFrameRate = 10 // FPS
    private val captureIntervalMs = 1000L / captureFrameRate
    private var lastCaptureTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize screen metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        frameProcessor = FrameProcessor(this)
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startCapture(resultCode, data)
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    private fun startCapture(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        
        // Android 14+ callback for when user stops projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, null)
        }
        
        setupVirtualDisplay()
    }
    
    private fun setupVirtualDisplay() {
        // Create ImageReader for frame capture
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2 // Max images in queue
        ).apply {
            setOnImageAvailableListener({ reader ->
                processFrame(reader)
            }, null)
        }
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PavlovaCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }
    
    private fun processFrame(reader: ImageReader) {
        val now = System.currentTimeMillis()
        
        // Rate limiting: only process frames at target FPS
        if (now - lastCaptureTime < captureIntervalMs) {
            return
        }
        lastCaptureTime = now
        
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return
            
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            // Calculate actual width including padding
            val actualWidth = screenWidth + rowPadding / pixelStride
            
            // Copy frame data
            val frameData = ByteArray(buffer.remaining())
            buffer.get(frameData)
            
            // Process asynchronously
            serviceScope.launch {
                frameProcessor?.processFrame(
                    frameData,
                    actualWidth,
                    screenHeight,
                    pixelStride,
                    rowStride
                )
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }
    
    private fun stopCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Protection Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pavlova is monitoring screen content for safety"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pavlova Active")
            .setContentText("Screen content protection is running")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                "Stop Protection",
                stopPendingIntent
            )
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        serviceScope.cancel()
        frameProcessor?.cleanup()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    companion object {
        private const val CHANNEL_ID = "pavlova_capture_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_CAPTURE = "com.thesis.pavlova.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.thesis.pavlova.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
}
```

---

### 2.3 Frame Processing Coordinator

**File**: `app/src/main/kotlin/com/thesis/pavlova/processing/FrameProcessor.kt`

```kotlin
package com.thesis.pavlova.processing

import android.content.Context
import android.graphics.Bitmap
import com.thesis.pavlova.ml.ClassificationResult
import com.thesis.pavlova.ml.RustMLBridge
import com.thesis.pavlova.overlay.OverlayManager
import com.thesis.pavlova.policy.PolicyEngine
import com.thesis.pavlova.storage.EventLogger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class FrameProcessor(private val context: Context) {
    
    private val rustBridge = RustMLBridge(context)
    private val overlayManager = OverlayManager(context)
    private val policyEngine = PolicyEngine(context)
    private val eventLogger = EventLogger(context)
    
    private val isProcessing = AtomicBoolean(false)
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    suspend fun processFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ) = withContext(Dispatchers.Default) {
        
        // Skip if already processing (drop frames to maintain performance)
        if (!isProcessing.compareAndSet(false, true)) {
            return@withContext
        }
        
        try {
            // Step 1: Classify frame using Rust ML engine
            val classification = rustBridge.classifyFrame(
                frameData,
                width,
                height
            )
            
            // Step 2: Apply policy rules
            val decision = policyEngine.evaluateClassification(classification)
            
            // Step 3: Apply overlay if needed
            when (decision.action) {
                FilterAction.BLUR -> {
                    val blurredFrame = rustBridge.generateBlur(
                        frameData,
                        width,
                        height,
                        decision.intensity
                    )
                    overlayManager.showBlurOverlay(blurredFrame, width, height)
                }
                FilterAction.PIXELATE -> {
                    val pixelatedFrame = rustBridge.generatePixelation(
                        frameData,
                        width,
                        height,
                        decision.blockSize
                    )
                    overlayManager.showPixelatedOverlay(pixelatedFrame, width, height)
                }
                FilterAction.ALLOW -> {
                    overlayManager.hideOverlay()
                }
                FilterAction.BLOCK -> {
                    overlayManager.showBlockScreen()
                }
            }
            
            // Step 4: Log event (privacy-preserving)
            eventLogger.logFilterEvent(
                category = classification.category,
                action = decision.action,
                confidence = classification.confidence
            )
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Fail-safe: hide overlay on error
            overlayManager.hideOverlay()
        } finally {
            isProcessing.set(false)
        }
    }
    
    fun cleanup() {
        processingScope.cancel()
        rustBridge.cleanup()
        overlayManager.cleanup()
    }
}

enum class FilterAction {
    ALLOW,
    BLUR,
    PIXELATE,
    BLOCK
}

data class FilterDecision(
    val action: FilterAction,
    val intensity: Float = 0.8f,
    val blockSize: Int = 16
)
```

---

### 2.4 Overlay Management Service

**File**: `app/src/main/kotlin/com/thesis/pavlova/overlay/OverlayManager.kt`

```kotlin
package com.thesis.pavlova.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.thesis.pavlova.R
import kotlinx.coroutines.*

class OverlayManager(private val context: Context) {
    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isOverlayShowing = false
    
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
    }
    
    fun showBlurOverlay(blurredFrame: ByteArray, width: Int, height: Int) {
        overlayScope.launch {
            try {
                val bitmap = byteArrayToBitmap(blurredFrame, width, height)
                showOverlayWithBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun showPixelatedOverlay(pixelatedFrame: ByteArray, width: Int, height: Int) {
        overlayScope.launch {
            try {
                val bitmap = byteArrayToBitmap(pixelatedFrame, width, height)
                showOverlayWithBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun showBlockScreen() {
        overlayScope.launch {
            if (overlayView == null) {
                overlayView = LayoutInflater.from(context)
                    .inflate(R.layout.overlay_block_screen, null)
            }
            
            if (!isOverlayShowing) {
                windowManager.addView(overlayView, layoutParams)
                isOverlayShowing = true
            }
        }
    }
    
    fun hideOverlay() {
        overlayScope.launch {
            try {
                if (isOverlayShowing && overlayView != null) {
                    windowManager.removeView(overlayView)
                    isOverlayShowing = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun showOverlayWithBitmap(bitmap: Bitmap) {
        if (overlayView == null) {
            overlayView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
            }
        }
        
        (overlayView as? ImageView)?.setImageBitmap(bitmap)
        
        if (!isOverlayShowing) {
            windowManager.addView(overlayView, layoutParams)
            isOverlayShowing = true
        } else {
            windowManager.updateViewLayout(overlayView, layoutParams)
        }
    }
    
    private fun byteArrayToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = java.nio.ByteBuffer.wrap(data)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    fun cleanup() {
        overlayScope.cancel()
        hideOverlay()
    }
}
```

---

## Phase 3: Rust ML & Image Processing (Week 6-8)

### 3.1 JNI Bridge Setup

**File**: `rust/src/jni_bridge.rs`

```rust
use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JObject};
use jni::sys::{jlong, jint, jfloat, jbyteArray, jobject};
use std::sync::Arc;
use crate::inference::InferenceEngine;
use crate::image::effects::{apply_gaussian_blur, apply_pixelation};
use crate::image::conversion::rgba_to_bytes;

/// Initialize the ML inference engine
#[no_mangle]
pub extern "C" fn Java_com_thesis_pavlova_ml_RustMLBridge_initEngine(
    env: JNIEnv,
    _class: JClass,
    model_path: jni::objects::JString,
    threshold: jfloat,
) -> jlong {
    android_logger::init_once(
        android_logger::Config::default()
            .with_min_level(log::Level::Info)
            .with_tag("PavlovaRust")
    );
    
    let model_path_str: String = env.get_string(model_path)
        .expect("Invalid model path")
        .into();
    
    match InferenceEngine::new(&model_path_str, threshold) {
        Ok(engine) => Box::into_raw(Box::new(engine)) as jlong,
        Err(e) => {
            log::error!("Failed to initialize engine: {}", e);
            0
        }
    }
}

/// Classify a frame
#[no_mangle]
pub extern "C" fn Java_com_thesis_pavlova_ml_RustMLBridge_classifyFrame(
    env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    frame_data: JByteArray,
    width: jint,
    height: jint,
) -> jobject {
    let engine = unsafe { &*(engine_handle as *const InferenceEngine) };
    
    let frame_bytes = env.convert_byte_array(frame_data)
        .expect("Failed to convert frame data");
    
    match engine.classify(&frame_bytes, width as u32, height as u32) {
        Ok(result) => {
            // Create ClassificationResult Java object
            let class = env.find_class("com/thesis/pavlova/ml/ClassificationResult")
                .expect("ClassificationResult class not found");
            
            let obj = env.new_object(
                class,
                "(Ljava/lang/String;FZ)V",
                &[
                    env.new_string(&result.category).unwrap().into(),
                    (result.confidence as jfloat).into(),
                    (result.is_safe as u8).into(),
                ]
            ).expect("Failed to create ClassificationResult");
            
            obj.into_raw()
        }
        Err(e) => {
            log::error!("Classification failed: {}", e);
            JObject::null().into_raw()
        }
    }
}

/// Generate blurred version of frame
#[no_mangle]
pub extern "C" fn Java_com_thesis_pavlova_ml_RustMLBridge_generateBlur(
    env: JNIEnv,
    _class: JClass,
    frame_data: JByteArray,
    width: jint,
    height: jint,
    intensity: jfloat,
) -> jbyteArray {
    let frame_bytes = env.convert_byte_array(frame_data)
        .expect("Failed to convert frame data");
    
    let mut rgba_buffer = frame_bytes.clone();
    
    let radius = (intensity * 20.0) as u32;
    apply_gaussian_blur(&mut rgba_buffer, width as u32, height as u32, radius);
    
    let result_array = env.byte_array_from_slice(&rgba_buffer)
        .expect("Failed to create result array");
    
    result_array
}

/// Generate pixelated version of frame
#[no_mangle]
pub extern "C" fn Java_com_thesis_pavlova_ml_RustMLBridge_generatePixelation(
    env: JNIEnv,
    _class: JClass,
    frame_data: JByteArray,
    width: jint,
    height: jint,
    block_size: jint,
) -> jbyteArray {
    let frame_bytes = env.convert_byte_array(frame_data)
        .expect("Failed to convert frame data");
    
    let mut rgba_buffer = frame_bytes.clone();
    
    apply_pixelation(&mut rgba_buffer, width as u32, height as u32, block_size as u32);
    
    let result_array = env.byte_array_from_slice(&rgba_buffer)
        .expect("Failed to create result array");
    
    result_array
}

/// Cleanup engine
#[no_mangle]
pub extern "C" fn Java_com_thesis_pavlova_ml_RustMLBridge_destroyEngine(
    _env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
) {
    if engine_handle != 0 {
        unsafe {
            let _ = Box::from_raw(engine_handle as *mut InferenceEngine);
        }
    }
}
```

---

### 3.2 ML Inference Module

**File**: `rust/src/inference/mod.rs`

```rust
pub mod tflite;

use std::error::Error;

#[derive(Debug, Clone)]
pub struct ClassificationResult {
    pub category: String,
    pub confidence: f32,
    pub is_safe: bool,
}

pub trait Model {
    fn infer(&self, input: &[f32]) -> Result<ClassificationResult, Box<dyn Error>>;
    fn input_shape(&self) -> (u32, u32, u32); // (width, height, channels)
}

pub struct InferenceEngine {
    model: Box<dyn Model + Send + Sync>,
    threshold: f32,
}

impl InferenceEngine {
    pub fn new(model_path: &str, threshold: f32) -> Result<Self, Box<dyn Error>> {
        let model: Box<dyn Model + Send + Sync> = if model_path.ends_with(".tflite") {
            Box::new(tflite::TFLiteModel::load(model_path)?)
        } else {
            return Err("Unsupported model format".into());
        };
        
        Ok(Self { model, threshold })
    }
    
    pub fn classify(&self, frame_rgba: &[u8], width: u32, height: u32) 
        -> Result<ClassificationResult, Box<dyn Error>> {
        
        let (model_w, model_h, model_c) = self.model.input_shape();
        
        // Resize if needed
        let resized = if width != model_w || height != model_h {
            crate::image::resize::resize_rgba(frame_rgba, width, height, model_w, model_h)?
        } else {
            frame_rgba.to_vec()
        };
        
        // Normalize to [0, 1]
        let normalized: Vec<f32> = resized.iter()
            .map(|&byte| byte as f32 / 255.0)
            .collect();
        
        // Run inference
        let mut result = self.model.infer(&normalized)?;
        
        // Apply threshold
        result.is_safe = result.confidence < self.threshold;
        
        Ok(result)
    }
}
```

**File**: `rust/src/inference/tflite.rs`

```rust
use super::{ClassificationResult, Model};
use std::error::Error;

pub struct TFLiteModel {
    // Placeholder for TFLite interpreter
    input_width: u32,
    input_height: u32,
    input_channels: u32,
}

impl TFLiteModel {
    pub fn load(model_path: &str) -> Result<Self, Box<dyn Error>> {
        // TODO: Load actual TFLite model
        // For now, return dummy model
        log::info!("Loading TFLite model from: {}", model_path);
        
        Ok(Self {
            input_width: 224,
            input_height: 224,
            input_channels: 3,
        })
    }
}

impl Model for TFLiteModel {
    fn infer(&self, input: &[f32]) -> Result<ClassificationResult, Box<dyn Error>> {
        // TODO: Implement actual TFLite inference
        // This is a stub implementation
        
        // Simulate classification
        let category = if input.iter().sum::<f32>() / input.len() as f32 > 0.5 {
            "unsafe_content".to_string()
        } else {
            "safe".to_string()
        };
        
        Ok(ClassificationResult {
            category,
            confidence: 0.85,
            is_safe: false,
        })
    }
    
    fn input_shape(&self) -> (u32, u32, u32) {
        (self.input_width, self.input_height, self.input_channels)
    }
}
```

---

### 3.3 Image Processing Effects

**File**: `rust/src/image/effects.rs`

```rust
use rayon::prelude::*;

/// Apply Gaussian blur to RGBA image
pub fn apply_gaussian_blur(buffer: &mut [u8], width: u32, height: u32, radius: u32) {
    if radius == 0 {
        return;
    }
    
    let kernel = generate_gaussian_kernel(radius);
    let kernel_size = kernel.len();
    let kernel_radius = (kernel_size / 2) as i32;
    
    let original = buffer.to_vec();
    
    // Horizontal pass
    let mut temp = vec![0u8; buffer.len()];
    
    for y in 0..height {
        for x in 0..width {
            let mut r = 0.0f32;
            let mut g = 0.0f32;
            let mut b = 0.0f32;
            let mut a = 0.0f32;
            let mut weight_sum = 0.0f32;
            
            for k in 0..kernel_size {
                let offset_x = x as i32 + k as i32 - kernel_radius;
                if offset_x >= 0 && offset_x < width as i32 {
                    let idx = ((y * width + offset_x as u32) * 4) as usize;
                    let weight = kernel[k];
                    
                    r += original[idx] as f32 * weight;
                    g += original[idx + 1] as f32 * weight;
                    b += original[idx + 2] as f32 * weight;
                    a += original[idx + 3] as f32 * weight;
                    weight_sum += weight;
                }
            }
            
            let idx = ((y * width + x) * 4) as usize;
            temp[idx] = (r / weight_sum) as u8;
            temp[idx + 1] = (g / weight_sum) as u8;
            temp[idx + 2] = (b / weight_sum) as u8;
            temp[idx + 3] = (a / weight_sum) as u8;
        }
    }
    
    // Vertical pass
    for y in 0..height {
        for x in 0..width {
            let mut r = 0.0f32;
            let mut g = 0.0f32;
            let mut b = 0.0f32;
            let mut a = 0.0f32;
            let mut weight_sum = 0.0f32;
            
            for k in 0..kernel_size {
                let offset_y = y as i32 + k as i32 - kernel_radius;
                if offset_y >= 0 && offset_y < height as i32 {
                    let idx = ((offset_y as u32 * width + x) * 4) as usize;
                    let weight = kernel[k];
                    
                    r += temp[idx] as f32 * weight;
                    g += temp[idx + 1] as f32 * weight;
                    b += temp[idx + 2] as f32 * weight;
                    a += temp[idx + 3] as f32 * weight;
                    weight_sum += weight;
                }
            }
            
            let idx = ((y * width + x) * 4) as usize;
            buffer[idx] = (r / weight_sum) as u8;
            buffer[idx + 1] = (g / weight_sum) as u8;
            buffer[idx + 2] = (b / weight_sum) as u8;
            buffer[idx + 3] = (a / weight_sum) as u8;
        }
    }
}

/// Generate Gaussian kernel
fn generate_gaussian_kernel(radius: u32) -> Vec<f32> {
    let size = (radius * 2 + 1) as usize;
    let mut kernel = vec![0.0f32; size];
    let sigma = radius as f32 / 3.0;
    let two_sigma_sq = 2.0 * sigma * sigma;
    let mut sum = 0.0f32;
    
    for i in 0..size {
        let x = i as f32 - radius as f32;
        let value = (-x * x / two_sigma_sq).exp();
        kernel[i] = value;
        sum += value;
    }
    
    // Normalize
    for i in 0..size {
        kernel[i] /= sum;
    }
    
    kernel
}

/// Apply pixelation effect to RGBA image
pub fn apply_pixelation(buffer: &mut [u8], width: u32, height: u32, block_size: u32) {
    if block_size <= 1 {
        return;
    }
    
    let blocks_x = (width + block_size - 1) / block_size;
    let blocks_y = (height + block_size - 1) / block_size;
    
    for by in 0..blocks_y {
        for bx in 0..blocks_x {
            let x_start = bx * block_size;
            let y_start = by * block_size;
            let x_end = (x_start + block_size).min(width);
            let y_end = (y_start + block_size).min(height);
            
            // Calculate average color in block
            let mut r_sum = 0u32;
            let mut g_sum = 0u32;
            let mut b_sum = 0u32;
            let mut a_sum = 0u32;
            let mut count = 0u32;
            
            for y in y_start..y_end {
                for x in x_start..x_end {
                    let idx = ((y * width + x) * 4) as usize;
                    r_sum += buffer[idx] as u32;
                    g_sum += buffer[idx + 1] as u32;
                    b_sum += buffer[idx + 2] as u32;
                    a_sum += buffer[idx + 3] as u32;
                    count += 1;
                }
            }
            
            let r_avg = (r_sum / count) as u8;
            let g_avg = (g_sum / count) as u8;
            let b_avg = (b_sum / count) as u8;
            let a_avg = (a_sum / count) as u8;
            
            // Apply average color to entire block
            for y in y_start..y_end {
                for x in x_start..x_end {
                    let idx = ((y * width + x) * 4) as usize;
                    buffer[idx] = r_avg;
                    buffer[idx + 1] = g_avg;
                    buffer[idx + 2] = b_avg;
                    buffer[idx + 3] = a_avg;
                }
            }
        }
    }
}

/// Fast box blur (alternative to Gaussian)
pub fn apply_box_blur(buffer: &mut [u8], width: u32, height: u32, radius: u32) {
    // Implementation using sliding window for O(n) complexity
    // Simpler and faster than Gaussian for mobile devices
    // TODO: Implement if needed
}
```

---

**File**: `rust/src/image/resize.rs`

```rust
use fast_image_resize as fr;
use std::error::Error;

pub fn resize_rgba(
    src: &[u8],
    src_width: u32,
    src_height: u32,
    dst_width: u32,
    dst_height: u32,
) -> Result<Vec<u8>, Box<dyn Error>> {
    
    let src_image = fr::Image::from_slice_u8(
        src_width,
        src_height,
        src,
        fr::PixelType::U8x4,
    )?;
    
    let mut dst_image = fr::Image::new(
        dst_width,
        dst_height,
        fr::PixelType::U8x4,
    );
    
    let mut resizer = fr::Resizer::new(
        fr::ResizeAlg::Convolution(fr::FilterType::Lanczos3),
    );
    
    resizer.resize(&src_image.view(), &mut dst_image.view_mut())?;
    
    Ok(dst_image.into_vec())
}
```

---

## Implementation continues in next file...

**Deliverables Status:**
✅ System Architecture
✅ Kotlin Scaffolding (Services, Permissions, Overlay)
✅ Rust Scaffolding (JNI, ML, Image Processing)
⏳ Privacy Flows & Battery Optimization
⏳ Evaluation Metrics
⏳ Master's Thesis Outline

Continue reading IMPLEMENTATION_PLAN_PART2.md for remaining sections.
