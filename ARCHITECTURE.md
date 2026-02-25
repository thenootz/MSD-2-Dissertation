# Pavlova - Architecture Overview

## Executive Summary

Pavlova is a privacy-focused Android application that provides real-time content filtering through on-device machine learning. The app captures screen frames, classifies content using ML models, and applies visual overlays to protect users from unwanted content—all while maintaining strict privacy guarantees through local processing and minimal data retention.

## Core Principles

1. **Privacy-First**: No screenshots stored, no cloud processing, minimal logging
2. **On-Device Processing**: All ML inference happens locally
3. **User Consent**: Per-session MediaProjection consent (Android 14+ compliant)
4. **Performance**: Rust-native performance for critical paths
5. **Transparency**: Clear user communication about what data is collected

---

## System Architecture

### High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Android UI Layer                         │
│                    (Kotlin - MainActivity, Settings)             │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────┴────────────────────────────────┐
│                      Service Layer (Kotlin)                      │
├──────────────────────┬──────────────────┬───────────────────────┤
│ ScreenCaptureService │ OverlayService   │ ContextService        │
│ (MediaProjection)    │ (TYPE_APP_OVERLAY)│ (Accessibility)      │
└──────────┬───────────┴────────┬─────────┴───────────┬───────────┘
           │                    │                     │
           │ Screen Frames      │ Apply Overlay       │ App Context
           │                    │                     │
┌──────────▼────────────────────▼─────────────────────▼───────────┐
│              Processing Coordinator (Kotlin)                     │
│           - Frame Queue Management                               │
│           - JNI Bridge to Rust                                   │
│           - Lifecycle Management                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │ JNI/FFI
┌──────────────────────────────▼──────────────────────────────────┐
│                    Rust Native Layer                             │
├──────────────────┬────────────────────┬──────────────────────────┤
│ Image Processing │ ML Inference       │ Policy Engine            │
│ - YUV→RGB        │ - TFLite/ONNX      │ - Classification Rules   │
│ - Resizing       │ - Model Management │ - Confidence Thresholds  │
│ - Blur/Pixelate  │ - Batch Processing │ - Context Integration    │
└──────────────────┴────────────────────┴──────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                    Storage Layer (Kotlin)                        │
│  - SharedPreferences (Settings)                                  │
│  - Room Database (Timestamps/Categories only)                    │
│  - No image data stored                                          │
└──────────────────────────────────────────────────────────────────┘
```

---

## Component Details

### 1. MediaProjection Capture Service

**Technology**: Kotlin + Android MediaProjection API

**Responsibilities**:
- Request user consent per session (Android 14+ single-session grant)
- Create virtual display for screen capture
- Capture frames at configurable FPS (default: 5-10 FPS)
- Convert hardware buffer to processable format
- Feed frames to processing pipeline

**Key Classes**:
- `ScreenCaptureService`: Foreground service managing MediaProjection
- `VirtualDisplayCallback`: Handles frame capture events
- `FrameBuffer`: Circular buffer for frame queue management

**Android 14+ Considerations**:
- Must request consent for each capture session
- Cannot run continuously in background without user awareness
- Show persistent notification during capture
- Handle projection stop callback gracefully

---

### 2. ML Inference Engine

**Technology**: Rust + TFLite/ONNX bindings

**Responsibilities**:
- Load and manage ML models
- Preprocess frames (resize, normalize)
- Run inference on frames
- Return classification results with confidence scores

**Model Options**:
1. **TensorFlow Lite** (via `tflite-rs` or C++ FFI)
   - Good for MobileNet-based classifiers
   - Hardware acceleration via NNAPI/GPU delegates
   
2. **ONNX Runtime** (via `ort` crate)
   - Broader model compatibility
   - Cross-platform
   
3. **Rust-native** (tract, burn, candle)
   - Pure Rust inference
   - Best integration, no C++ dependencies

**Recommended Architecture**:
```rust
pub struct InferenceEngine {
    model: Box<dyn Model>,
    input_shape: ImageShape,
    threshold: f32,
}

impl InferenceEngine {
    pub fn classify_frame(&self, frame: &[u8]) -> ClassificationResult {
        // Preprocess → Infer → Postprocess
    }
}
```

**Classification Categories**:
- Safe content
- Adult content
- Violence
- Disturbing imagery
- Custom user-defined categories

---

### 3. Image Processing Pipeline (Rust)

**Responsibilities**:
- YUV to RGB conversion (Android camera frames are typically YUV)
- Image resizing for ML input
- Blur/pixelation effect generation
- Efficient memory management

**Key Modules**:

```rust
// Image format conversion
pub mod conversion {
    pub fn yuv_to_rgb(yuv: &[u8], width: u32, height: u32) -> Vec<u8>;
    pub fn resize_image(rgb: &[u8], src_size: Size, dst_size: Size) -> Vec<u8>;
}

// Visual effects
pub mod effects {
    pub fn gaussian_blur(image: &mut [u8], radius: u32);
    pub fn pixelate(image: &mut [u8], block_size: u32);
    pub fn adaptive_blur(image: &mut [u8], intensity: f32);
}
```

**Performance Optimizations**:
- Use SIMD instructions (via `std::arch` or `packed_simd`)
- Parallel processing with Rayon
- Memory pooling to avoid allocations
- Zero-copy where possible via shared memory

---

### 4. Overlay Service

**Technology**: Kotlin + Android WindowManager

**Responsibilities**:
- Create TYPE_APPLICATION_OVERLAY window
- Render blur/pixelation effect over unsafe content
- Handle touch events (pass-through vs. interactive)
- Manage overlay lifecycle

**Implementation Strategy**:
```kotlin
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    
    fun showBlurOverlay(blurredBitmap: Bitmap, region: Rect?) {
        // Apply blur to full screen or specific region
    }
    
    fun hideOverlay() {
        // Remove overlay when content is safe
    }
}
```

**Overlay Modes**:
1. **Full Screen Blur**: Complete screen coverage
2. **Region-Based**: Blur only detected unsafe regions (requires object detection)
3. **Gradual Fade**: Smooth transition for better UX

---

### 5. Context Services (Optional)

#### 5.1 Accessibility Service

**Purpose**: Identify foreground app for context-aware filtering

**Privacy Guarantee**: 
- Only extracts package name and app label
- Does NOT log user actions or touch events
- Does NOT capture accessibility node trees beyond current app

```kotlin
class AppContextService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            // Pass to policy engine for context-aware decisions
        }
    }
}
```

#### 5.2 NotificationListenerService

**Purpose**: Extract metadata from media notifications

**Data Collected**:
- Song/podcast title
- Artist/creator name
- Album/show name
- App playing media

**Privacy Guarantee**:
- Metadata only, no notification content
- No message/email notification access
- Clear user consent and purpose explanation

```kotlin
class MediaContextService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val mediaSession = sbn.notification.extras
            .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        // Extract safe metadata
    }
}
```

---

### 6. Policy Engine (Rust)

**Responsibilities**:
- Apply user-defined filtering rules
- Combine ML predictions with context
- Make final decision on content filtering
- Generate privacy-preserving logs

**Architecture**:
```rust
pub struct PolicyEngine {
    rules: Vec<FilterRule>,
    context_provider: ContextProvider,
}

pub struct FilterRule {
    app_package: Option<String>,
    category: ContentCategory,
    action: FilterAction,
    confidence_threshold: f32,
}

pub enum FilterAction {
    Allow,
    Blur,
    Pixelate,
    Block,
    Warn,
}

impl PolicyEngine {
    pub fn should_filter(&self, 
        classification: &Classification, 
        context: &AppContext) -> FilterDecision {
        // Apply rules based on ML result + context
    }
}
```

**Example Rules**:
- Always allow content in trusted apps
- Higher sensitivity during work hours
- Category-specific thresholds
- User override capabilities

---

### 7. Storage & Logging

**Privacy-Preserving Data**:

```kotlin
@Entity(tableName = "filter_events")
data class FilterEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,  // "adult", "violence", etc.
    val action: String,     // "blur", "allow"
    val appContext: String? // Optional: Package name only
    // NO SCREENSHOTS
    // NO CONTENT DETAILS
    // NO USER-IDENTIFIABLE DATA
)
```

**User Benefits**:
- View filtering statistics
- Understand app behavior
- Debug false positives
- Export anonymized data for ML improvement

---

## Data Flow

### Frame Processing Pipeline

```
User Screen
    ↓
MediaProjection Capture (10 FPS)
    ↓
Frame Queue (Kotlin - Bounded)
    ↓
JNI Call to Rust
    ↓
[RUST] YUV → RGB Conversion
    ↓
[RUST] Resize to Model Input (e.g., 224x224)
    ↓
[RUST] ML Inference (TFLite/ONNX)
    ↓
[RUST] Classification Result + Confidence
    ↓
[RUST] Policy Engine Decision
    ↓
JNI Return to Kotlin
    ↓
If Unsafe: Generate Blur in Rust
    ↓
Apply Overlay via WindowManager
    ↓
Log Event (timestamp + category only)
```

**Performance Targets**:
- Frame capture to decision: < 100ms
- ML inference: < 50ms per frame
- Overlay application: < 16ms (smooth 60fps)
- Memory usage: < 150MB total

---

## Kotlin ↔ Rust Interface (JNI/FFI)

### JNI Bridge Design

**Kotlin Side**:
```kotlin
object PavlovaRustBridge {
    external fun initInferenceEngine(modelPath: String, threshold: Float): Long
    external fun classifyFrame(
        engineHandle: Long,
        frameData: ByteArray,
        width: Int,
        height: Int
    ): ClassificationResult
    external fun generateBlur(
        frameData: ByteArray,
        width: Int,
        height: Int,
        intensity: Float
    ): ByteArray
    external fun destroyEngine(engineHandle: Long)
    
    init {
        System.loadLibrary("pavlova_rust")
    }
}
```

**Rust Side** (using `jni` crate):
```rust
#[no_mangle]
pub extern "C" fn Java_com_pavlova_PavlovaRustBridge_initInferenceEngine(
    env: JNIEnv,
    _class: JClass,
    model_path: JString,
    threshold: jfloat,
) -> jlong {
    // Initialize engine, return handle
}

#[no_mangle]
pub extern "C" fn Java_com_pavlova_PavlovaRustBridge_classifyFrame(
    env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    frame_data: jbyteArray,
    width: jint,
    height: jint,
) -> jobject {
    // Process frame, return result
}
```

**Optimization**: Use DirectByteBuffer for zero-copy data transfer

---

## Permission Model

### Required Permissions

```xml
<!-- Screen Capture -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<!-- Overlay -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Optional: Context Services -->
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" 
    tools:node="remove" /> <!-- Only if user enables -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    tools:node="remove" /> <!-- Only if user enables -->

<!-- Internet only for model updates, can be disabled -->
<uses-permission android:name="android.permission.INTERNET" />
```

### Runtime Permission Flow

1. **First Launch**:
   - Explain app purpose
   - Request SYSTEM_ALERT_WINDOW via Settings
   - Request notification permission (Android 13+)

2. **First Activation**:
   - Request MediaProjection consent
   - Show clear warning about screen capture
   - Explain per-session requirement (Android 14+)

3. **Optional Features**:
   - Separate opt-in for Accessibility Service
   - Separate opt-in for Notification Listener
   - Clear explanation of what data is accessed

---

## Android Version Compatibility

### Minimum SDK: API 26 (Android 8.0)
- MediaProjection available
- TYPE_APPLICATION_OVERLAY stable
- Notification channels

### Target SDK: API 35 (Android 15)
- Latest security features
- Foreground service types
- Privacy dashboard integration

### Version-Specific Handling

| Android Version | Key Considerations |
|----------------|-------------------|
| 8.0 - 9.0      | Basic MediaProjection, manual permission handling |
| 10             | Scoped storage, background restrictions |
| 11             | One-time permissions, package visibility |
| 12             | Splash screen, approximate location |
| 13             | Notification permission, themed icons |
| 14             | **Per-session MediaProjection consent**, predictive back |
| 15             | Enhanced privacy controls, screen recording detection |

---

## Security Considerations

### Threat Model

**Threats Mitigated**:
1. ✅ User exposure to unwanted content
2. ✅ Accidental sharing of sensitive screen content
3. ✅ Privacy leaks from screenshot storage

**Threats NOT Mitigated**:
1. ❌ Malicious apps bypassing overlay (root/system apps)
2. ❌ User intentionally disabling protection
3. ❌ Hardware-level screen capture

### Security Measures

1. **Model Integrity**:
   - Verify ML model checksums
   - Secure model updates
   - Fallback to safe mode on tampering

2. **Code Obfuscation**:
   - ProGuard/R8 for Kotlin
   - Strip symbols from Rust binaries
   - Runtime integrity checks

3. **Data Protection**:
   - Encrypt local database (though minimal data stored)
   - Clear memory buffers after processing
   - No debugging logs in production

---

## Performance Optimization Strategy

### Critical Path Optimization

1. **Frame Capture** (Kotlin):
   - Use ImageReader with PRIVATE format
   - Reuse buffer pool
   - Drop frames if processing queue full

2. **Image Processing** (Rust):
   - SIMD for YUV conversion
   - Multi-threaded resize with Rayon
   - Lookup tables for blur kernels

3. **ML Inference** (Rust):
   - Quantized models (INT8)
   - NNAPI/GPU delegation when available
   - Batch processing if queue allows

4. **Overlay Rendering** (Kotlin):
   - Hardware-accelerated canvas
   - Dirty region tracking
   - VSync alignment

### Memory Management

```rust
// Use memory pools for frame buffers
pub struct FramePool {
    buffers: Vec<Vec<u8>>,
    available: VecDeque<usize>,
}

impl FramePool {
    pub fn acquire(&mut self) -> &mut Vec<u8> {
        // Reuse existing buffer
    }
    
    pub fn release(&mut self, buffer_index: usize) {
        // Return to pool
    }
}
```

---

## Testing Strategy

### Unit Tests

**Kotlin**:
- Service lifecycle
- Permission handling
- UI state management

**Rust**:
- Image processing correctness
- ML inference accuracy
- Policy engine logic

### Integration Tests

- JNI boundary correctness
- End-to-end frame processing
- Performance benchmarks

### Testing Tools

- **Kotlin**: JUnit, Mockito, Robolectric
- **Rust**: cargo test, criterion (benchmarks)
- **Integration**: Android Instrumentation tests

---

## Deployment Considerations

### APK Size Optimization

- Use Android App Bundle
- Split APKs by architecture (arm64-v8a, armeabi-v7a)
- Compress ML models
- On-demand model download

### Battery Impact Mitigation

- Adaptive frame rate (lower FPS when screen static)
- Pause when screen off
- Efficient wake locks
- Battery usage attribution

### Play Store Compliance

- Clear privacy policy
- Accessibility service justification form
- Transparent data collection disclosure
- No deceptive behavior

---

## ML Model Strategy — 3 Phases

### Phase 1: MVP — Pre-trained NSFW Classifier
- **Model**: GantMan/nsfw_model (MobileNetV2 1.4, INT8, ~3MB)
- **Output**: 5 classes (Drawing, Hentai, Neutral, Porn, Sexy) → mapped to safe/adult binary
- **Backend**: `tract` (pure Rust, no C dependencies)
- **Goal**: End-to-end pipeline working on device

### Phase 2: Multi-Category — Custom Fine-tuned Model
- **Model**: EfficientNet-Lite0 or MobileNetV3-Large fine-tuned on NSFW + violence + gore datasets
- **Output**: Multi-label `[safe, adult, violence, gore, hate]`
- **Alternatives**: Falconsai/nsfw_image_detection (ViT, ONNX), bumble-tech/private-detector (EfficientNet-B0)
- **Goal**: Full 5-category classification with improved accuracy

### Phase 3: Advanced — Multi-Model Ensemble
- **Primary**: EfficientNet-Lite0 content classifier (< 15ms)
- **Secondary**: Text-in-image OCR detector for hate speech
- **Optimization**: NNAPI/GPU delegates, ARM NEON SIMD, region-based blur via object detection
- **Goal**: Production-quality with hardware acceleration

---

## Future Enhancements

1. **Advanced ML** (Phase 3+):
   - Object detection for region-based blur (blur only unsafe regions)
   - OCR for text content filtering (hate speech in screenshots)
   - Audio content analysis

2. **User Customization**:
   - Custom model training
   - Per-app sensitivity settings
   - Scheduling (work hours vs. personal time)

3. **Cross-Device**:
   - Sync settings across devices
   - Shared filter lists
   - Community-contributed models

4. **Accessibility**:
   - Integration with screen readers
   - Voice control for quick override
   - Haptic feedback

---

## Open Questions / Decisions Needed

1. ~~**ML Model Source**: Pre-trained public model vs. custom training?~~ → **Decided**: Phase 1 uses GantMan/nsfw_model (MIT), Phase 2 fine-tunes EfficientNet-Lite0
2. ~~**Inference Framework**: TFLite vs. ONNX vs. pure Rust (tract)?~~ → **Decided**: `tract` for Phase 1 (pure Rust, easiest cross-compilation), `ort` as Phase 2 option for ViT models
3. **Monetization**: Free + Pro features? One-time purchase? FOSS?
4. **Target Audience**: Parental control? Workplace safety? General privacy?
5. **Brand + UX**: App name, iconography, color scheme
6. ~~**Initial Release Scope**: Full feature set or MVP with core functionality?~~ → **Decided**: MVP (Phase 1) with core NSFW detection, then iterate

---

## References & Resources

### Android Documentation
- [MediaProjection API](https://developer.android.com/reference/android/media/projection/MediaProjection)
- [Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [TYPE_APPLICATION_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)

### Rust Android Development
- [mozilla/rust-android-gradle](https://github.com/mozilla/rust-android-gradle)
- [jni-rs](https://github.com/jni-rs/jni-rs)

### ML on Android
- [TensorFlow Lite](https://www.tensorflow.org/lite/android)
- [ONNX Runtime](https://onnxruntime.ai/docs/tutorials/mobile/)
- [tract](https://github.com/sonos/tract)

---

**Document Version**: 1.0
**Last Updated**: 2026-02-25
**Status**: Initial Architecture Design
