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

    /// Rotates the image 90° counter-clockwise: `(x, y)` becomes `(y, width - 1 - x)`.
    ///
    /// PP-OCR's recogniser takes horizontal lines. A vertical Japanese/Chinese column is therefore
    /// turned a quarter turn before recognition — the same handling PaddleOCR's predictor applies to
    /// boxes that are at least 1.5× taller than they are wide — so the characters arrive as a row
    /// instead of being squeezed into a narrow canvas.
    ///
    /// The direction matters and was measured, not assumed: clockwise puts the *bottom* of the column
    /// first, so the text comes back reversed and garbled (`tools/ocr-reference/probe_vertical.py`
    /// reads 0.313 'ccw-style' text the wrong way round, 0.877 correct text counter-clockwise, and an
    /// exact reading when the column is split into character cells).
    pub fn rotated_counter_clockwise(&self) -> RgbaImage {
        let (new_w, new_h) = (self.height, self.width);
        let mut out = vec![0u8; self.data.len()];
        for y in 0..self.height {
            for x in 0..self.width {
                let src = (y * self.width + x) * 4;
                // (x, y) -> (y, width - 1 - x): the column's first character becomes leftmost.
                let dst = ((self.width - 1 - x) * new_w + y) * 4;
                out[dst..dst + 4].copy_from_slice(&self.data[src..src + 4]);
            }
        }
        RgbaImage { width: new_w, height: new_h, data: out }
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

    /// A 2-wide, 3-tall image whose top-left pixel is marked: after a counter-clockwise quarter turn
    /// the marked pixel must be at the bottom-left, and the dimensions must swap.
    #[test]
    fn a_counter_clockwise_rotation_moves_the_top_left_pixel_to_the_bottom_left() {
        let (w, h) = (2usize, 3usize);
        let mut data = vec![9u8; w * h * 4];
        data[0..4].copy_from_slice(&[200, 10, 20, 255]);
        let img = RgbaImage::new(w, h, data).unwrap();

        let rotated = img.rotated_counter_clockwise();

        assert_eq!((rotated.width, rotated.height), (3, 2));
        let at = |x: usize, y: usize| {
            let start = (y * rotated.width + x) * 4;
            rotated.data[start..start + 3].to_vec()
        };
        assert_eq!(at(0, 1), vec![200, 10, 20], "the marker lands bottom-left");
        assert_eq!(at(2, 0), vec![9, 9, 9], "the rest is untouched");
    }
}
