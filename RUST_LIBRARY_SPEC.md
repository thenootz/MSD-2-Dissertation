# Pavlova Rust Library - Complete Specification

## Overview

The Rust library (`pavlova_rust`) provides high-performance image processing, ML inference, and policy evaluation capabilities to the Android application through JNI.

---

## Module Structure

```
rust/
├── Cargo.toml
├── build.rs
└── src/
    ├── lib.rs              # Library entry point
    ├── jni_bridge.rs       # JNI interface layer
    ├── inference/
    │   ├── mod.rs          # Inference engine coordination
    │   ├── tflite.rs       # TensorFlow Lite backend
    │   ├── onnx.rs         # ONNX Runtime backend
    │   └── tract.rs        # Tract (pure Rust) backend
    ├── image/
    │   ├── mod.rs
    │   ├── conversion.rs   # YUV↔RGB, format conversions
    │   ├── resize.rs       # High-performance resizing
    │   └── effects.rs      # Blur, pixelation, filters
    ├── policy/
    │   ├── mod.rs
    │   ├── engine.rs       # Rule evaluation logic
    │   └── rules.rs        # Rule definitions
    └── utils/
        ├── mod.rs
        ├── pool.rs         # Memory pool for frame buffers
        └── metrics.rs      # Performance metrics
```

---

## Complete lib.rs

**File**: `rust/src/lib.rs`

```rust
//! Pavlova Rust Library
//! 
//! Provides ML inference, image processing, and policy evaluation
//! for the Pavlova Android screen safety application.

pub mod jni_bridge;
pub mod inference;
pub mod image;
pub mod policy;
pub mod utils;

use android_logger::Config;
use log::LevelFilter;

/// Initialize the Rust library
/// Called once from Android when app starts
#[no_mangle]
pub extern "C" fn pavlova_init() {
    android_logger::init_once(
        Config::default()
            .with_min_level(LevelFilter::Info)
            .with_tag("PavlovaRust")
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("debug,pavlova_rust=trace")
                    .build(),
            ),
    );
    
    log::info!("Pavlova Rust library initialized");
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_library_init() {
        pavlova_init();
        log::info!("Test: library initialized successfully");
    }
}
```

---

## Image Conversion Module

**File**: `rust/src/image/conversion.rs`

```rust
//! Image format conversion utilities
//! 
//! Handles YUV→RGB conversion for Android camera frames
//! and other color space transformations.

use std::error::Error;

/// Convert YUV_420_888 to RGBA
/// 
/// Android MediaProjection typically provides frames in YUV format.
/// This function converts to RGBA for ML processing and overlay rendering.
pub fn yuv420_to_rgba(
    y_plane: &[u8],
    u_plane: &[u8],
    v_plane: &[u8],
    width: u32,
    height: u32,
    y_row_stride: u32,
    uv_row_stride: u32,
    uv_pixel_stride: u32,
) -> Result<Vec<u8>, Box<dyn Error>> {
    
    let pixel_count = (width * height) as usize;
    let mut rgba = vec![0u8; pixel_count * 4];
    
    for y in 0..height {
        for x in 0..width {
            let y_index = (y * y_row_stride + x) as usize;
            let uv_row = y / 2;
            let uv_col = x / 2;
            let uv_index = (uv_row * uv_row_stride + uv_col * uv_pixel_stride) as usize;
            
            let y_value = y_plane[y_index] as i32;
            let u_value = u_plane[uv_index] as i32 - 128;
            let v_value = v_plane[uv_index] as i32 - 128;
            
            // YUV to RGB conversion (BT.709 coefficients)
            let r = (y_value + (1.5748 * v_value as f32) as i32).clamp(0, 255) as u8;
            let g = (y_value - (0.1873 * u_value as f32) as i32 - (0.4681 * v_value as f32) as i32).clamp(0, 255) as u8;
            let b = (y_value + (1.8556 * u_value as f32) as i32).clamp(0, 255) as u8;
            
            let rgba_index = ((y * width + x) * 4) as usize;
            rgba[rgba_index] = r;
            rgba[rgba_index + 1] = g;
            rgba[rgba_index + 2] = b;
            rgba[rgba_index + 3] = 255; // Alpha
        }
    }
    
    Ok(rgba)
}

/// Convert NV21 (YUV 420 semi-planar) to RGBA
/// Common format from Android Camera2 API
pub fn nv21_to_rgba(
    data: &[u8],
    width: u32,
    height: u32,
) -> Result<Vec<u8>, Box<dyn Error>> {
    
    let pixel_count = (width * height) as usize;
    let mut rgba = vec![0u8; pixel_count * 4];
    
    let y_size = (width * height) as usize;
    
    for y in 0..height {
        for x in 0..width {
            let y_index = (y * width + x) as usize;
            let uv_index = y_size + (y / 2) * width + (x & !1);
            
            let y_value = data[y_index] as i32;
            let v_value = data[uv_index] as i32 - 128;
            let u_value = data[uv_index + 1] as i32 - 128;
            
            let r = (y_value + (1.5748 * v_value as f32) as i32).clamp(0, 255) as u8;
            let g = (y_value - (0.1873 * u_value as f32) as i32 - (0.4681 * v_value as f32) as i32).clamp(0, 255) as u8;
            let b = (y_value + (1.8556 * u_value as f32) as i32).clamp(0, 255) as u8;
            
            let rgba_index = ((y * width + x) * 4) as usize;
            rgba[rgba_index] = r;
            rgba[rgba_index + 1] = g;
            rgba[rgba_index + 2] = b;
            rgba[rgba_index + 3] = 255;
        }
    }
    
    Ok(rgba)
}

/// Convert RGBA to RGB (drop alpha channel)
pub fn rgba_to_rgb(rgba: &[u8]) -> Vec<u8> {
    rgba.chunks(4)
        .flat_map(|chunk| &chunk[0..3])
        .copied()
        .collect()
}

/// Normalize RGB/RGBA values from [0, 255] to [0.0, 1.0]
pub fn normalize_u8_to_f32(data: &[u8]) -> Vec<f32> {
    data.iter().map(|&byte| byte as f32 / 255.0).collect()
}

/// Denormalize from [0.0, 1.0] to [0, 255]
pub fn denormalize_f32_to_u8(data: &[f32]) -> Vec<u8> {
    data.iter().map(|&value| (value * 255.0).clamp(0.0, 255.0) as u8).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_rgba_to_rgb() {
        let rgba = vec![255, 0, 0, 255, 0, 255, 0, 255];
        let rgb = rgba_to_rgb(&rgba);
        assert_eq!(rgb, vec![255, 0, 0, 0, 255, 0]);
    }
    
    #[test]
    fn test_normalization() {
        let data = vec![0, 128, 255];
        let normalized = normalize_u8_to_f32(&data);
        assert_eq!(normalized[0], 0.0);
        assert!((normalized[1] - 0.502).abs() < 0.01);
        assert_eq!(normalized[2], 1.0);
        
        let denormalized = denormalize_f32_to_u8(&normalized);
        assert_eq!(denormalized, data);
    }
}
```

---

## Policy Engine

**File**: `rust/src/policy/mod.rs`

```rust
//! Policy engine for content filtering decisions
//! 
//! Combines ML classification results with user-defined rules
//! and context information to make final filtering decisions.

pub mod engine;
pub mod rules;

pub use engine::PolicyEngine;
pub use rules::{FilterRule, FilterAction, ContentCategory};
```

**File**: `rust/src/policy/engine.rs`

```rust
use super::rules::*;
use crate::inference::ClassificationResult;
use std::collections::HashMap;

pub struct PolicyEngine {
    global_rules: Vec<FilterRule>,
    app_specific_rules: HashMap<String, Vec<FilterRule>>,
    default_action: FilterAction,
}

impl PolicyEngine {
    pub fn new() -> Self {
        Self {
            global_rules: Vec::new(),
            app_specific_rules: HashMap::new(),
            default_action: FilterAction::Allow,
        }
    }
    
    pub fn add_rule(&mut self, rule: FilterRule) {
        if let Some(app_package) = &rule.app_package {
            self.app_specific_rules
                .entry(app_package.clone())
                .or_insert_with(Vec::new)
                .push(rule);
        } else {
            self.global_rules.push(rule);
        }
    }
    
    pub fn evaluate(
        &self,
        classification: &ClassificationResult,
        app_package: Option<&str>,
    ) -> FilterDecision {
        
        // Check app-specific rules first
        if let Some(package) = app_package {
            if let Some(rules) = self.app_specific_rules.get(package) {
                for rule in rules {
                    if let Some(decision) = rule.matches(classification) {
                        log::debug!(
                            "App-specific rule matched: {} -> {:?}",
                            package,
                            decision.action
                        );
                        return decision;
                    }
                }
            }
        }
        
        // Check global rules
        for rule in &self.global_rules {
            if let Some(decision) = rule.matches(classification) {
                log::debug!("Global rule matched: {:?}", decision.action);
                return decision;
            }
        }
        
        // Default behavior
        if classification.is_safe {
            FilterDecision {
                action: FilterAction::Allow,
                intensity: 0.0,
                block_size: 0,
                reason: "Content classified as safe".to_string(),
            }
        } else {
            FilterDecision {
                action: FilterAction::Blur,
                intensity: 0.8,
                block_size: 0,
                reason: format!(
                    "Unsafe content detected: {} (confidence: {:.2})",
                    classification.category,
                    classification.confidence
                ),
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct FilterDecision {
    pub action: FilterAction,
    pub intensity: f32,
    pub block_size: u32,
    pub reason: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_policy_evaluation() {
        let mut engine = PolicyEngine::new();
        
        engine.add_rule(FilterRule {
            category: ContentCategory::AdultContent,
            action: FilterAction::Blur,
            confidence_threshold: 0.7,
            app_package: None,
        });
        
        let classification = ClassificationResult {
            category: "adult_content".to_string(),
            confidence: 0.85,
            is_safe: false,
        };
        
        let decision = engine.evaluate(&classification, None);
        assert!(matches!(decision.action, FilterAction::Blur));
    }
}
```

**File**: `rust/src/policy/rules.rs`

```rust
use crate::inference::ClassificationResult;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterRule {
    pub category: ContentCategory,
    pub action: FilterAction,
    pub confidence_threshold: f32,
    pub app_package: Option<String>,
}

impl FilterRule {
    pub fn matches(&self, classification: &ClassificationResult) 
        -> Option<super::engine::FilterDecision> {
        
        if self.category.matches(&classification.category) 
            && classification.confidence >= self.confidence_threshold {
            
            Some(super::engine::FilterDecision {
                action: self.action.clone(),
                intensity: self.calculate_intensity(classification.confidence),
                block_size: self.calculate_block_size(classification.confidence),
                reason: format!(
                    "Rule matched: {:?} with confidence {:.2}",
                    self.category,
                    classification.confidence
                ),
            })
        } else {
            None
        }
    }
    
    fn calculate_intensity(&self, confidence: f32) -> f32 {
        // Higher confidence = stronger blur
        (confidence * 1.0).min(1.0)
    }
    
    fn calculate_block_size(&self, confidence: f32) -> u32 {
        // Higher confidence = larger pixel blocks
        ((confidence * 32.0) as u32).max(8).min(64)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum ContentCategory {
    Safe,
    AdultContent,
    Violence,
    Gore,
    Hate,
    Custom(String),
}

impl ContentCategory {
    pub fn matches(&self, category_str: &str) -> bool {
        match self {
            Self::Safe => category_str == "safe",
            Self::AdultContent => {
                category_str.contains("adult") || 
                category_str.contains("nsfw") ||
                category_str.contains("explicit")
            }
            Self::Violence => category_str.contains("violence"),
            Self::Gore => category_str.contains("gore") || category_str.contains("blood"),
            Self::Hate => category_str.contains("hate"),
            Self::Custom(pattern) => category_str.contains(pattern),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum FilterAction {
    Allow,
    Blur,
    Pixelate,
    Block,
    Warn,
}
```

---

## Memory Pool Implementation

**File**: `rust/src/utils/pool.rs`

```rust
//! Memory pool for efficient frame buffer reuse
//! 
//! Avoids frequent allocations by maintaining a pool of reusable buffers.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

pub struct FrameBufferPool {
    pool: Arc<Mutex<VecDeque<Vec<u8>>>>,
    buffer_size: usize,
    max_buffers: usize,
}

impl FrameBufferPool {
    pub fn new(buffer_size: usize, max_buffers: usize) -> Self {
        Self {
            pool: Arc::new(Mutex::new(VecDeque::with_capacity(max_buffers))),
            buffer_size,
            max_buffers,
        }
    }
    
    pub fn acquire(&self) -> Vec<u8> {
        let mut pool = self.pool.lock().unwrap();
        
        if let Some(mut buffer) = pool.pop_front() {
            buffer.clear();
            buffer.resize(self.buffer_size, 0);
            buffer
        } else {
            vec![0u8; self.buffer_size]
        }
    }
    
    pub fn release(&self, buffer: Vec<u8>) {
        let mut pool = self.pool.lock().unwrap();
        
        if pool.len() < self.max_buffers {
            pool.push_back(buffer);
        }
        // Otherwise, let buffer be dropped
    }
    
    pub fn clear(&self) {
        let mut pool = self.pool.lock().unwrap();
        pool.clear();
    }
}

impl Clone for FrameBufferPool {
    fn clone(&self) -> Self {
        Self {
            pool: Arc::clone(&self.pool),
            buffer_size: self.buffer_size,
            max_buffers: self.max_buffers,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_pool_acquire_release() {
        let pool = FrameBufferPool::new(1024, 5);
        
        let buf1 = pool.acquire();
        assert_eq!(buf1.len(), 1024);
        
        pool.release(buf1);
        
        let buf2 = pool.acquire();
        assert_eq!(buf2.len(), 1024);
    }
    
    #[test]
    fn test_pool_max_size() {
        let pool = FrameBufferPool::new(100, 2);
        
        let buf1 = pool.acquire();
        let buf2 = pool.acquire();
        let buf3 = pool.acquire();
        
        pool.release(buf1);
        pool.release(buf2);
        pool.release(buf3);
        
        let pool_lock = pool.pool.lock().unwrap();
        assert_eq!(pool_lock.len(), 2); // Only 2 buffers kept
    }
}
```

---

## Performance Metrics

**File**: `rust/src/utils/metrics.rs`

```rust
//! Performance metrics collection
//! 
//! Tracks timing and resource usage for optimization.

use std::time::{Duration, Instant};
use std::collections::VecDeque;

pub struct PerformanceMetrics {
    frame_times: VecDeque<Duration>,
    inference_times: VecDeque<Duration>,
    blur_times: VecDeque<Duration>,
    max_samples: usize,
}

impl PerformanceMetrics {
    pub fn new(max_samples: usize) -> Self {
        Self {
            frame_times: VecDeque::with_capacity(max_samples),
            inference_times: VecDeque::with_capacity(max_samples),
            blur_times: VecDeque::with_capacity(max_samples),
            max_samples,
        }
    }
    
    pub fn record_frame_time(&mut self, duration: Duration) {
        if self.frame_times.len() >= self.max_samples {
            self.frame_times.pop_front();
        }
        self.frame_times.push_back(duration);
    }
    
    pub fn record_inference_time(&mut self, duration: Duration) {
        if self.inference_times.len() >= self.max_samples {
            self.inference_times.pop_front();
        }
        self.inference_times.push_back(duration);
    }
    
    pub fn record_blur_time(&mut self, duration: Duration) {
        if self.blur_times.len() >= self.max_samples {
            self.blur_times.pop_front();
        }
        self.blur_times.push_back(duration);
    }
    
    pub fn average_frame_time(&self) -> Option<Duration> {
        if self.frame_times.is_empty() {
            return None;
        }
        
        let sum: Duration = self.frame_times.iter().sum();
        Some(sum / self.frame_times.len() as u32)
    }
    
    pub fn average_inference_time(&self) -> Option<Duration> {
        if self.inference_times.is_empty() {
            return None;
        }
        
        let sum: Duration = self.inference_times.iter().sum();
        Some(sum / self.inference_times.len() as u32)
    }
    
    pub fn get_stats(&self) -> MetricsStats {
        MetricsStats {
            avg_frame_ms: self.average_frame_time()
                .map(|d| d.as_millis() as f32)
                .unwrap_or(0.0),
            avg_inference_ms: self.average_inference_time()
                .map(|d| d.as_millis() as f32)
                .unwrap_or(0.0),
            frames_processed: self.frame_times.len(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct MetricsStats {
    pub avg_frame_ms: f32,
    pub avg_inference_ms: f32,
    pub frames_processed: usize,
}

pub struct Timer {
    start: Instant,
}

impl Timer {
    pub fn start() -> Self {
        Self {
            start: Instant::now(),
        }
    }
    
    pub fn elapsed(&self) -> Duration {
        self.start.elapsed()
    }
}
```

---

## Build Configuration

**File**: `rust/build.rs`

```rust
fn main() {
    // Compile-time checks
    println!("cargo:rerun-if-changed=src/");
    
    // Set linking flags for Android
    #[cfg(target_os = "android")]
    {
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=android");
    }
}
```

---

## Complete Cargo.toml with All Features

**File**: `rust/Cargo.toml`

```toml
[package]
name = "pavlova_rust"
version = "0.1.0"
edition = "2021"
authors = ["Pavlova Team"]
description = "On-device ML and image processing for Android screen safety"
license = "MIT"

[lib]
crate-type = ["cdylib", "staticlib"]
name = "pavlova_rust"

[dependencies]
# JNI bindings
jni = { version = "0.21", default-features = false }

# Image processing
image = { version = "0.24", default-features = false }
fast_image_resize = { version = "3.0", features = ["image"] }

# ML inference backends (optional)
tflite = { version = "0.3", optional = true }
ort = { version = "2.0", optional = true, default-features = false }
tract-onnx = { version = "0.21", optional = true }

# Performance
rayon = "1.8"
crossbeam = "0.8"
once_cell = "1.19"

# Serialization
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"

# Logging
log = "0.4"
android_logger = "0.13"

# SIMD (for optimized image processing)
packed_simd = { version = "0.3", optional = true }

[dev-dependencies]
criterion = "0.5"

[features]
default = ["tflite-backend"]
tflite-backend = ["tflite"]
onnx-backend = ["ort"]
tract-backend = ["tract-onnx"]
simd-optimizations = ["packed_simd"]

[profile.release]
opt-level = 3
lto = true
codegen-units = 1
strip = true
panic = "abort"

[profile.bench]
inherits = "release"

[[bench]]
name = "image_processing"
harness = false
```

---

## Testing Strategy

### Unit Tests

```rust
// In each module's test section

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_blur_performance() {
        let mut buffer = vec![128u8; 1920 * 1080 * 4];
        let start = std::time::Instant::now();
        
        apply_gaussian_blur(&mut buffer, 1920, 1080, 10);
        
        let duration = start.elapsed();
        println!("Blur took: {:?}", duration);
        assert!(duration.as_millis() < 100); // Should be faster than 100ms
    }
}
```

### Benchmarks

**File**: `rust/benches/image_processing.rs`

```rust
use criterion::{black_box, criterion_group, criterion_main, Criterion};
use pavlova_rust::image::effects::{apply_gaussian_blur, apply_pixelation};

fn benchmark_blur(c: &mut Criterion) {
    let mut group = c.benchmark_group("blur");
    
    let sizes = vec![
        (640, 480),
        (1280, 720),
        (1920, 1080),
    ];
    
    for (width, height) in sizes {
        let mut buffer = vec![128u8; (width * height * 4) as usize];
        
        group.bench_function(format!("gaussian_{}x{}", width, height), |b| {
            b.iter(|| {
                apply_gaussian_blur(
                    black_box(&mut buffer),
                    black_box(width),
                    black_box(height),
                    black_box(10),
                );
            });
        });
    }
    
    group.finish();
}

fn benchmark_pixelation(c: &mut Criterion) {
    let mut buffer = vec![128u8; 1920 * 1080 * 4];
    
    c.bench_function("pixelation_1080p", |b| {
        b.iter(|| {
            apply_pixelation(
                black_box(&mut buffer),
                black_box(1920),
                black_box(1080),
                black_box(16),
            );
        });
    });
}

criterion_group!(benches, benchmark_blur, benchmark_pixelation);
criterion_main!(benches);
```

---

## Integration with Android

### Kotlin Bridge Class

**File**: `android/app/src/main/java/com/pavlova/ml/RustMLBridge.kt`

```kotlin
package com.pavlova.ml

import android.content.Context
import java.io.File

class RustMLBridge(private val context: Context) {
    
    private var engineHandle: Long = 0
    
    init {
        System.loadLibrary("pavlova_rust")
        
        // Initialize Rust library
        pavlovaInit()
        
        // Load ML model
        val modelPath = extractModelFromAssets()
        engineHandle = initEngine(modelPath, CONFIDENCE_THRESHOLD)
    }
    
    fun classifyFrame(
        frameData: ByteArray,
        width: Int,
        height: Int
    ): ClassificationResult {
        return classifyFrame(engineHandle, frameData, width, height)
    }
    
    fun generateBlur(
        frameData: ByteArray,
        width: Int,
        height: Int,
        intensity: Float
    ): ByteArray {
        return generateBlur(frameData, width, height, intensity)
    }
    
    fun generatePixelation(
        frameData: ByteArray,
        width: Int,
        height: Int,
        blockSize: Int
    ): ByteArray {
        return generatePixelation(frameData, width, height, blockSize)
    }
    
    fun cleanup() {
        if (engineHandle != 0L) {
            destroyEngine(engineHandle)
            engineHandle = 0
        }
    }
    
    private fun extractModelFromAssets(): String {
        val modelFile = File(context.filesDir, "model.tflite")
        
        if (!modelFile.exists()) {
            context.assets.open("models/nsfw_classifier.tflite").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return modelFile.absolutePath
    }
    
    // Native methods (implemented in Rust)
    private external fun pavlovaInit()
    private external fun initEngine(modelPath: String, threshold: Float): Long
    private external fun classifyFrame(
        engineHandle: Long,
        frameData: ByteArray,
        width: Int,
        height: Int
    ): ClassificationResult
    private external fun generateBlur(
        frameData: ByteArray,
        width: Int,
        height: Int,
        intensity: Float
    ): ByteArray
    private external fun generatePixelation(
        frameData: ByteArray,
        width: Int,
        height: Int,
        blockSize: Int
    ): ByteArray
    private external fun destroyEngine(engineHandle: Long)
    
    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.75f
    }
}

data class ClassificationResult(
    val category: String,
    val confidence: Float,
    val isSafe: Boolean
)
```

---

## Performance Targets

| Operation | Target Latency | Memory |
|-----------|---------------|---------|
| Frame capture | < 20ms | ~8MB |
| YUV→RGBA conversion | < 10ms | Reused buffer |
| Image resize (1080p→224x224) | < 5ms | ~200KB |
| ML inference | < 30ms | ~10MB |
| Gaussian blur (1080p) | < 20ms | Reused buffer |
| Pixelation (1080p) | < 5ms | Reused buffer |
| **Total pipeline** | **< 90ms** | **< 25MB** |

**Note**: These targets allow ~11 FPS processing on mid-range devices.

---

## Next Steps

1. Implement TFLite integration with actual model
2. Add ONNX Runtime support as alternative
3. Optimize SIMD paths for ARM NEON
4. Profile on real devices
5. Add adaptive quality settings based on device capabilities

