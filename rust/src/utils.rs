/// Utility functions for Pavlova

/// Convert YUV420 to RGBA (for future use if needed)
pub fn yuv420_to_rgba(
    y_plane: &[u8],
    u_plane: &[u8],
    v_plane: &[u8],
    width: usize,
    height: usize,
) -> Vec<u8> {
    let mut rgba = vec![0u8; width * height * 4];

    for row in 0..height {
        for col in 0..width {
            let y_index = row * width + col;
            let uv_index = (row / 2) * (width / 2) + (col / 2);

            let y = y_plane[y_index] as f32;
            let u = u_plane[uv_index] as f32 - 128.0;
            let v = v_plane[uv_index] as f32 - 128.0;

            // BT.709 conversion
            let r = (y + 1.5748 * v).max(0.0).min(255.0) as u8;
            let g = (y - 0.1873 * u - 0.4681 * v).max(0.0).min(255.0) as u8;
            let b = (y + 1.8556 * u).max(0.0).min(255.0) as u8;

            let out_index = y_index * 4;
            rgba[out_index] = r;
            rgba[out_index + 1] = g;
            rgba[out_index + 2] = b;
            rgba[out_index + 3] = 255; // Alpha
        }
    }

    rgba
}

/// Performance timer
pub struct Timer {
    start: std::time::Instant,
    name: String,
}

impl Timer {
    pub fn new(name: &str) -> Self {
        Timer {
            start: std::time::Instant::now(),
            name: name.to_string(),
        }
    }
}

impl Drop for Timer {
    fn drop(&mut self) {
        let elapsed = self.start.elapsed();
        log::debug!("{} took {:?}", self.name, elapsed);
    }
}
