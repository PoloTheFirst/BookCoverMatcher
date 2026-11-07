// SPDX-License-Identifier: GPL-3.0-or-later

//! PP-OCRv6 detection: preprocessing, DB probability-map post-processing, quad extraction.
//!
//! Parameters come from the detector's own `inference.yml`
//! (`DBPostProcess thresh 0.2, box_thresh 0.45, unclip_ratio 1.4, max_candidates 3000`) and are
//! configurable, so a model with different values is not silently mis-tuned.
//!
//! The post-processing deliberately does **not** depend on OpenCV: components are labelled with a
//! flood fill, each component's region is grown by the unclip distance and expressed as an
//! axis-aligned quad in *source* coordinates. PaddleOCR uses `cv2.minAreaRect` there, which yields
//! a rotated box; for the upright and vertical text a book cover carries the two agree, and the
//! difference is documented rather than hidden.

use crate::geom::Quad;
use crate::image::{axis_aligned_quad, RgbaImage};

/// Detector input side length (the long edge after resizing); the model is fully convolutional,
/// so this only trades accuracy for latency.
pub const DEFAULT_LIMIT_SIDE: usize = 736;

/// Detector input dimensions must be multiples of this (PaddleOCR's rounding rule).
const STRIDE: usize = 32;

#[derive(Debug, Clone, Copy)]
pub struct DetSettings {
    pub limit_side: usize,
    pub thresh: f32,
    pub box_thresh: f32,
    pub unclip_ratio: f32,
    pub max_candidates: usize,
    pub min_size: f32,
}

impl Default for DetSettings {
    fn default() -> Self {
        Self {
            limit_side: DEFAULT_LIMIT_SIDE,
            thresh: 0.2,
            box_thresh: 0.45,
            unclip_ratio: 1.4,
            max_candidates: 3000,
            min_size: 3.0,
        }
    }
}

/// A detector input tensor plus what is needed to map results back to the source image.
pub struct DetInput {
    /// `[1, 3, h, w]`, RGB normalised with the PaddleOCR ImageNet mean/std, CHW layout.
    pub tensor: Vec<f32>,
    pub width: usize,
    pub height: usize,
    /// Source pixels per model pixel (both axes share the ratio: the aspect ratio is preserved).
    pub scale: f32,
    /// Padding added on the right/bottom, in model pixels.
    pub pad_x: usize,
    pub pad_y: usize,
}

/// Builds the detector tensor: aspect-preserving resize, pad to a multiple of 32, normalise.
pub fn preprocess(image: &RgbaImage, limit_side: usize) -> DetInput {
    let long = image.width.max(image.height) as f32;
    let limit = limit_side.max(STRIDE) as f32;
    let scale = if long > limit { long / limit } else { 1.0 };
    let resized_w = ((image.width as f32 / scale).round() as usize).max(1);
    let resized_h = ((image.height as f32 / scale).round() as usize).max(1);
    let width = ((resized_w + STRIDE - 1) / STRIDE) * STRIDE;
    let height = ((resized_h + STRIDE - 1) / STRIDE) * STRIDE;

    let resized = image.resized(resized_w, resized_h);
    let mut tensor = vec![0f32; 3 * width * height];
    let mean = [0.485f32, 0.456, 0.406];
    let std = [0.229f32, 0.224f32, 0.225f32];
    for y in 0..resized_h {
        for x in 0..resized_w {
            let at = (y * resized_w + x) * 4;
            for c in 0..3 {
                let value = resized.data[at + c] as f32 / 255.0;
                tensor[c * width * height + y * width + x] = (value - mean[c]) / std[c];
            }
        }
    }
    DetInput {
        tensor,
        width,
        height,
        scale,
        pad_x: width - resized_w,
        pad_y: height - resized_h,
    }
}

/// Maps one model-space box back to the source image, undoing padding and scaling.
fn to_source(
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
    input: &DetInput,
    image: &RgbaImage,
) -> Option<Quad> {
    let sx = |v: f32| (v * input.scale).clamp(0.0, image.width as f32);
    let sy = |v: f32| (v * input.scale).clamp(0.0, image.height as f32);
    let (x0, x1) = (sx(x0), sx(x1));
    let (y0, y1) = (sy(y0), sy(y1));
    let quad = axis_aligned_quad(x0, y0, x1, y1);
    quad.is_valid().then_some(quad)
}


/// One detected text region, in source-image coordinates, with its detector score.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Detection {
    pub quad: Quad,
    pub score: f32,
}

/// Turns a DB probability map into text regions.
///
/// `prob` is the model output `[h, w]` (one channel, values 0..1). The steps mirror PaddleOCR's
/// `DBPostProcess`: threshold, label connected components, score each component by the mean
/// probability inside it, drop anything below `box_thresh` or smaller than `min_size`, then grow the
/// surviving region by the unclip distance (`area * unclip_ratio / perimeter`) so the crop keeps
/// the letters' edges, and finally map back to source coordinates.
///
/// The component region is axis-aligned (see the module note); the *quad* the caller receives is
/// still four-corner geometry, so a later min-area-rect upgrade does not change this contract.
pub fn postprocess(
    prob: &[f32],
    prob_w: usize,
    prob_h: usize,
    input: &DetInput,
    image: &RgbaImage,
    settings: &DetSettings,
) -> Vec<Detection> {
    if prob_w == 0 || prob_h == 0 || prob.len() < prob_w * prob_h {
        return Vec::new();
    }
    let model_to_prob_x = input.width as f32 / prob_w as f32;
    let model_to_prob_y = input.height as f32 / prob_h as f32;

    let mut visited = vec![false; prob_w * prob_h];
    let mut found: Vec<Detection> = Vec::new();
    let mut stack: Vec<usize> = Vec::new();

    for start in 0..prob_w * prob_h {
        if visited[start] || prob[start] < settings.thresh {
            continue;
        }
        let mut min_x = prob_w;
        let mut min_y = prob_h;
        let mut max_x = 0usize;
        let mut max_y = 0usize;
        let mut sum = 0f32;
        let mut count = 0usize;
        visited[start] = true;
        stack.push(start);
        while let Some(index) = stack.pop() {
            let (px, py) = (index % prob_w, index / prob_w);
            min_x = min_x.min(px);
            max_x = max_x.max(px);
            min_y = min_y.min(py);
            max_y = max_y.max(py);
            sum += prob[index];
            count += 1;
            let x0 = px.saturating_sub(1);
            let y0 = py.saturating_sub(1);
            let x1 = (px + 1).min(prob_w - 1);
            let y1 = (py + 1).min(prob_h - 1);
            for ny in y0..=y1 {
                for nx in x0..=x1 {
                    let next = ny * prob_w + nx;
                    if !visited[next] && prob[next] >= settings.thresh {
                        visited[next] = true;
                        stack.push(next);
                    }
                }
            }
        }
        if count == 0 {
            continue;
        }
        let score = sum / count as f32;
        if score < settings.box_thresh {
            continue;
        }
        let bx0 = min_x as f32 * model_to_prob_x;
        let by0 = min_y as f32 * model_to_prob_y;
        let bx1 = (max_x as f32 + 1.0) * model_to_prob_x;
        let by1 = (max_y as f32 + 1.0) * model_to_prob_y;
        let width = bx1 - bx0;
        let height = by1 - by0;
        if width < settings.min_size || height < settings.min_size {
            continue;
        }
        let perimeter = 2.0 * (width + height);
        let distance = if perimeter > 0.0 {
            (width * height) * settings.unclip_ratio / perimeter
        } else {
            0.0
        };
        if let Some(quad) = to_source(
            bx0 - distance,
            by0 - distance,
            bx1 + distance,
            by1 + distance,
            input,
            image,
        ) {
            found.push(Detection { quad, score });
            if found.len() >= settings.max_candidates {
                break;
            }
        }
    }

    found
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blank_image() -> RgbaImage {
        RgbaImage::new(200, 100, vec![255u8; 200 * 100 * 4]).expect("image")
    }

    /// A detector input whose tensor is unused (no model is involved), so `postprocess` can be
    /// exercised on a hand-written probability map.
    fn input_for(width: usize, height: usize, scale: f32) -> DetInput {
        DetInput { tensor: Vec::new(), width, height, scale, pad_x: 0, pad_y: 0 }
    }

    #[test]
    fn a_single_blob_becomes_one_region_mapped_back_to_source_pixels() {
        let (pw, ph) = (20usize, 10usize);
        let mut prob = vec![0f32; pw * ph];
        for y in 3..6 {
            for x in 4..10 {
                prob[y * pw + x] = 0.9;
            }
        }
        let image = blank_image();
        // One model pixel is half a source pixel.
        let input = input_for(pw, ph, 0.5);
        let found = postprocess(&prob, pw, ph, &input, &image, &DetSettings::default());
        assert_eq!(found.len(), 1);
        let quad = found[0].quad;
        // Model x 4..10 -> source 2..5; model y 3..6 -> source 1.5..3; plus the unclip margin.
        assert!(quad.min_x() <= 2.0 && quad.max_x() >= 5.0);
        assert!(quad.min_y() <= 1.5 && quad.max_y() >= 3.0);
        assert!((found[0].score - 0.9).abs() < 1e-5);
    }

    #[test]
    fn low_confidence_blobs_are_dropped() {
        let (pw, ph) = (10usize, 10usize);
        let mut prob = vec![0f32; pw * ph];
        // Above thresh (0.2) but below box_thresh (0.45).
        for y in 2..6 {
            for x in 2..8 {
                prob[y * pw + x] = 0.3;
            }
        }
        let image = blank_image();
        let found = postprocess(&prob, pw, ph, &input_for(pw, ph, 1.0), &image, &DetSettings::default());
        assert!(found.is_empty());
    }

    #[test]
    fn separate_text_lines_stay_separate_and_a_stray_pixel_is_dropped() {
        let (pw, ph) = (40usize, 20usize);
        let mut prob = vec![0f32; pw * ph];
        for y in 2..6 {
            for x in 2..12 {
                prob[y * pw + x] = 0.9;
            }
        }
        for y in 12..16 {
            for x in 2..12 {
                prob[y * pw + x] = 0.9;
            }
        }
        prob[3 * pw + 35] = 0.99; // one lone pixel, smaller than min_size
        let image = blank_image();
        let found = postprocess(&prob, pw, ph, &input_for(pw, ph, 1.0), &image, &DetSettings::default());
        assert_eq!(found.len(), 2, "one region per text line, stray pixel dropped");
    }

    #[test]
    fn an_undersized_probability_map_yields_nothing() {
        let image = blank_image();
        let found =
            postprocess(&[0.9; 4], 10, 10, &input_for(10, 10, 1.0), &image, &DetSettings::default());
        assert!(found.is_empty());
    }

    #[test]
    fn preprocess_keeps_the_aspect_ratio_and_rounds_to_the_stride() {
        let image = RgbaImage::new(1000, 500, vec![128u8; 1000 * 500 * 4]).unwrap();
        let input = preprocess(&image, 736);
        assert_eq!(input.width % 32, 0);
        assert_eq!(input.height % 32, 0);
        assert!(input.width >= 736, "the long edge reaches the limit");
        assert!(input.scale > 1.0, "a 1000 px wide image is downscaled");
        assert_eq!(input.tensor.len(), 3 * input.width * input.height);
        // The right/bottom padding is included in the canvas but not in the resized area.
        assert!(input.pad_x < 32 && input.pad_y < 32);
    }

    #[test]
    fn preprocess_of_a_small_image_does_not_upscale_it() {
        let image = RgbaImage::new(64, 32, vec![10u8; 64 * 32 * 4]).unwrap();
        let input = preprocess(&image, 736);
        assert_eq!(input.scale, 1.0);
        assert_eq!((input.width, input.height), (64, 32));
    }

    #[test]
    fn a_solid_white_image_normalises_to_the_expected_tensor_values() {
        let image = RgbaImage::new(32, 32, vec![255u8; 32 * 32 * 4]).unwrap();
        let input = preprocess(&image, 736);
        // (1.0 - 0.485) / 0.229 for the red channel of a pure white pixel.
        let red = input.tensor[0];
        assert!((red - (1.0 - 0.485) / 0.229).abs() < 1e-4, "got {red}");
    }
}
