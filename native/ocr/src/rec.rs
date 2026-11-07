//! PP-OCRv6 recognition: preprocessing for the exported recogniser and decoding of its output.
//!
//! # What the model expects
//!
//! From `PP-OCRv6_medium_rec_onnx/inference.yml`:
//!
//! ```yaml
//! PreProcess:  DecodeImage(img_mode: BGR) → RecResizeImg(image_shape: [3, 48, 320])
//! PostProcess: CTCLabelDecode(character_dict: …18,704 entries…)
//! ```
//!
//! `RecResizeImg` keeps the aspect ratio, scales the crop to height 48 and pads the **right** side to
//! width 320 after the plain `(v/255 - 0.5) / 0.5` normalisation (no ImageNet mean/std here, unlike
//! the detector). The channel order stays **BGR** because the decoder produced BGR, and the padding
//! value is 0 — mid-grey after normalisation, which is what PaddleOCR writes.
//!
//! # Class layout (reconciled against the model, not assumed)
//!
//! The exported graph ends in `MatMul(linear_8.w_0 [192, 18710]) + bias [18710]` followed by
//! `Softmax(axis = 2)`, so the recogniser emits **probabilities**, not logits, over 18,710 classes.
//! Those classes are PaddleOCR's `CTCLabelDecode` index space:
//!
//! ```text
//! class 0                CTC blank                       (`["blank"] + dict_character`)
//! classes 1..=18,708     character_dict[0..=18,707]      official ppocrv6_dict.txt, file order
//! class 18,709           ' '                             (`use_space_char: true`)
//! ```
//!
//! `18,708 + 1 blank + 1 space = 18,710` — exactly the graph's output width, so the dictionary is
//! the model's own vocabulary and no class is truncated, padded or explained away. [`decode_output`]
//! keeps refusing when the two disagree: that mismatch would otherwise turn into plausible-looking
//! text with every character shifted.

use crate::dict::Charset;
use crate::image::RgbaImage;

/// Recogniser input height (fixed by the model).
pub const REC_HEIGHT: usize = 48;
/// Recogniser input width (the model takes a fixed 320-wide canvas; wider crops are squeezed).
pub const REC_MAX_WIDTH: usize = 320;

/// One recogniser input tensor plus the geometry needed to interpret the output.
pub struct RecInput {
    /// `[1, 3, 48, REC_MAX_WIDTH]`, BGR, normalised to -1..1, right-padded with 0.
    pub tensor: Vec<f32>,
    /// Number of valid (non-padding) columns, proportional to the original aspect ratio.
    pub valid_width: usize,
}

impl RecInput {
    pub fn tensor_len(&self) -> usize {
        3 * REC_HEIGHT * REC_MAX_WIDTH
    }
}

/// Builds the recogniser tensor for one perspective-corrected crop.
pub fn preprocess(crop: &RgbaImage) -> Result<RecInput, String> {
    if crop.width == 0 || crop.height == 0 {
        return Err("recognition crop is empty".into());
    }
    // Aspect-preserving: height becomes 48, width follows, and anything wider than the canvas is
    // squeezed to it (PaddleOCR does the same rather than truncating the text line).
    let ratio = crop.width as f32 / crop.height as f32;
    let valid_width = ((REC_HEIGHT as f32 * ratio).ceil() as usize)
        .max(1)
        .min(REC_MAX_WIDTH);
    let resized = crop.resized(valid_width, REC_HEIGHT);

    let mut tensor = vec![0f32; 3 * REC_HEIGHT * REC_MAX_WIDTH];
    for y in 0..REC_HEIGHT {
        for x in 0..resized.width {
            let at = (y * resized.width + x) * 4;
            // BGR: the model was exported from a BGR pipeline.
            let channels = [resized.data[at + 2], resized.data[at + 1], resized.data[at]];
            for (c, value) in channels.iter().enumerate() {
                let normalised = (*value as f32 / 255.0 - 0.5) / 0.5;
                tensor[c * REC_HEIGHT * REC_MAX_WIDTH + y * REC_MAX_WIDTH + x] = normalised;
            }
        }
    }
    Ok(RecInput { tensor, valid_width: resized.width })
}

/// Softmax over one timestep.
fn softmax_into(row: &[f32], out: &mut Vec<f32>) {
    out.clear();
    let max = row.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    let mut sum = 0f32;
    for value in row {
        let e = (value - max).exp();
        out.push(e);
        sum += e;
    }
    if sum > 0.0 {
        for value in out.iter_mut() {
            *value /= sum;
        }
    }
}

/// True when one output row is already a probability distribution.
///
/// The exported PP-OCRv6 graph ends in `Softmax`, so its rows are probabilities. Softmaxing those a
/// second time would not change the text (argmax is unchanged) but would flatten every score towards
/// `1 / classes`: with 18,710 classes a 0.99 probability would be reported as ~0.00005, i.e. "0 %".
/// Detecting the row keeps the confidence honest and still handles an export whose head is a plain
/// linear layer (raw logits: values outside 0..1 or a row that does not sum to 1).
fn is_probability_row(row: &[f32]) -> bool {
    let mut sum = 0.0f32;
    for value in row {
        if !(*value >= 0.0 && *value <= 1.0) {
            return false;
        }
        sum += *value;
    }
    (sum - 1.0).abs() <= 1e-3
}

/// Decodes a CTC output tensor into text.
///
/// `logits` is `[steps * classes]` row-major logits **or** probabilities; each row is inspected, so
/// the reported confidence is a real probability either way. `classes` must equal
/// `charset.class_count()`; the mismatch is reported rather than papered over. A non-finite value is
/// rejected instead of being decoded as blanks, because NaN/inf means the graph itself misbehaved.
pub fn decode_output(
    logits: &[f32],
    steps: usize,
    classes: usize,
    charset: &Charset,
) -> Result<(String, f32), String> {
    if steps == 0 || classes == 0 {
        return Err("recogniser produced an empty output tensor".into());
    }
    if logits.len() < steps * classes {
        return Err(format!(
            "recogniser output has {} values, expected {}x{}",
            logits.len(),
            steps,
            classes
        ));
    }
    if let Some(at) = logits[..steps * classes].iter().position(|v| !v.is_finite()) {
        return Err(format!(
            "recogniser produced a non-finite score ({}) at index {at}; refusing to decode",
            logits[at]
        ));
    }
    let expected = charset.class_count();
    if classes != expected {
        return Err(format!(
            "recogniser outputs {classes} classes but the dictionary maps {expected} \
             ({} characters + blank{}); refusing to decode",
            charset.dict_len(),
            if expected == charset.dict_len() + 2 { " + space" } else { "" }
        ));
    }
    let mut rows: Vec<Vec<f32>> = Vec::with_capacity(steps);
    let mut probabilities: Vec<f32> = Vec::with_capacity(classes);
    for step in 0..steps {
        let row = &logits[step * classes..(step + 1) * classes];
        if is_probability_row(row) {
            // The graph already ends in Softmax: use the distribution as it is.
            rows.push(row.to_vec());
        } else {
            softmax_into(row, &mut probabilities);
            rows.push(probabilities.clone());
        }
    }
    Ok(charset.decode_ctc(&rows))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn filled(width: usize, height: usize, rgba: [u8; 4]) -> RgbaImage {
        let mut data = Vec::with_capacity(width * height * 4);
        for _ in 0..width * height {
            data.extend_from_slice(&rgba);
        }
        RgbaImage::new(width, height, data).expect("image")
    }

    fn charset() -> Charset {
        Charset::from_dict_text("a\nb\nあ\n", true).unwrap() // 5 classes
    }

    #[test]
    fn an_empty_crop_is_rejected() {
        let zero = RgbaImage { width: 0, height: 0, data: Vec::new() };
        assert!(preprocess(&zero).is_err());
    }

    #[test]
    fn the_tensor_is_three_by_48_by_320_and_right_padded_with_zero() {
        let crop = filled(100, 48, [255, 255, 255, 255]);
        let input = preprocess(&crop).expect("preprocess");
        assert_eq!(input.tensor.len(), input.tensor_len());
        assert_eq!(input.valid_width, 100);
        // Column 100 must be padding in every channel and row.
        for c in 0..3 {
            for y in 0..REC_HEIGHT {
                assert_eq!(input.tensor[c * REC_HEIGHT * REC_MAX_WIDTH + y * REC_MAX_WIDTH + 100], 0.0);
            }
        }
    }

    #[test]
    fn white_normalises_to_one_and_black_to_minus_one() {
        let white = preprocess(&filled(48, 48, [255, 255, 255, 255])).unwrap();
        let black = preprocess(&filled(48, 48, [0, 0, 0, 255])).unwrap();
        assert!((white.tensor[0] - 1.0).abs() < 1e-6);
        assert!((black.tensor[0] + 1.0).abs() < 1e-6);
        assert_eq!(white.valid_width, 48, "a square crop stays square");
    }

    #[test]
    fn channels_are_bgr_not_rgb() {
        // Pure red rgba(255,0,0): B=0, G=0, R=255 -> channel 0 = -1, channel 2 = +1.
        let crop = preprocess(&filled(48, 48, [255, 0, 0, 255])).unwrap();
        let plane = REC_HEIGHT * REC_MAX_WIDTH;
        assert!((crop.tensor[0] + 1.0).abs() < 1e-6, "blue plane comes from byte 2");
        assert!((crop.tensor[2 * plane] - 1.0).abs() < 1e-6, "red plane comes from byte 0");
    }

    #[test]
    fn a_wide_crop_is_squeezed_to_the_canvas_width() {
        let crop = preprocess(&filled(2000, 40, [10, 10, 10, 255])).unwrap();
        assert_eq!(crop.valid_width, REC_MAX_WIDTH);
        assert_eq!(crop.tensor.len(), crop.tensor_len());
    }

    #[test]
    fn a_tall_crop_keeps_a_narrow_valid_width() {
        let crop = preprocess(&filled(12, 48, [10, 10, 10, 255])).unwrap();
        assert_eq!(crop.valid_width, 12);
    }

    #[test]
    fn decoding_rejects_a_class_count_that_does_not_match_the_dictionary() {
        let cs = charset(); // 5 classes
        let err = decode_output(&vec![0.0; 10 * 4], 10, 4, &cs).unwrap_err();
        assert!(err.contains('4') && err.contains('5'), "message names both counts: {err}");
    }

    #[test]
    fn decoding_rejects_a_truncated_or_empty_tensor() {
        let cs = charset();
        assert!(decode_output(&[0.0; 7], 10, 5, &cs).is_err());
        assert!(decode_output(&[], 0, 5, &cs).is_err());
    }

    #[test]
    fn decoding_maps_the_model_space_through_the_dictionary() {
        let cs = charset(); // 1=a 2=b 3=あ 4=space, 0=blank
        let (steps, classes) = (4usize, 5usize);
        let mut logits = vec![0f32; steps * classes];
        for (step, class) in [(0usize, 1usize), (1, 0), (2, 2), (3, 3)] {
            logits[step * classes + class] = 9.0;
        }
        let (text, confidence) = decode_output(&logits, steps, classes, &cs).unwrap();
        assert_eq!(text, "abあ");
        assert!(confidence > 0.9, "softmax of a dominant logit is close to 1: {confidence}");
    }

    #[test]
    fn an_all_blank_output_decodes_to_empty_text_with_zero_confidence() {
        let cs = charset();
        let (steps, classes) = (6usize, 5usize);
        let mut logits = vec![-9f32; steps * classes];
        for step in 0..steps {
            logits[step * classes] = 9.0; // blank wins
        }
        let (text, confidence) = decode_output(&logits, steps, classes, &cs).unwrap();
        assert_eq!(text, "");
        assert_eq!(confidence, 0.0);
    }

    #[test]
    fn a_probability_row_from_the_graphs_softmax_is_used_as_it_is() {
        // The PP-OCRv6 export ends in Softmax, so the tensor already holds probabilities. Softmaxing
        // it again would keep argmax but collapse the score towards 1/classes: this test pins that
        // the reported confidence stays the model's own 0.90 instead of ~0.0002.
        let cs = charset();
        let (steps, classes) = (2usize, 5usize);
        let mut probs = vec![0f32; steps * classes];
        probs[0..5].copy_from_slice(&[0.0, 0.90, 0.05, 0.05, 0.0]); // 'a'
        probs[5..10].copy_from_slice(&[1.0, 0.0, 0.0, 0.0, 0.0]); // blank
        let (text, confidence) = decode_output(&probs, steps, classes, &cs).unwrap();
        assert_eq!(text, "a");
        assert!((confidence - 0.90).abs() < 1e-6, "confidence is the row's own probability: {confidence}");
    }

    #[test]
    fn a_non_finite_score_is_rejected_instead_of_decoded_as_blank() {
        let cs = charset();
        let (steps, classes) = (2usize, 5usize);
        for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            let mut logits = vec![0f32; steps * classes];
            logits[classes + 1] = bad;
            let err = decode_output(&logits, steps, classes, &cs).expect_err("must refuse");
            assert!(err.contains("non-finite"), "the error names the cause: {err}");
        }
    }

    #[test]
    fn a_tensor_longer_than_the_declared_shape_is_decoded_by_its_declared_rows() {
        // The engine passes the graph's own dimensions; extra trailing values must not shift anything.
        let cs = charset();
        let (steps, classes) = (1usize, 5usize);
        let mut logits = vec![0f32; classes + 3];
        logits[2] = 9.0; // 'b'
        assert_eq!(decode_output(&logits, steps, classes, &cs).unwrap().0, "b");
    }
}

