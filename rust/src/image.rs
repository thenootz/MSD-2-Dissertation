use std::error::Error;

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
