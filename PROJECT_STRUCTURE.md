# Pavlova - Project Structure & Setup Guide

## Complete Project Directory Structure

```
pavlova/
├── README.md                          # Main project documentation
├── ARCHITECTURE.md                    # System architecture overview
├── IMPLEMENTATION_PLAN.md             # Detailed implementation guide
├── RUST_LIBRARY_SPEC.md               # Rust library specifications
├── PRIVACY_SECURITY.md                # Privacy & security guidelines
├── EVALUATION.md                      # Testing & evaluation methodology
├── THESIS_OUTLINE.md                  # Master's thesis structure
├── LICENSE                            # MIT or GPL (choose based on model licenses)
│
├── android/                           # Android app
│   ├── app/
│   │   ├── build.gradle.kts
│   │   ├── proguard-rules.pro
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── java/com/thesis/pavlova/
│   │       │   │   ├── MainActivity.kt
│   │       │   │   ├── capture/
│   │       │   │   │   └── ScreenCaptureService.kt
│   │       │   │   ├── overlay/
│   │       │   │   │   └── OverlayManager.kt
│   │       │   │   ├── ml/
│   │       │   │   │   ├── RustMLBridge.kt
│   │       │   │   │   └── ClassificationResult.kt
│   │       │   │   ├── permissions/
│   │       │   │   │   └── PermissionManager.kt
│   │       │   │   ├── processing/
│   │       │   │   │   ├── FrameProcessor.kt
│   │       │   │   │   └── FilterAction.kt
│   │       │   │   ├── policy/
│   │       │   │   │   └── PolicyEngine.kt
│   │       │   │   ├── storage/
│   │       │   │   │   ├── AppDatabase.kt
│   │       │   │   │   ├── FilterEvent.kt
│   │       │   │   │   └── EventLogger.kt
│   │       │   │   ├── context/
│   │       │   │   │   ├── AppContextService.kt
│   │       │   │   │   └── MediaContextService.kt
│   │       │   │   └── ui/
│   │       │   │       ├── compose/
│   │       │   │       │   ├── MainScreen.kt
│   │       │   │       │   ├── SettingsScreen.kt
│   │       │   │       │   └── PrivacyDashboard.kt
│   │       │   │       └── theme/
│   │       │   │           └── Theme.kt
│   │       │   └── res/
│   │       │       ├── layout/
│   │       │       │   └── overlay_block_screen.xml
│   │       │       ├── values/
│   │       │       │   ├── strings.xml
│   │       │       │   └── themes.xml
│   │       │       ├── drawable/
│   │       │       │   ├── ic_shield.xml
│   │       │       │   └── ic_stop.xml
│   │       │       └── xml/
│   │       │           ├── accessibility_service_config.xml
│   │       │           └── notification_listener_config.xml
│   │       ├── androidTest/
│   │       │   └── java/com/thesis/pavlova/
│   │       │       ├── PerformanceBenchmarkTest.kt
│   │       │       ├── AccuracyTest.kt
│   │       │       └── PrivacyComplianceTest.kt
│   │       └── test/
│   │           └── java/com/thesis/pavlova/
│   │               ├── PolicyEngineTest.kt
│   │               └── FrameProcessorTest.kt
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
│
├── rust/                              # Rust native library
│   ├── Cargo.toml
│   ├── Cargo.lock
│   ├── build.rs
│   └── src/
│       ├── lib.rs
│       ├── jni_bridge.rs
│       ├── inference/
│       │   ├── mod.rs
│       │   ├── tflite.rs
│       │   ├── onnx.rs
│       │   └── tract.rs
│       ├── image/
│       │   ├── mod.rs
│       │   ├── conversion.rs
│       │   ├── resize.rs
│       │   └── effects.rs
│       ├── policy/
│       │   ├── mod.rs
│       │   ├── engine.rs
│       │   └── rules.rs
│       └── utils/
│           ├── mod.rs
│           ├── pool.rs
│           └── metrics.rs
│
├── models/                            # ML models
│   ├── nsfw_classifier.tflite
│   ├── nsfw_classifier_int8.tflite   # Quantized version
│   ├── model_metadata.json
│   └── README.md                      # Model source, license, training details
│
├── scripts/                           # Automation scripts
│   ├── collect_performance_data.py
│   ├── analyze_battery.py
│   ├── run_benchmarks.sh
│   └── build_all.sh
│
├── docs/                              # Additional documentation
│   ├── API.md                         # JNI API documentation
│   ├── USER_GUIDE.md                  # End-user documentation
│   ├── CONTRIBUTING.md                # For open-source contributors
│   └── diagrams/
│       ├── architecture.png
│       ├── data_flow.png
│       └── permission_flow.png
│
├── thesis/                            # Thesis-specific materials
│   ├── latex/                         # LaTeX source (if applicable)
│   ├── figures/
│   ├── data/
│   │   ├── performance_results.csv
│   │   ├── accuracy_results.csv
│   │   └── battery_logs.csv
│   └── user_study/
│       ├── consent_form.pdf
│       ├── sus_questionnaire.pdf
│       └── results_analysis.ipynb
│
└── .github/                           # CI/CD (if using GitHub)
    └── workflows/
        ├── android_build.yml
        ├── rust_tests.yml
        └── release.yml
```

---

## Setup Instructions

### Prerequisites

#### 1. Software Requirements

- **Android Studio**: Jellyfish (2024.1) or later
- **JDK**: 17 or 21
- **Rust Toolchain**: 1.75+ 
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  ```
- **Android NDK**: r26
  - Install via Android Studio SDK Manager
- **Cargo NDK**: 
  ```bash
  cargo install cargo-ndk
  ```
- **Python**: 3.8+ (for scripts)

#### 2. Android Device
- Physical device with Android 8.0+ (API 26+)
- Recommended: Android 14+ for testing latest features
- Enable Developer Mode and USB Debugging

---

### Step-by-Step Setup

#### Step 1: Clone Repository

```bash
git clone https://github.com/yourusername/pavlova.git
cd pavlova
```

#### Step 2: Set Up Rust

```bash
# Add Android targets
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android

# Set NDK path (adjust path as needed)
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/26.1.10909125
```

#### Step 3: Build Rust Library

```bash
cd rust
cargo ndk --target aarch64-linux-android --platform 26 build --release
cd ..
```

**Expected Output**: `target/aarch64-linux-android/release/libpavlova_rust.so`

#### Step 4: Open Android Project

```bash
cd android
# Open in Android Studio or build via command line
./gradlew assembleDebug
```

#### Step 5: Add ML Model

1. Download or train NSFW classifier model (TFLite format)
2. Place in `android/app/src/main/assets/models/nsfw_classifier.tflite`
3. Update model path in code if needed

**Sample Model Sources**:
- [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) (open-source NSFW detector)
- Custom training with TensorFlow/Keras + TFLite conversion

#### Step 6: Install on Device

```bash
# Via Android Studio: Run configuration
# Or via command line:
./gradlew installDebug
adb shell am start -n com.pavlova/.MainActivity
```

---

## Development Workflow

### Building

**Full Build** (Rust + Android):
```bash
./scripts/build_all.sh
```

**Rust Only**:
```bash
cd rust
cargo build --release
```

**Android Only** (assumes Rust already built):
```bash
cd android
./gradlew assembleRelease
```

---

### Testing

**Rust Unit Tests**:
```bash
cd rust
cargo test
```

**Rust Benchmarks**:
```bash
cd rust
cargo bench
```

**Android Unit Tests**:
```bash
cd android
./gradlew test
```

**Android Instrumentation Tests** (requires device):
```bash
./gradlew connectedAndroidTest
```

**Performance Benchmarks**:
```bash
./gradlew benchmark
# Or use Android Studio Profiler
```

---

### Debugging

**Rust Debugging**:
```bash
# Build with debug symbols
cargo ndk --target aarch64-linux-android --platform 26 build

# View logs
adb logcat | grep PavlovaRust
```

**Android Debugging**:
- Use Android Studio debugger
- Logcat filtering: `adb logcat | grep Pavlova`

**JNI Debugging**:
```bash
# Check for JNI errors
adb logcat | grep JNI
```

---

## Configuration

### App Configuration

**File**: `android/app/src/main/res/values/config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Frame capture settings -->
    <integer name="default_fps">10</integer>
    <integer name="min_fps">5</integer>
    <integer name="max_fps">15</integer>
    
    <!-- ML settings -->
    <string name="model_filename">models/nsfw_classifier.tflite</string>
    <item name="confidence_threshold" type="dimen" format="float">0.75</item>
    
    <!-- Privacy settings -->
    <integer name="event_retention_days">30</integer>
    <bool name="enable_analytics">false</bool>
    
    <!-- Performance settings -->
    <bool name="enable_gpu_acceleration">true</bool>
    <bool name="enable_nnapi">true</bool>
</resources>
```

---

### Build Variants

**File**: `android/app/build.gradle.kts`

```kotlin
buildTypes {
    debug {
        applicationIdSuffix = ".debug"
        isDebuggable = true
        isMinifyEnabled = false
    }
    
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
    
    create("benchmark") {
        initWith(getByName("release"))
        applicationIdSuffix = ".benchmark"
        isDebuggable = true
        // Optimizations on, debugging enabled for profiling
    }
}
```

---

## ML Model Management

### Model Integration Checklist

- [ ] Model in TFLite format
- [ ] Input shape documented (typically 224x224x3)
- [ ] Output classes defined (safe, adult, violence, etc.)
- [ ] Quantization applied (INT8 for speed)
- [ ] Checksum calculated and hardcoded
- [ ] License compatible (many NSFW models are open-source)

### Model Metadata

**File**: `models/model_metadata.json`

```json
{
  "name": "NSFW Classifier",
  "version": "1.0.0",
  "framework": "TensorFlow Lite",
  "input_shape": [1, 224, 224, 3],
  "input_type": "float32",
  "mean": [127.5, 127.5, 127.5],
  "std": [127.5, 127.5, 127.5],
  "output_classes": ["safe", "adult", "violence", "gore", "hate"],
  "quantization": "int8",
  "source": "https://github.com/GantMan/nsfw_model",
  "license": "MIT",
  "sha256": "abc123..."
}
```

---

## Performance Optimization Tips

### 1. Rust Optimizations

**Enable All Optimizations** in `Cargo.toml`:
```toml
[profile.release]
opt-level = 3
lto = "fat"
codegen-units = 1
panic = "abort"
strip = true

[profile.release.package."*"]
opt-level = 3
```

**Use SIMD**:
```rust
#[cfg(target_arch = "aarch64")]
use std::arch::aarch64::*;
```

### 2. Android Optimizations

**Enable R8 Full Mode** in `gradle.properties`:
```properties
android.enableR8.fullMode=true
```

**ProGuard Aggressive Optimization**:
```proguard
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
```

### 3. ML Optimizations

- Use **INT8 quantization** (4x smaller, faster)
- Enable **NNAPI delegate** for hardware acceleration
- Consider **GPU delegate** for supported operations
- Batch size = 1 for lowest latency

---

## Troubleshooting

### Common Issues

#### Issue: Rust library not found

**Symptom**: `UnsatisfiedLinkError: couldn't find "libpavlova_rust.so"`

**Solution**:
```bash
# Ensure Rust built for correct architecture
cd rust
cargo ndk --target aarch64-linux-android --platform 26 build --release

# Check library exists
ls ../android/app/src/main/jniLibs/arm64-v8a/

# Rebuild Android
cd ../android
./gradlew clean assembleDebug
```

#### Issue: MediaProjection consent not working

**Symptom**: Screen capture doesn't start after granting permission

**Solution**:
- Ensure foreground service declared in manifest
- Check notification channel created
- Verify result code from MediaProjection intent

#### Issue: High battery drain

**Symptom**: Battery drains faster than expected

**Solution**:
- Enable adaptive FPS
- Reduce default FPS (5 instead of 10)
- Check for memory leaks (use Android Profiler)
- Ensure frames are released properly

#### Issue: App crashes on low-end devices

**Symptom**: OutOfMemoryError on devices with < 4GB RAM

**Solution**:
- Reduce frame resolution (720p instead of 1080p)
- Implement memory pooling
- Call `System.gc()` strategically (though use sparingly)

---

## Contributing

See `CONTRIBUTING.md` for guidelines.

**Quick Checklist**:
- [ ] Code follows Kotlin/Rust style guides
- [ ] Unit tests added for new features
- [ ] Documentation updated
- [ ] No new privacy concerns introduced
- [ ] Performance impact measured

---

## License

**Code**: MIT License (or GPL, depending on ML model)

**ML Model**: Check individual model licenses

**Thesis Content**: Copyright [Your Name], [Year]. All rights reserved.

---

## Contact & Support

- **GitHub Issues**: [repo URL]/issues
- **Email**: [your email]
- **Thesis Supervisor**: [supervisor email]

---

## Acknowledgments

- **TensorFlow Team**: TFLite framework
- **GantMan**: NSFW model (if used)
- **Rust Android Working Group**: JNI tooling
- **Android Team**: MediaProjection API
- **[Your University]**: Research support

---

## Citing This Work

If you use Pavlova in your research, please cite:

```bibtex
@mastersthesis{pavlova2026,
  title={Pavlova: On-Device Screen-Safety System for Android},
  author={[Your Name]},
  year={2026},
  school={[Your University]},
  type={Master's Thesis}
}
```

---

**End of Project Structure Guide**
