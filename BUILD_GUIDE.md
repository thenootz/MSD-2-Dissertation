# Pavlova — Build & Run Guide

> Step-by-step instructions to clone, build, and run the Pavlova app on your local machine using Android Studio.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Clone the Repository](#2-clone-the-repository)
3. [Verify Your Environment](#3-verify-your-environment)
4. [Download & Convert the ML Model](#4-download--convert-the-ml-model)
5. [Build the Rust Native Library](#5-build-the-rust-native-library)
6. [Open the Project in Android Studio](#6-open-the-project-in-android-studio)
7. [Build & Run the App](#7-build--run-the-app)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. Prerequisites

Install **all** of the following before continuing:

### 1.1 Android Studio

- Download and install [Android Studio](https://developer.android.com/studio) (Hedgehog 2023.1+ recommended)
- During setup, ensure these components are installed:
  - **Android SDK** (API 35)
  - **Android SDK Build-Tools** (35.x)
  - **Android NDK** (any recent version, e.g. 26.x or 27.x)
  - **CMake** (optional, not strictly required but useful)
- After install, verify `ANDROID_HOME` is set:
  - **Windows**: `ANDROID_HOME` → typically `C:\Users\<you>\AppData\Local\Android\Sdk`
  - Add `%ANDROID_HOME%\platform-tools` to your `PATH`

### 1.2 Java 17+

Android Studio bundles a JDK, but verify:

```bash
java -version
# Expected: openjdk version "17.x.x" or higher
```

If missing, install [Eclipse Temurin JDK 17](https://adoptium.net/) or use Android Studio's bundled JDK.

### 1.3 Rust Toolchain

Install Rust via [rustup](https://rustup.rs/):

```bash
# Windows: download and run rustup-init.exe from https://rustup.rs
# macOS/Linux:
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Verify:
```bash
rustc --version    # e.g. rustc 1.78.0
cargo --version    # e.g. cargo 1.78.0
```

### 1.4 Android Cross-Compilation Targets

Add the Rust targets for Android:

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
```

### 1.5 cargo-ndk

Install the `cargo-ndk` helper to simplify Android NDK cross-compilation:

```bash
cargo install cargo-ndk
```

### 1.6 Python 3.8+ (for model conversion)

```bash
python --version   # or python3 --version
# Expected: Python 3.8+
```

> **Note**: Python 3.13+ may not support TensorFlow, but the conversion script uses `tflite2onnx` which works on any Python 3.8+.

---

## 2. Clone the Repository

```bash
git clone https://github.com/<your-username>/pavlova.git
cd pavlova
```

---

## 3. Verify Your Environment

Run the environment check script to confirm everything is installed:

**Windows (PowerShell)**:
```powershell
.\check-env.ps1
```

**macOS / Linux (bash)**:
```bash
# Manual checks:
rustc --version
cargo-ndk --version
java -version
echo $ANDROID_HOME
ls $ANDROID_HOME/ndk/
```

You should see all green checkmarks. Fix any reported issues before proceeding.

---

## 4. Download & Convert the ML Model

The ONNX model file is **not included in the repository** (gitignored). You must generate it locally.

### 4.1 Create a Python Virtual Environment (recommended)

```bash
python -m venv .venv

# Activate:
# Windows PowerShell:
.\.venv\Scripts\Activate.ps1
# Windows CMD:
.\.venv\Scripts\activate.bat
# macOS/Linux:
source .venv/bin/activate
```

### 4.2 Install Python Dependencies

```bash
pip install tflite2onnx onnx
```

### 4.3 Run the Conversion Script

```bash
python scripts/convert_model.py
```

This will:
1. Download `nsfw_mobilenet_v2_140_224.zip` (~135 MB) from GitHub Releases
2. Extract the TFLite model
3. Convert it to ONNX format
4. Copy `nsfw_mobilenet_v2_140_224.onnx` to `android/app/src/main/assets/`

**Verify** the model file exists:
```bash
# Windows:
dir android\app\src\main\assets\nsfw_mobilenet_v2_140_224.onnx
# macOS/Linux:
ls -la android/app/src/main/assets/nsfw_mobilenet_v2_140_224.onnx
# Expected: ~23 MB file
```

---

## 5. Build the Rust Native Library

### 5.1 Set Up NDK Path

`cargo-ndk` needs to find the Android NDK. It reads from the `ANDROID_NDK_HOME` environment variable or auto-detects from `ANDROID_HOME/ndk/<version>`.

If auto-detection fails, set it explicitly:

**Windows PowerShell**:
```powershell
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\<version>"
# Example:
# $env:ANDROID_NDK_HOME = "C:\Users\you\AppData\Local\Android\Sdk\ndk\27.0.12077973"
```

**macOS/Linux**:
```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
```

### 5.2 Build with the Script

**Windows (PowerShell)**:
```powershell
.\build-rust.ps1
```

**macOS / Linux** (manual):
```bash
cd rust

cargo ndk \
    --target aarch64-linux-android \
    --target armv7-linux-androideabi \
    --target x86_64-linux-android \
    --platform 26 \
    -- build --release

# Copy .so files to jniLibs
mkdir -p ../android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}
cp target/aarch64-linux-android/release/libpavlova_core.so ../android/app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libpavlova_core.so ../android/app/src/main/jniLibs/armeabi-v7a/
cp target/x86_64-linux-android/release/libpavlova_core.so ../android/app/src/main/jniLibs/x86_64/

cd ..
```

**Expected output**:
```
✅ Rust library built successfully!
✅ Native libraries copied!

📦 Library sizes:
  arm64-v8a/libpavlova_core.so: ~X.XX MB
  armeabi-v7a/libpavlova_core.so: ~X.XX MB
  x86_64/libpavlova_core.so: ~X.XX MB
```

> **First build** takes 3-10 minutes (downloads and compiles ~200 crates). Subsequent builds are faster.

### 5.3 Verify the Native Libraries

```bash
# Windows:
dir android\app\src\main\jniLibs\arm64-v8a\libpavlova_core.so
# macOS/Linux:
ls -la android/app/src/main/jniLibs/*/libpavlova_core.so
```

You should see `.so` files in all three ABI directories.

---

## 6. Open the Project in Android Studio

1. Open Android Studio
2. Select **File → Open**
3. Navigate to and select the **`android/`** folder (not the repo root)
4. Click **OK**

### 6.1 First-Time Gradle Sync

Android Studio will:
- Download the Gradle wrapper (version 8.2)
- Download all Gradle dependencies
- Perform a project sync

> This may take 2-5 minutes on first run. Watch the bottom status bar for progress.

If Gradle sync fails with a wrapper error:
1. Go to **File → Project Structure → Project**
2. Set **Gradle Version** to `8.2`
3. Set **Android Gradle Plugin Version** to `8.2.0`
4. Click **Apply** → **OK**
5. Re-sync: **File → Sync Project with Gradle Files**

### 6.2 Verify SDK Configuration

If prompted about missing SDK components:
1. Go to **File → Project Structure → Modules**
2. Ensure **Compile SDK** = 35
3. Ensure **Min SDK** = 26
4. Open **SDK Manager** (Tools → SDK Manager) and install any missing components

---

## 7. Build & Run the App

### 7.1 Connect a Device or Start an Emulator

**Physical Device (recommended)**:
- Enable **Developer Options** on your Android device
- Enable **USB Debugging**
- Connect via USB
- Trust the computer when prompted

**Emulator**:
- Open **Device Manager** in Android Studio
- Create a virtual device (Pixel 6+ recommended, API 26+)
- Start the emulator

> **Note**: The x86_64 native library is included for emulator support. If using an ARM emulator, ensure `arm64-v8a` is built.

### 7.2 Build & Run

1. Select your device/emulator from the device dropdown (top toolbar)
2. Click the **Run** button (green triangle ▶️) or press `Shift+F10`
3. Wait for the build and installation to complete

**First build** may take 2-5 minutes. You'll see the Pavlova app launch on your device.

### 7.3 Grant Permissions

On first launch, the app will request several permissions:
- **Overlay permission** (draw over other apps)
- **Accessibility service** (optional, for content awareness)
- **Notification access** (optional)

Follow the in-app prompts to enable the required permissions in system settings.

---

## 8. Troubleshooting

### Rust build fails: "linker not found"

**Cause**: `cargo-ndk` can't find the NDK toolchain.

**Fix**:
```bash
# Verify NDK is installed
ls $ANDROID_HOME/ndk/

# Set explicitly
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
```

On Windows, you can also set it in Android Studio: **File → Settings → Appearance → System Settings → Android SDK → SDK Tools → NDK (Side by side)** — ensure it's checked and installed.

---

### Gradle sync fails: "Could not find com.android.tools.build:gradle:8.2.0"

**Fix**: Ensure you have internet access and the Google Maven repository is reachable. Check `android/settings.gradle.kts` has:
```kotlin
repositories {
    google()
    mavenCentral()
}
```

---

### App crashes at launch: "java.lang.UnsatisfiedLinkError: libpavlova_core.so"

**Cause**: Native `.so` files are missing from `jniLibs/`.

**Fix**: Re-run the Rust build:
```powershell
.\build-rust.ps1
```

Then rebuild and redeploy the app in Android Studio.

---

### App crashes: "Model file not found" or ONNX load error

**Cause**: The ML model file is missing from `assets/`.

**Fix**: Run the model conversion script:
```bash
python scripts/convert_model.py
```

Verify the file exists at `android/app/src/main/assets/nsfw_mobilenet_v2_140_224.onnx`.

---

### Emulator: "No matching ABI" or "INSTALL_FAILED_NO_MATCHING_ABIS"

**Cause**: The emulator architecture doesn't have a matching native library.

**Fix**: Use an **x86_64** emulator (most common on Intel/AMD hosts) or an **ARM64** emulator. Ensure the corresponding target was built in step 5.

---

### Python script fails: "ModuleNotFoundError: No module named 'tflite2onnx'"

**Fix**:
```bash
pip install tflite2onnx onnx
```

If using a virtual environment, make sure it's activated first.

---

### Compilation warning: "dead_code" in Rust

These warnings are expected and harmless. Some utility functions (`yuv420_to_rgba`, `Timer`) are prepared for future use but not currently called.

---

## Quick Reference — Full Build Sequence

For those who want the commands without explanations:

```bash
# 1. Clone
git clone https://github.com/<your-username>/pavlova.git
cd pavlova

# 2. Verify environment
# Windows:
powershell -ExecutionPolicy Bypass -File .\check-env.ps1

# 3. Convert ML model
python -m venv .venv
# Windows:
.\.venv\Scripts\Activate.ps1
pip install tflite2onnx onnx
python scripts/convert_model.py

# 4. Build Rust native library
# Windows:
powershell -ExecutionPolicy Bypass -File .\build-rust.ps1

# 5. Open android/ in Android Studio → Sync → Run
```

---

## System Requirements Summary

| Component            | Minimum Version | Recommended          |
|----------------------|-----------------|----------------------|
| Android Studio       | Hedgehog 2023.1 | Latest stable        |
| Java (JDK)           | 17              | 17+                  |
| Android SDK          | API 26          | API 35               |
| Android NDK          | 25.x            | 26.x or 27.x        |
| Rust                 | 1.70+           | Latest stable        |
| cargo-ndk            | 3.x             | Latest               |
| Python               | 3.8+            | 3.10-3.12            |
| Disk Space           | ~5 GB           | ~10 GB               |
| RAM                  | 8 GB            | 16 GB                |

---

*Last updated: March 2026*
