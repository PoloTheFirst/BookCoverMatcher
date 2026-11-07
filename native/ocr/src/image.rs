// SPDX-License-Identifier: GPL-3.0-or-later

//! Decoded RGBA images and the resampling the detector and recogniser need.

use crate::geom::{sample_bilinear, Point, Quad};

/// An 8-bit RGBA image, the format Kotlin hands across JNI (it decodes with `BitmapFactory`, so
/// EXIF orientation and codec support stay on the platform side).
#[derive(Clone)]
pub struct RgbaImage {
    pub width: usize,
    pub height: usize,
    /// Row-major RGBA, `width * height * 4` bytes.
    pub data: Vec<u8>,
}

impl RgbaImage {
    /// Wraps raw pixels, rejecting buffers whose length does not match the stated size.
    pub fn new(width: usize, height: usize, data: Vec<u8>) -> Result<Self, String> {
        if width == 0 || height == 0 {
            return Err("image has a zero dimension".into());
        }
        let expected = width
            .checked_mul(height)
            .and_then(|px| px.checked_mul(4))
            .ok_or_else(|| "image dimensions overflow".to_string())?;
        if data.len() != expected {
            return Err(format!(
                "image buffer is {} bytes, expected {} for {}x{} RGBA",
                data.len(),
                expected,
                width,
                height
            ));
        }
        Ok(Self { width, height, data })
    }

    /// Bilinear resize to an exact size (used to build the detector's input canvas).
    pub fn resized(&self, width: usize, height: usize) -> RgbaImage {
        if width == self.width && height == self.height {
            return self.clone();
        }
        let mut out = vec![0u8; width * height * 4];
        let sx = self.width as f32 / width as f32;
        let sy = self.height as f32 / height as f32;
        for y in 0..height {
            for x in 0..width {
                let px = sample_bilinear(
                    &self.data,
                    self.width,
                    self.height,
                    (x as f32 + 0.5) * sx - 0.5,
                    (y as f32 + 0.5) * sy - 0.5,
                );
                let at = (y * width + x) * 4;
                out[at..at + 4].copy_from_slice(&px);
            }
        }
        RgbaImage { width, height, data: out }
    }

    /// Axis-aligned crop clamped to the image bounds, or `None` when nothing is left.
    pub fn crop(&self, x: usize, y: usize, w: usize, h: usize) -> Option<RgbaImage> {
        let x0 = x.min(self.width);
        let y0 = y.min(self.height);
        let x1 = (x + w).min(self.width);
        let y1 = (y + h).min(self.height);
        if x1 <= x0 || y1 <= y0 {
            return None;
        }
        let (cw, ch) = (x1 - x0, y1 - y0);
        let mut out = Vec::with_capacity(cw * ch * 4);
        for row in 0..ch {
            let start = ((y0 + row) * self.width + x0) * 4;
            out.extend_from_slice(&self.data[start..start + cw * 4]);
        }
        Some(RgbaImage { width: cw, height: ch, data: out })
    }

    /// Perspective crop of a detector quad into `out_w × out_h` (upright).
    pub fn crop_quad(&self, quad: &Quad, out_w: usize, out_h: usize) -> RgbaImage {
        let data = crate::geom::crop_quad(&self.data, self.width, self.height, quad, out_w, out_h);
        RgbaImage { width: out_w, height: out_h, data }
    }
}

/// The four corners of a text region in source-image coordinates, in `geom::Quad` order.
pub fn axis_aligned_quad(x0: f32, y0: f32, x1: f32, y1: f32) -> Quad {
    Quad::ordered([
        Point::new(x0, y0),
        Point::new(x1, y0),
        Point::new(x1, y1),
        Point::new(x0, y1),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(width: usize, height: usize, value: u8) -> RgbaImage {
        let mut data = vec![0u8; width * height * 4];
        for pixel in data.chunks_mut(4) {
            pixel[0] = value;
            pixel[1] = value;
            pixel[2] = value;
            pixel[3] = 255;
        }
        RgbaImage::new(width, height, data).unwrap()
    }

    #[test]
    fn a_buffer_that_does_not_match_the_dimensions_is_rejected() {
        assert!(RgbaImage::new(2, 2, vec![0; 15]).is_err());
        assert!(RgbaImage::new(0, 2, vec![]).is_err());
        assert!(RgbaImage::new(2, 2, vec![0; 16]).is_ok());
    }

    #[test]
    fn resizing_preserves_a_solid_colour() {
        let img = solid(4, 4, 200);
        let small = img.resized(2, 2);
        assert_eq!((small.width, small.height), (2, 2));
        assert!(small.data.chunks(4).all(|p| p[0] == 200 && p[3] == 255));
    }

    #[test]
    fn cropping_clamps_to_the_image_or_returns_none() {
        let img = solid(4, 4, 10);
        let cropped = img.crop(2, 2, 10, 10).expect("partially inside");
        assert_eq!((cropped.width, cropped.height), (2, 2));
        assert!(img.crop(4, 4, 2, 2).is_none());
    }

    #[test]
    fn an_axis_aligned_quad_is_ordered_then_croppable() {
        let img = solid(8, 8, 33);
        let quad = axis_aligned_quad(1.0, 1.0, 5.0, 4.0);
        let crop = img.crop_quad(&quad, 4, 3);
        assert_eq!((crop.width, crop.height), (4, 3));
        assert!(crop.data.chunks(4).all(|p| p[0] == 33));
    }
}
