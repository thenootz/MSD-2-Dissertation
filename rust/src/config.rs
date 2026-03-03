/// Model configuration constants shared across modules.

/// GantMan NSFW model output class names.
/// Order matches the model's output tensor.
pub const CLASSES: [&str; 5] = ["drawing", "hentai", "neutral", "porn", "sexy"];

/// Number of output classes.
pub const NUM_CLASSES: usize = CLASSES.len();

/// Model input dimensions (MobileNetV2 1.4, 224×224 RGB).
pub const MODEL_INPUT_WIDTH: usize = 224;
pub const MODEL_INPUT_HEIGHT: usize = 224;
pub const MODEL_INPUT_CHANNELS: usize = 3;
