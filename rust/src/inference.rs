use std::error::Error;
use std::path::Path;
use tract_onnx::prelude::*;

use crate::config::{CLASSES, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, MODEL_INPUT_CHANNELS};

/// Classification result with per-class scores
#[derive(Debug, Clone)]
pub struct ClassificationResult {
    /// Per-class confidence scores (drawing, hentai, neutral, porn, sexy)
    pub scores: [f32; 5],
    /// Index of highest-scoring class
    pub top_class_index: usize,
    /// Name of highest-scoring class
    pub top_class: String,
    /// Whether content is safe (neutral or drawing)
    pub is_safe: bool,
    /// Confidence of the safety determination (max of unsafe classes if unsafe, max of safe classes if safe)
    pub confidence: f32,
}

/// ML inference engine using tract (pure Rust)
pub struct MLEngine {
    model: SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>,
    model_path: String,
    is_nhwc: bool,
}

impl MLEngine {
    /// Create new ML engine by loading model from file
    pub fn new(model_path: &str) -> Result<Self, Box<dyn Error>> {
        if !Path::new(model_path).exists() {
            return Err(format!("Model file not found: {}", model_path).into());
        }

        log::info!("Loading ML model from: {}", model_path);

        // Try loading based on file extension
        let (model, is_nhwc) = if model_path.ends_with(".tflite") {
            (Self::load_tflite(model_path)?, true)
        } else if model_path.ends_with(".onnx") {
            Self::load_onnx(model_path)?
        } else {
            return Err(format!("Unsupported model format: {}", model_path).into());
        };

        log::info!("ML model loaded successfully (NHWC: {})", is_nhwc);

        Ok(MLEngine {
            model,
            model_path: model_path.to_string(),
            is_nhwc,
        })
    }

    /// Load a TFLite model using tract
    fn load_tflite(model_path: &str) -> Result<SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>, Box<dyn Error>> {
        let model = tract_tflite::tflite().model_for_path(model_path)?;

        // TFLite models for MobileNet are typically NHWC [1, 224, 224, 3]
        let fact = f32::fact([1, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, MODEL_INPUT_CHANNELS]).into();

        let model = model
            .with_input_fact(0, fact)?
            .into_optimized()?
            .into_runnable()?;

        Ok(model)
    }

    /// Load an ONNX model using tract
    fn load_onnx(model_path: &str) -> Result<(SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>, bool), Box<dyn Error>> {
        let model = tract_onnx::onnx().model_for_path(model_path)?;

        // Determine if model is NCHW or NHWC by inspecting input shape
        let input_id = model.input_outlets()?[0];
        let fact = model.outlet_fact(input_id)?;
        let mut is_nhwc = false;

        if let Ok(Some(shape)) = fact.shape.as_concrete_finite() {
            log::info!("Model input shape: {:?}", shape);
            // If last dimension is 3, it's likely NHWC [1, 224, 224, 3]
            if shape.len() == 4 && shape[3] == 3 {
                is_nhwc = true;
            }
        }

        // Set batch size to 1 and define input shape
        let fact = if is_nhwc {
            f32::fact([1, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, MODEL_INPUT_CHANNELS]).into()
        } else {
            f32::fact([1, MODEL_INPUT_CHANNELS, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH]).into()
        };

        log::info!("Setting input fact (is_nhwc={}): {:?}", is_nhwc, fact);

        let model = model
            .with_input_fact(0, fact)?
            .into_optimized()?
            .into_runnable()?;
        
        Ok((model, is_nhwc))
    }

    /// Classify preprocessed image data
    pub fn classify_preprocessed(&self, input_tensor: &[f32]) -> Result<ClassificationResult, Box<dyn Error>> {
        let expected_size = MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT * MODEL_INPUT_CHANNELS;
        if input_tensor.len() != expected_size {
            return Err(format!(
                "Input tensor size mismatch: expected {}, got {}",
                expected_size,
                input_tensor.len()
            ).into());
        }

        // Create tract tensor in appropriate format
        let tensor: Tensor = if self.is_nhwc {
            tract_ndarray::Array4::from_shape_vec(
                (1, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, MODEL_INPUT_CHANNELS),
                input_tensor.to_vec(),
            )?.into()
        } else {
            tract_ndarray::Array4::from_shape_vec(
                (1, MODEL_INPUT_CHANNELS, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH),
                input_tensor.to_vec(),
            )?.into()
        };

        // Run inference
        let result = self.model.run(tvec!(tensor.into()))?;
        
        // Extract output scores
        let output = result[0].to_array_view::<f32>()?;
        let scores_slice = output.as_slice().unwrap();
        
        // Parse into 5-class scores
        let mut scores = [0.0f32; 5];
        for (i, &score) in scores_slice.iter().take(5).enumerate() {
            scores[i] = score;
        }

        // Find top class
        let top_class_index = scores
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap())
            .map(|(i, _)| i)
            .unwrap_or(2); // default to "neutral"

        // Safe classes: neutral (2) and drawing (0)
        let safe_score = scores[0] + scores[2];
        let unsafe_score = scores[1] + scores[3] + scores[4];
        let is_safe = safe_score > unsafe_score;
        
        let confidence = if is_safe { safe_score } else { unsafe_score };

        let result = ClassificationResult {
            scores,
            top_class_index,
            top_class: CLASSES[top_class_index].to_string(),
            is_safe,
            confidence,
        };

        Ok(result)
    }

    /// Classify raw RGBA image data
    pub fn classify(&self, rgba_data: &[u8], width: usize, height: usize) -> Result<ClassificationResult, Box<dyn Error>> {
        let preprocessed = crate::image::preprocess_for_model(rgba_data, width, height, self.is_nhwc)?;
        self.classify_preprocessed(&preprocessed)
    }

    /// Get model path
    pub fn model_path(&self) -> &str {
        &self.model_path
    }
}
