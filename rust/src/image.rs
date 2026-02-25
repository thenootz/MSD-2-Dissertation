use std::error::Error;

use crate::inference::{MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, MODEL_INPUT_CHANNELS};

/// Preprocess RGBA image data for MobileNetV2 model inference
/// Pipeline: RGBA → resize to 224×224 → drop alpha → normalize to [-1, 1] → NCHW layout
/// Returns Vec<f32> of length 3 * 224 * 224 in NCHW (channel-first) order
pub fn preprocess_for_model(rgba_data: &[u8], width: usize, height: usize) -> Result<Vec<f32>, Box<dyn Error>> {
    if rgba_data.len() != width * height * 4 {
        return Err(format!(
            "RGBA data size mismatch: expected {}x{}x4={}, got {}",
            width, height, width * height * 4, rgba_data.len()
        ).into());
    }

    // Step 1: Resize to 224×224 (bilinear interpolation on RGBA)
    let resized = resize_bilinear_rgba(rgba_data, width, height, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT);

    let pixel_count = MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT;
    let output_size = MODEL_INPUT_CHANNELS * pixel_count;
    
    // Step 2: Convert RGBA → RGB, normalize to [-1, 1], and reorder to NCHW
    // NCHW = all R values, then all G values, then all B values
    let mut normalized = vec![0.0f32; output_size];

    for i in 0..pixel_count {
        let rgba_idx = i * 4;
        // MobileNetV2 normalization: (pixel - 127.5) / 127.5
        normalized[i] = (resized[rgba_idx] as f32 - 127.5) / 127.5;                         // R channel
        normalized[pixel_count + i] = (resized[rgba_idx + 1] as f32 - 127.5) / 127.5;       // G channel
        normalized[2 * pixel_count + i] = (resized[rgba_idx + 2] as f32 - 127.5) / 127.5;   // B channel
        // Alpha channel dropped
    }

    Ok(normalized)
}

/// Bilinear interpolation resize for RGBA images
fn resize_bilinear_rgba(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    dst_w: usize,
    dst_h: usize,
) -> Vec<u8> {
    let mut dst = vec![0u8; dst_w * dst_h * 4];
    let x_ratio = src_w as f32 / dst_w as f32;
    let y_ratio = src_h as f32 / dst_h as f32;

    for dy in 0..dst_h {
        for dx in 0..dst_w {
            let src_x = dx as f32 * x_ratio;
            let src_y = dy as f32 * y_ratio;

            let x0 = src_x.floor() as usize;
            let y0 = src_y.floor() as usize;
            let x1 = (x0 + 1).min(src_w - 1);
            let y1 = (y0 + 1).min(src_h - 1);

            let x_frac = src_x - x0 as f32;
            let y_frac = src_y - y0 as f32;

            let idx00 = (y0 * src_w + x0) * 4;
            let idx10 = (y0 * src_w + x1) * 4;
            let idx01 = (y1 * src_w + x0) * 4;
            let idx11 = (y1 * src_w + x1) * 4;

            let dst_idx = (dy * dst_w + dx) * 4;

            for c in 0..4 {
                let v00 = src[idx00 + c] as f32;
                let v10 = src[idx10 + c] as f32;
                let v01 = src[idx01 + c] as f32;
                let v11 = src[idx11 + c] as f32;

                let top = v00 + (v10 - v00) * x_frac;
                let bottom = v01 + (v11 - v01) * x_frac;
                let value = top + (bottom - top) * y_frac;

                dst[dst_idx + c] = value.round().clamp(0.0, 255.0) as u8;
            }
        }
    }

    dst
}

/// Apply Gaussian blur to RGBA image
pub fn blur(image_data: &[u8], width: usize, height: usize, radius: f32) -> Result<Vec<u8>, Box<dyn Error>> {
    if image_data.len() != width * height * 4 {
        return Err("Invalid image data size".into());
    }

    // Simple box blur approximation for Gaussian blur
    // For better quality, use a proper Gaussian kernel
    
    let mut output = image_data.to_vec();
    let kernel_size = (radius * 2.0) as usize + 1;
    let half_kernel = kernel_size / 2;

    // Horizontal pass
    let mut temp = vec![0u8; width * height * 4];
    for y in 0..height {
        for x in 0..width {
            let mut r_sum = 0u32;
            let mut g_sum = 0u32;
            let mut b_sum = 0u32;
            let mut count = 0u32;

            for kx in 0..kernel_size {
                let sample_x = (x as i32 + kx as i32 - half_kernel as i32).max(0).min(width as i32 - 1) as usize;
                let idx = (y * width + sample_x) * 4;
                
                r_sum += image_data[idx] as u32;
                g_sum += image_data[idx + 1] as u32;
                b_sum += image_data[idx + 2] as u32;
                count += 1;
            }

            let out_idx = (y * width + x) * 4;
            temp[out_idx] = (r_sum / count) as u8;
            temp[out_idx + 1] = (g_sum / count) as u8;
            temp[out_idx + 2] = (b_sum / count) as u8;
            temp[out_idx + 3] = image_data[out_idx + 3]; // Keep alpha
        }
    }

    // Vertical pass
    for y in 0..height {
        for x in 0..width {
            let mut r_sum = 0u32;
            let mut g_sum = 0u32;
            let mut b_sum = 0u32;
            let mut count = 0u32;

            for ky in 0..kernel_size {
                let sample_y = (y as i32 + ky as i32 - half_kernel as i32).max(0).min(height as i32 - 1) as usize;
                let idx = (sample_y * width + x) * 4;
                
                r_sum += temp[idx] as u32;
                g_sum += temp[idx + 1] as u32;
                b_sum += temp[idx + 2] as u32;
                count += 1;
            }

            let out_idx = (y * width + x) * 4;
            output[out_idx] = (r_sum / count) as u8;
            output[out_idx + 1] = (g_sum / count) as u8;
            output[out_idx + 2] = (b_sum / count) as u8;
            output[out_idx + 3] = temp[out_idx + 3]; // Keep alpha
        }
    }

    Ok(output)
}

/// Apply pixelation effect to RGBA image
pub fn pixelate(image_data: &[u8], width: usize, height: usize, block_size: usize) -> Result<Vec<u8>, Box<dyn Error>> {
    if image_data.len() != width * height * 4 {
        return Err("Invalid image data size".into());
    }

    let mut output = image_data.to_vec();

    for block_y in (0..height).step_by(block_size) {
        for block_x in (0..width).step_by(block_size) {
            // Calculate average color for this block
            let mut r_sum = 0u32;
            let mut g_sum = 0u32;
            let mut b_sum = 0u32;
            let mut count = 0u32;

            let block_end_y = (block_y + block_size).min(height);
            let block_end_x = (block_x + block_size).min(width);

            for y in block_y..block_end_y {
                for x in block_x..block_end_x {
                    let idx = (y * width + x) * 4;
                    r_sum += image_data[idx] as u32;
                    g_sum += image_data[idx + 1] as u32;
                    b_sum += image_data[idx + 2] as u32;
                    count += 1;
                }
            }

            let avg_r = (r_sum / count) as u8;
            let avg_g = (g_sum / count) as u8;
            let avg_b = (b_sum / count) as u8;

            // Fill block with average color
            for y in block_y..block_end_y {
                for x in block_x..block_end_x {
                    let idx = (y * width + x) * 4;
                    output[idx] = avg_r;
                    output[idx + 1] = avg_g;
                    output[idx + 2] = avg_b;
                    // Keep original alpha
                }
            }
        }
    }

    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_blur() {
        let width = 100;
        let height = 100;
        let image_data = vec![128u8; width * height * 4];
        
        let result = blur(&image_data, width, height, 5.0);
        assert!(result.is_ok());
        
        let blurred = result.unwrap();
        assert_eq!(blurred.len(), image_data.len());
    }

    #[test]
    fn test_pixelate() {
        let width = 100;
        let height = 100;
        let image_data = vec![128u8; width * height * 4];
        
        let result = pixelate(&image_data, width, height, 10);
        assert!(result.is_ok());
        
        let pixelated = result.unwrap();
        assert_eq!(pixelated.len(), image_data.len());
    }
}
