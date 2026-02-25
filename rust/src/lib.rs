use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jbyteArray, jfloatArray, jint};
use log::{info, error};
use std::sync::Mutex;

mod image;
mod inference;
mod utils;

use crate::image::{blur, pixelate};
use crate::inference::MLEngine;

// Global ML engine instance
lazy_static::lazy_static! {
    static ref ML_ENGINE: Mutex<Option<MLEngine>> = Mutex::new(None);
}

/// Initialize the ML engine with a model file
#[no_mangle]
pub extern "C" fn Java_com_pavlova_ml_RustMLBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jboolean {
    // Initialize Android logger
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("PavlovaRust")
    );

    info!("Initializing ML engine...");

    let model_path_str: String = match env.get_string(&model_path) {
        Ok(path) => path.into(),
        Err(e) => {
            error!("Failed to get model path: {:?}", e);
            return false as jboolean;
        }
    };

    match MLEngine::new(&model_path_str) {
        Ok(engine) => {
            let mut ml_engine = ML_ENGINE.lock().unwrap();
            *ml_engine = Some(engine);
            info!("ML engine initialized successfully");
            true as jboolean
        }
        Err(e) => {
            error!("Failed to initialize ML engine: {:?}", e);
            false as jboolean
        }
    }
}

/// Classify a frame (image data)
/// Returns array: [confidence_safe, confidence_unsafe]
#[no_mangle]
pub extern "C" fn Java_com_pavlova_ml_RustMLBridge_nativeClassifyFrame<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    image_data: JByteArray<'local>,
    width: jint,
    height: jint,
) -> jfloatArray {
    let start_time = std::time::Instant::now();

    // Convert JByteArray to Rust Vec
    let image_bytes = match env.convert_byte_array(image_data) {
        Ok(bytes) => bytes,
        Err(e) => {
            error!("Failed to convert image data: {:?}", e);
            return env.new_float_array(2).unwrap().into_raw();
        }
    };

    // Classify using ML engine
    let ml_engine = ML_ENGINE.lock().unwrap();
    let result = match &*ml_engine {
        Some(engine) => {
            match engine.classify(&image_bytes, width as usize, height as usize) {
                Ok(confidence) => confidence,
                Err(e) => {
                    error!("Classification failed: {:?}", e);
                    0.5 // Default to safe
                }
            }
        }
        None => {
            error!("ML engine not initialized");
            0.5
        }
    };

    let elapsed = start_time.elapsed();
    info!("Classification took {:?}", elapsed);

    // Return [confidence_safe, confidence_unsafe]
    let output = env.new_float_array(2).unwrap();
    env.set_float_array_region(&output, 0, &[result, 1.0 - result]).unwrap();
    output.into_raw()
}

/// Generate blurred version of image
#[no_mangle]
pub extern "C" fn Java_com_pavlova_ml_RustMLBridge_nativeGenerateBlur<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    image_data: JByteArray<'local>,
    width: jint,
    height: jint,
    radius: f32,
) -> jbyteArray {
    let start_time = std::time::Instant::now();

    // Convert JByteArray to Rust Vec
    let image_bytes = match env.convert_byte_array(image_data) {
        Ok(bytes) => bytes,
        Err(e) => {
            error!("Failed to convert image data: {:?}", e);
            // Return empty array on error
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };

    // Apply blur
    let blurred = match blur(&image_bytes, width as usize, height as usize, radius) {
        Ok(data) => data,
        Err(e) => {
            error!("Blur failed: {:?}", e);
            // Return original data on error
            image_bytes
        }
    };

    let elapsed = start_time.elapsed();
    info!("Blur took {:?}", elapsed);

    // Convert back to JByteArray
    let output = env.new_byte_array(blurred.len() as i32).unwrap();
    env.set_byte_array_region(&output, 0, &blurred.iter().map(|&b| b as i8).collect::<Vec<_>>()).unwrap();
    output.into_raw()
}

/// Generate pixelated version of image
#[no_mangle]
pub extern "C" fn Java_com_pavlova_ml_RustMLBridge_nativeGeneratePixelation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    image_data: JByteArray<'local>,
    width: jint,
    height: jint,
    block_size: jint,
) -> jbyteArray {
    let start_time = std::time::Instant::now();

    // Convert JByteArray to Rust Vec
    let image_bytes = match env.convert_byte_array(image_data) {
        Ok(bytes) => bytes,
        Err(e) => {
            error!("Failed to convert image data: {:?}", e);
            // Return empty array on error
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };

    // Apply pixelation
    let pixelated = match pixelate(&image_bytes, width as usize, height as usize, block_size as usize) {
        Ok(data) => data,
        Err(e) => {
            error!("Pixelation failed: {:?}", e);
            // Return original data on error
            image_bytes
        }
    };

    let elapsed = start_time.elapsed();
    info!("Pixelation took {:?}", elapsed);

    // Convert back to JByteArray
    let output = env.new_byte_array(pixelated.len() as i32).unwrap();
    env.set_byte_array_region(&output, 0, &pixelated.iter().map(|&b| b as i8).collect::<Vec<_>>()).unwrap();
    output.into_raw()
}

/// Cleanup and destroy ML engine
#[no_mangle]
pub extern "C" fn Java_com_pavlova_ml_RustMLBridge_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
) {
    info!("Destroying ML engine...");
    let mut ml_engine = ML_ENGINE.lock().unwrap();
    *ml_engine = None;
    info!("ML engine destroyed");
}
