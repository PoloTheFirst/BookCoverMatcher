// SPDX-License-Identifier: GPL-3.0-or-later

//! The inference engine: detector + recogniser sessions and the full OCR pipeline.
//!
//! # Runtime reuse
//!
//! `ort` is built with `load-dynamic` and `api-20`, so this crate links **no** ONNX Runtime. On
//! Android the runtime is the `libonnxruntime.so` the application already ships (1.20.0) and has
//! loaded; here [`RuntimeConfig`] lets the caller name it (`init_from(path)`) or leave it to the
//! default search (`init()`), which honours `ORT_DYLIB_PATH` on the host.
//!
//! # Session ownership and concurrency
//!
//! `Session::run` requires `&mut self` in this release of `ort` (ONNX Runtime sessions are not safe
//! for concurrent `run`). The engine therefore owns **one** detector session and **one** recogniser
//! session and exposes `&mut self` for inference: inference is serialized by the borrow checker
//! instead of by an unverifiable lock. Callers that need parallelism create more engines.

use std::path::PathBuf;

use ort::session::Session;
use ort::value::Tensor;

use crate::det::{self, DetSettings};
use crate::dict::Charset;
use crate::geom::Quad;
use crate::image::RgbaImage;
use crate::order::{self, Region};
use crate::rec;

/// Where the runtime and the two models live.
#[derive(Debug, Clone)]
pub struct ModelPaths {
    pub detector: PathBuf,
    pub recognizer: PathBuf,
    pub dictionary: PathBuf,
}

impl ModelPaths {
    pub fn new(detector: impl Into<PathBuf>, recognizer: impl Into<PathBuf>, dictionary: impl Into<PathBuf>) -> Self {
        Self { detector: detector.into(), recognizer: recognizer.into(), dictionary: dictionary.into() }
    }
}

/// How the runtime should be located.
#[derive(Debug, Clone, Default)]
pub enum RuntimeConfig {
    /// Let `ort` decide (`ORT_DYLIB_PATH`, then the platform search path).
    #[default]
    Default,
    /// Load a specific library file (Android: the app's `libonnxruntime.so`).
    FromPath(PathBuf),
}

/// One recognised text region.
#[derive(Debug, Clone)]
pub struct OcrLine {
    pub text: String,
    /// Mean softmax probability of the emitted characters.
    pub score: f32,
    /// Region in **source image** coordinates.
    pub quad: Quad,
    /// True when the region is a vertical column (tall and narrow).
    pub vertical: bool,
    /// 1-based reading position.
    pub rank: usize,
}

/// The whole result of one image.
#[derive(Debug, Clone, Default)]
pub struct OcrOutcome {
    pub lines: Vec<OcrLine>,
    /// How many text regions the detector proposed (before recognition dropped empty ones).
    pub regions_detected: usize,
    /// Per-region failures: the text is lost, the rest of the image is not.
    pub region_errors: Vec<String>,
}

impl OcrOutcome {
    /// Lines joined in reading order — the shape the existing query generator wants.
    pub fn text(&self) -> String {
        self.lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join("\n")
    }
}

/// Metadata read back from the models at load time, recorded so a report can quote it.
#[derive(Debug, Clone)]
pub struct ModelInfo {
    pub det_input: String,
    pub det_input_shape: Vec<i64>,
    pub det_output: String,
    pub det_output_shape: Vec<i64>,
    pub rec_input: String,
    pub rec_input_shape: Vec<i64>,
    pub rec_output: String,
    pub rec_output_shape: Vec<i64>,
    pub rec_classes: usize,
    pub dict_len: usize,
    pub use_space_char: bool,
}

pub struct OcrEngine {
    // Held so the runtime outlives the sessions.
    _environment: ort::environment::Environment,
    detector: Session,
    recognizer: Session,
    charset: Charset,
    info: ModelInfo,
    settings: DetSettings,
}

impl OcrEngine {
    /// Opens the runtime and both models, validating everything the two must agree on.
    pub fn open(paths: &ModelPaths, runtime: RuntimeConfig) -> Result<Self, String> {
        for path in [&paths.detector, &paths.recognizer, &paths.dictionary] {
            if !path.is_file() {
                return Err(format!("model file is missing: {}", path.display()));
            }
        }
        let environment = match runtime {
            RuntimeConfig::Default => ort::init()
                .build()
                .map_err(|e| format!("ONNX Runtime could not be initialized: {e}"))?,
            RuntimeConfig::FromPath(library) => {
                let shown = library.display().to_string();
                ort::init_from(library)
                    .map_err(|e| format!("ONNX Runtime could not be loaded from {shown}: {e}"))?
                    .build()
                    .map_err(|e| format!("ONNX Runtime could not be initialized: {e}"))?
            }
        };

        let detector = load_session(&environment, &paths.detector, "detector")?;
        let recognizer = load_session(&environment, &paths.recognizer, "recognizer")?;

        let dict_text = std::fs::read_to_string(&paths.dictionary)
            .map_err(|e| format!("dictionary {} could not be read: {e}", paths.dictionary.display()))?;
        // The dictionary decides how many classes are possible; the graph decides which of the two
        // valid counts it is. Both are checked rather than assumed.
        let raw_dict_len = dict_text.lines().filter(|l| !l.trim_end_matches('\r').is_empty()).count();

        let det_input = descriptor(detector.inputs().first(), "detector input")?;
        let det_output = descriptor(detector.outputs().first(), "detector output")?;
        let rec_input = descriptor(recognizer.inputs().first(), "recognizer input")?;
        // A PP-OCRv6 recogniser can expose more than one head (the config declares
        // `MultiLabelEncode` with `gtc_encode: NRTRLabelEncode`). The CTC head is the one whose last
        // dimension matches the dictionary, so it is selected by that rule — never by index.
        let mut rec_outputs: Vec<(String, Vec<i64>)> = Vec::new();
        for outlet in recognizer.outputs() {
            rec_outputs.push(descriptor(Some(outlet), "recognizer output")?);
        }
        let all_outputs = rec_outputs
            .iter()
            .map(|(name, shape)| format!("{name}{shape:?}"))
            .collect::<Vec<_>>()
            .join(", ");
        let (rec_output, rec_classes) = rec_outputs
            .iter()
            .find_map(|(name, shape)| {
                let classes = *shape.last().unwrap_or(&-1);
                ((classes == raw_dict_len as i64 + 1) || (classes == raw_dict_len as i64 + 2))
                    .then(|| (name.clone(), shape.clone(), classes as usize))
            })
            .map(|(name, shape, classes)| ((name, shape), classes))
            .ok_or_else(|| {
                format!(
                    "no recogniser output matches the dictionary: it has {raw_dict_len} characters, \
                     so a CTC head must emit {} or {} classes, but the model exposes [{all_outputs}]",
                    raw_dict_len + 1,
                    raw_dict_len + 2
                )
            })?;
        let use_space_char = if rec_classes == raw_dict_len + 2 {
            true
        } else if rec_classes == raw_dict_len + 1 {
            false
        } else {
            return Err(format!(
                "dictionary has {raw_dict_len} characters, which maps to {} or {} classes, \
                 but the recogniser emits {rec_classes} classes: model and dictionary disagree",
                raw_dict_len + 1,
                raw_dict_len + 2
            ));
        };
        let charset = Charset::from_dict_text(&dict_text, use_space_char)?;
        debug_assert_eq!(charset.class_count(), rec_classes);

        let info = ModelInfo {
            det_input: det_input.0.clone(),
            det_input_shape: det_input.1.clone(),
            det_output: det_output.0.clone(),
            det_output_shape: det_output.1.clone(),
            rec_input: rec_input.0.clone(),
            rec_input_shape: rec_input.1.clone(),
            rec_output: rec_output.0.clone(),
            rec_output_shape: rec_output.1.clone(),
            rec_classes,
            dict_len: charset.dict_len(),
            use_space_char,
        };
        Ok(Self {
            _environment: environment,
            detector,
            recognizer,
            charset,
            info,
            settings: DetSettings::default(),
        })
    }

    pub fn info(&self) -> &ModelInfo {
        &self.info
    }


    /// Runs the whole pipeline on one image.
    pub fn recognise(&mut self, image: &RgbaImage) -> Result<OcrOutcome, String> {
        let det_input = det::preprocess(image, self.settings.limit_side);
        // The detector borrow ends with this block, so the recogniser can be borrowed mutably below.
        let detections = {
            let shape = [1i64, 3, det_input.height as i64, det_input.width as i64];
            let tensor = Tensor::from_array((shape, det_input.tensor.clone().into_boxed_slice()))
                .map_err(|e| format!("detector input tensor: {e}"))?;
            let name = self.info.det_input.clone();
            let outputs = self
                .detector
                .run(ort::inputs![name.as_str() => tensor])
                .map_err(|e| format!("detector inference failed: {e}"))?;
            let (det_shape, prob) = outputs[0]
                .try_extract_tensor::<f32>()
                .map_err(|e| format!("detector output could not be read: {e}"))?;
            let dims: Vec<i64> = det_shape.to_vec();
            let (prob_h, prob_w) = match dims.len() {
                4 if dims[2] > 0 && dims[3] > 0 => (dims[2] as usize, dims[3] as usize),
                3 if dims[1] > 0 && dims[2] > 0 => (dims[1] as usize, dims[2] as usize),
                _ => {
                    // Fully dynamic spatial output: derive a square map from the element count.
                    let side = ((prob.len() as f32).sqrt()).round().max(1.0) as usize;
                    (side, side)
                }
            };
            det::postprocess(prob, prob_w, prob_h, &det_input, image, &self.settings)
        };
        let mut outcome = OcrOutcome { regions_detected: detections.len(), ..Default::default() };
        if detections.is_empty() {
            return Ok(outcome);
        }

        let mut found: Vec<(String, f32, Quad, bool)> = Vec::new();
        for detection in &detections {
            match self.recognise_region(image, &detection.quad) {
                Ok((text, score)) => {
                    let trimmed = text.trim();
                    if !trimmed.is_empty() {
                        let vertical = detection.quad.is_vertical();
                        found.push((trimmed.to_string(), score, detection.quad, vertical));
                    }
                }
                // A region that fails is reported, not fatal: the rest of the cover still counts.
                Err(message) => outcome.region_errors.push(message),
            }
        }
        if found.is_empty() {
            return Ok(outcome);
        }
        let regions: Vec<Region> = found
            .iter()
            .map(|(_, _, quad, vertical)| Region { quad: *quad, vertical: *vertical })
            .collect();
        let mut lines: Vec<OcrLine> = Vec::with_capacity(found.len());
        for ordered in order::reading_order(&regions) {
            let (text, score, quad, vertical) = found[ordered.index].clone();
            lines.push(OcrLine { text, score, quad, vertical, rank: ordered.rank });
        }
        outcome.lines = lines;
        Ok(outcome)
    }

    /// Crops one region and recognises it.
    fn recognise_region(&mut self, image: &RgbaImage, quad: &Quad) -> Result<(String, f32), String> {
        if !quad.is_valid() {
            return Err("detector produced a degenerate region".into());
        }
        let (width, height) = quad.rect_size();
        let crop_w = width.round().max(8.0) as usize;
        let crop_h = height.round().max(8.0) as usize;
        let crop = image.crop_quad(quad, crop_w, crop_h);
        let input = rec::preprocess(&crop)?;
        let shape = [1i64, 3, rec::REC_HEIGHT as i64, rec::REC_MAX_WIDTH as i64];
        let tensor = Tensor::from_array((shape, input.tensor.into_boxed_slice()))
            .map_err(|e| format!("recognizer input tensor: {e}"))?;
        let name = self.info.rec_input.clone();
        let outputs = self
            .recognizer
            .run(ort::inputs![name.as_str() => tensor])
            .map_err(|e| format!("recognizer inference failed: {e}"))?;
        let (rec_shape, logits) = outputs[0]
            .try_extract_tensor::<f32>()
            .map_err(|e| format!("recognizer output could not be read: {e}"))?;
        let dims: Vec<usize> = rec_shape.iter().map(|d| (*d).max(0) as usize).collect();
        let (steps, classes) = match dims.len() {
            3 => (dims[1], dims[2]),
            2 => (dims[0], dims[1]),
            _ => return Err(format!("recognizer returned an unexpected rank: {dims:?}")),
        };
        rec::decode_output(logits, steps, classes, &self.charset)
    }
}

/// Input/output name and shape, keeping `-1` for a dynamic dimension.
fn descriptor(value: Option<&ort::value::Outlet>, what: &str) -> Result<(String, Vec<i64>), String> {
    let outlet = value.ok_or_else(|| format!("{what} is missing from the model"))?;
    let name = outlet.name().to_string();
    let shape = outlet
        .dtype()
        .tensor_shape()
        .map(|dims| dims.to_vec())
        .unwrap_or_default();
    Ok((name, shape))
}

/// Builds one session with the shared thread policy.
fn load_session(
    environment: &ort::environment::Environment,
    path: &std::path::Path,
    what: &str,
) -> Result<Session, String> {
    let mut builder = Session::builder(environment)
        .map_err(|e| format!("{what} session builder: {e}"))?;
    builder = builder
        .with_intra_threads(threads())
        .map_err(|e| format!("{what} thread configuration: {e}"))?;
    builder
        .commit_from_file(path)
        .map_err(|e| format!("{what} {} could not be loaded: {e}", path.display()))
}

/// Threads per session: enough to use a phone's big cores, few enough to leave the UI responsive.
fn threads() -> usize {
    std::thread::available_parallelism().map(|n| n.get().clamp(1, 4)).unwrap_or(2)
}

