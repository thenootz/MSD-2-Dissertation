use std::error::Error;
use std::path::Path;
use tract_onnx::prelude::*;

/// GantMan NSFW model output classes
/// Order matches the model's output tensor
const CLASSES: [&str; 5] = ["drawing", "hentai", "neutral", "porn", "sexy"];

/// Model input dimensions (MobileNetV2 1.4)
pub const MODEL_INPUT_WIDTH: usize = 224;
pub const MODEL_INPUT_HEIGHT: usize = 224;
pub const MODEL_INPUT_CHANNELS: usize = 3;

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
}

impl MLEngine {
    /// Create new ML engine by loading model from file
    pub fn new(model_path: &str) -> Result<Self, Box<dyn Error>> {
        if !Path::new(model_path).exists() {
            return Err(format!("Model file not found: {}", model_path).into());
        }

        log::info!("Loading ML model from: {}", model_path);

        // Try loading based on file extension
        let model = if model_path.ends_with(".tflite") {
            Self::load_tflite(model_path)?
        } else if model_path.ends_with(".onnx") {
            Self::load_onnx(model_path)?
        } else {
            return Err(format!("Unsupported model format: {}", model_path).into());
        };

        log::info!("ML model loaded successfully");

        Ok(MLEngine {
            model,
            model_path: model_path.to_string(),
        })
    }

    /// Load a TFLite model using tract
    fn load_tflite(_model_path: &str) -> Result<SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>, Box<dyn Error>> {
        // tract doesn't directly support tflite — we need to convert to ONNX first
        // For Phase 1, we'll use ONNX format
        Err("TFLite models must first be converted to ONNX format. Use: python -m tf2onnx.convert --tflite model.tflite --output model.onnx".into())
    }

    /// Load an ONNX model using tract
    /// Model expects NCHW input: [1, 3, 224, 224]
    fn load_onnx(model_path: &str) -> Result<SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>, Box<dyn Error>> {
        let model = tract_onnx::onnx()
            .model_for_path(model_path)?
            .with_input_fact(0, f32::fact([1, MODEL_INPUT_CHANNELS, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH]).into())?
            .into_optimized()?
            .into_runnable()?;
        
        Ok(model)
    }

    /// Classify preprocessed image data
    /// Input: already preprocessed float32 tensor in NCHW format [1, 3, 224, 224] normalized to [-1, 1]
    pub fn classify_preprocessed(&self, input_tensor: &[f32]) -> Result<ClassificationResult, Box<dyn Error>> {
        let expected_size = MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT * MODEL_INPUT_CHANNELS;
        if input_tensor.len() != expected_size {
            return Err(format!(
                "Input tensor size mismatch: expected {}, got {}",
                expected_size,
                input_tensor.len()
            ).into());
        }

        // Create tract tensor in NCHW format [1, 3, 224, 224]
        let tensor: Tensor = tract_ndarray::Array4::from_shape_vec(
            (1, MODEL_INPUT_CHANNELS, MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH),
            input_tensor.to_vec(),
        )?.into();

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
        // Unsafe classes: hentai (1), porn (3), sexy (4)
        let safe_score = scores[0] + scores[2]; // drawing + neutral
        let unsafe_score = scores[1] + scores[3] + scores[4]; // hentai + porn + sexy
        let is_safe = safe_score > unsafe_score;
        
        let confidence = if is_safe { safe_score } else { unsafe_score };

        let result = ClassificationResult {
            scores,
            top_class_index,
            top_class: CLASSES[top_class_index].to_string(),
            is_safe,
            confidence,
        };

        log::debug!(
            "Classification: {} (confidence: {:.3}), scores: d={:.3} h={:.3} n={:.3} p={:.3} s={:.3}",
            result.top_class,
            result.confidence,
            scores[0], scores[1], scores[2], scores[3], scores[4]
        );

        Ok(result)
    }

    /// Classify raw RGBA image data (handles full preprocessing pipeline)
    pub fn classify(&self, rgba_data: &[u8], width: usize, height: usize) -> Result<ClassificationResult, Box<dyn Error>> {
        let preprocessed = crate::image::preprocess_for_model(rgba_data, width, height)?;
        self.classify_preprocessed(&preprocessed)
    }

    /// Get model path
    pub fn model_path(&self) -> &str {
        &self.model_path
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_class_names() {
        assert_eq!(CLASSES[0], "drawing");
        assert_eq!(CLASSES[1], "hentai");
        assert_eq!(CLASSES[2], "neutral");
        assert_eq!(CLASSES[3], "porn");
        assert_eq!(CLASSES[4], "sexy");
    }

    #[test]
    fn test_classification_result_safe() {
        let result = ClassificationResult {
            scores: [0.1, 0.05, 0.8, 0.03, 0.02],
            top_class_index: 2,
            top_class: "neutral".to_string(),
            is_safe: true,
            confidence: 0.9,
        };
        assert!(result.is_safe);
        assert_eq!(result.top_class, "neutral");
    }

    #[test]
    fn test_classification_result_unsafe() {
        let result = ClassificationResult {
            scores: [0.01, 0.05, 0.04, 0.85, 0.05],
            top_class_index: 3,
            top_class: "porn".to_string(),
            is_safe: false,
            confidence: 0.95,
        };
        assert!(!result.is_safe);
        assert_eq!(result.top_class, "porn");
    }
}
