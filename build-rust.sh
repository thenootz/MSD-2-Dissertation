#!/bin/bash
# Build script for Pavlova - macOS/Linux
# Run this to build the Rust library for Android

set -e

echo "Building Pavlova Rust Library..."

# Check if cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Error: cargo-ndk not found. Install it with:"
    echo "  cargo install cargo-ndk"
    exit 1
fi

# Check if Rust Android targets are installed
TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")
for target in "${TARGETS[@]}"; do
    if ! rustup target list --installed | grep -q "$target"; then
        echo "Installing target: $target"
        rustup target add "$target"
    fi
done

# Build Rust library
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/rust"

echo ""
echo "Building for Android (release mode)..."

cargo ndk \
    --target aarch64-linux-android \
    --target armv7-linux-androideabi \
    --target x86_64-linux-android \
    --platform 26 \
    -- build --release

echo ""
echo "✅ Rust library built successfully!"

# Copy to jniLibs
echo ""
echo "Copying native libraries to jniLibs..."

JNILIBS_PATH="../android/app/src/main/jniLibs"

mkdir -p "$JNILIBS_PATH/arm64-v8a"
mkdir -p "$JNILIBS_PATH/armeabi-v7a"
mkdir -p "$JNILIBS_PATH/x86_64"

cp "target/aarch64-linux-android/release/libpavlova_core.so" "$JNILIBS_PATH/arm64-v8a/"
cp "target/armv7-linux-androideabi/release/libpavlova_core.so" "$JNILIBS_PATH/armeabi-v7a/"
cp "target/x86_64-linux-android/release/libpavlova_core.so" "$JNILIBS_PATH/x86_64/"

echo "✅ Native libraries copied!"

echo ""
echo "📦 Library sizes:"
find "$JNILIBS_PATH" -name "*.so" -exec sh -c 'echo "  {}: $(du -h "{}" | cut -f1)"' \;

echo ""
echo "🎉 Build complete! Now open the Android project in Android Studio."

cd "$SCRIPT_DIR"
