# Pavlova Android App - Quick Start Guide

## 🚀 Quick Setup

### Prerequisites

Before you begin, ensure you have installed:

1. **Android Studio** (Electric Eel or later)
   - Download: https://developer.android.com/studio

2. **Rust** (1.75 or later)
   ```powershell
   # Install using rustup
   winget install --id Rustlang.Rustup
   ```

3. **Android NDK** (r26 or later)
   - Install via Android Studio SDK Manager
   - Or download: https://developer.android.com/ndk/downloads

4. **cargo-ndk**
   ```powershell
   cargo install cargo-ndk
   ```

5. **Rust Android targets**
   ```powershell
   rustup target add aarch64-linux-android
   rustup target add armv7-linux-androideabi
   rustup target add x86_64-linux-android
   ```

### Step 1: Clone/Open Project

```powershell
cd c:\Users\danutiurascu\work\personal\Android\pavlova
```

### Step 2: Build Rust Library

```powershell
cd rust

# Build for all Android architectures
cargo ndk --target aarch64-linux-android --target armv7-linux-androideabi --target x86_64-linux-android --platform 26 -- build --release

cd ..
```

### Step 3: Copy Native Libraries

The Gradle build will automatically copy Rust libraries, but you can also do it manually:

```powershell
# Copy to jniLibs
New-Item -ItemType Directory -Force -Path "android\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force -Path "android\app\src\main\jniLibs\armeabi-v7a"
New-Item -ItemType Directory -Force -Path "android\app\src\main\jniLibs\x86_64"

Copy-Item "rust\target\aarch64-linux-android\release\libpavlova_core.so" "android\app\src\main\jniLibs\arm64-v8a\"
Copy-Item "rust\target\armv7-linux-androideabi\release\libpavlova_core.so" "android\app\src\main\jniLibs\armeabi-v7a\"
Copy-Item "rust\target\x86_64-linux-android\release\libpavlova_core.so" "android\app\src\main\jniLibs\x86_64\"
```

### Step 4: Add ML Model

Place your TensorFlow Lite model in `android/app/src/main/assets/`:

```powershell
New-Item -ItemType Directory -Force -Path "android\app\src\main\assets"

# Copy your model (download or train separately)
# Copy-Item "path\to\your\nsfw_mobilenet_v2_140_224_int8.tflite" "android\app\src\main\assets\"
```

**Note**: You'll need to obtain or train an NSFW classification model. For testing, you can use a placeholder model or disable ML checks temporarily.

### Step 5: Open in Android Studio

1. Launch Android Studio
2. Open project: `c:\Users\danutiurascu\work\personal\Android\pavlova\android`
3. Wait for Gradle sync to complete
4. Connect Android device or start emulator
5. Click **Run** ▶️

## 📱 Testing on Device

### Requirements
- Android 8.0 (API 26) or higher
- Physical device recommended (MediaProjection doesn't work well on emulators)

### Grant Permissions

When you first run the app:

1. Click "Start Protection"
2. Grant **Overlay Permission** (Settings will open)
3. Grant **Notification Permission** (Android 13+)
4. Grant **MediaProjection Permission** (screen capture consent)

## 🔧 Development Commands

### Build Rust Library (Debug)
```powershell
cd rust
cargo ndk --target aarch64-linux-android --platform 26 -- build
```

### Build Rust Library (Release)
```powershell
cd rust
cargo ndk --target aarch64-linux-android --platform 26 -- build --release
```

### Run Rust Tests
```powershell
cd rust
cargo test
```

### Build Android App
```powershell
cd android
.\gradlew assembleDebug
```

### Install on Device
```powershell
cd android
.\gradlew installDebug
```

### View Logs
```powershell
adb logcat -s PavlovaApplication ScreenCaptureService FrameProcessor RustMLBridge PavlovaRust
```

## 🐛 Troubleshooting

### Issue: Native library not found

**Solution**: Rebuild Rust library and ensure .so files are in jniLibs:
```powershell
cd rust
cargo ndk --target aarch64-linux-android --platform 26 -- build --release
cd ..
# Then rebuild Android app
```

### Issue: ML model not found

**Solution**: Add a placeholder model or modify code temporarily:
```kotlin
// In RustMLBridge.kt, comment out model extraction for testing:
// val modelFile = extractModelFromAssets(context)
val modelFile = File(context.filesDir, "dummy_model.tflite")
modelFile.createNewFile()
```

### Issue: MediaProjection consent required on every launch (Android 14+)

**Expected behavior**: This is by design for Android 14+ security. Users must grant consent each session.

### Issue: Gradle sync fails

**Solution**: 
1. Update NDK path in `gradle.properties`
2. Check that all Rust targets are installed: `rustup target list | Select-String android`
3. Verify cargo-ndk: `cargo ndk --version`

## 📂 Project Structure

```
pavlova/
├── android/              # Android app
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/pavlova/
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── rust/                 # Rust library
│   ├── src/
│   │   ├── lib.rs
│   │   ├── image.rs
│   │   ├── inference.rs
│   │   └── utils.rs
│   └── Cargo.toml
└── docs/                 # Technical documentation
```

## ✅ Next Steps

1. **Obtain ML Model**: Download or train an NSFW classification model (TFLite format)
2. **Implement TFLite Integration**: Complete the inference code in `rust/src/inference.rs`
3. **Test on Device**: Run on physical Android device
4. **Optimize Performance**: Profile and optimize for target <100ms latency
5. **Add Features**: Implement optional services (Accessibility, NotificationListener)

## 📚 Documentation

- [ARCHITECTURE.md](../ARCHITECTURE.md) - System design
- [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md) - Detailed implementation guide
- [RUST_LIBRARY_SPEC.md](../RUST_LIBRARY_SPEC.md) - Rust module specifications
- [PROJECT_STRUCTURE.md](../PROJECT_STRUCTURE.md) - Complete project structure

## 🆘 Getting Help

**Build Issues**: Check logs with `.\gradlew build --stacktrace`

**Rust Issues**: Run `cargo check` in the rust/ directory

**Runtime Errors**: Use `adb logcat` to view detailed logs

---

**Status**: ✅ Basic project structure complete! Ready for development.

**Last Updated**: 2026-02-25
