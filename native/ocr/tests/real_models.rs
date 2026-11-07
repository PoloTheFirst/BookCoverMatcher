//! Real-model inference test: PP-OCRv6 Medium, actual ONNX files, actual ONNX Runtime.
//!
//! This test is `#[ignore]`d on purpose. It needs three downloaded artefacts (139 MB of weights)
//! and an ONNX Runtime shared library, so it must never run in the default suite and must never be
//! reported as passing when it did not run. Execute it explicitly:
//!
//! ```powershell
//! $env:BC_OCR_MODELS = "$env:TEMP\ppocrv6"          # det.onnx, rec.onnx
//! $env:BC_OCR_DICT   = "native\ocr\assets\ppocrv6_dict.txt"
//! $env:BC_OCR_IMAGE  = "app\src\androidTest\assets\cover.jpg"
//! $env:ORT_DYLIB_PATH = "$env:TEMP\ort120\onnxruntime-win-x64-1.20.0\lib\onnxruntime.dll"
//! cargo test --test real_models -- --ignored --nocapture
//! ```
//!
//! The optional `BC_OCR_CROPS` directory (rendered by `tools/make-ocr-crops.py`) adds the
//! multilingual smoke test: Japanese, Traditional Chinese, Simplified Chinese and English crops.
//!
//! Env overrides: `BC_OCR_DET`, `BC_OCR_REC`, `BC_OCR_DICT` point at individual files instead of
//! `BC_OCR_MODELS`.

use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard};

use bc_ocr::engine::{ModelPaths, OcrEngine, RuntimeConfig};
use bc_ocr::image::RgbaImage;
use bc_ocr::order::{reading_order, Region};

/// ONNX Runtime permits exactly one live environment per process, and `cargo test` runs the tests of
/// one binary in parallel threads. Each test therefore takes this lock for its whole body; without it
/// the harness itself fails with `only one environment is allowed per process`, which says nothing
/// about the models. Poisoning is ignored so that one failing test does not hide the others.
static RUNTIME: Mutex<()> = Mutex::new(());

fn runtime_guard() -> MutexGuard<'static, ()> {
    RUNTIME.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn env_path(key: &str) -> Option<PathBuf> {
    std::env::var_os(key).map(PathBuf::from)
}

fn models() -> Option<ModelPaths> {
    let base = env_path("BC_OCR_MODELS")?;
    Some(ModelPaths {
        detector: env_path("BC_OCR_DET").unwrap_or_else(|| base.join("det.onnx")),
        recognizer: env_path("BC_OCR_REC").unwrap_or_else(|| base.join("rec.onnx")),
        dictionary: env_path("BC_OCR_DICT").unwrap_or_else(|| base.join("ppocrv6_dict.txt")),
    })
}

fn load_image(path: &PathBuf) -> RgbaImage {
    let decoded = image::open(path).unwrap_or_else(|e| panic!("image {} could not decode: {e}", path.display()));
    let rgba = decoded.to_rgba8();
    let (width, height) = (rgba.width() as usize, rgba.height() as usize);
    RgbaImage::new(width, height, rgba.into_raw()).expect("rgba buffer")
}

#[test]
#[ignore = "requires the PP-OCRv6 ONNX models, the dictionary asset and an ONNX Runtime library"]
fn ppocrv6_medium_reads_a_real_cover() {
    let _serial = runtime_guard();
    let paths = models().expect("BC_OCR_MODELS (or the three individual paths) must be set");
    let image_path = env_path("BC_OCR_IMAGE").expect("BC_OCR_IMAGE must point at a cover image");
    let image = load_image(&image_path);

    let started = std::time::Instant::now();
    let mut engine = OcrEngine::open(&paths, RuntimeConfig::Default).expect("engine must open");
    let load_ms = started.elapsed().as_millis();

    let info = engine.info().clone();
    println!("--- model metadata ---");
    println!("det input   {} {:?}", info.det_input, info.det_input_shape);
    println!("det output  {} {:?}", info.det_output, info.det_output_shape);
    println!("rec input   {} {:?}", info.rec_input, info.rec_input_shape);
    println!("rec output  {} {:?}", info.rec_output, info.rec_output_shape);
    println!(
        "rec classes {} · dict {} characters · space class {}",
        info.rec_classes, info.dict_len, info.use_space_char
    );
    println!("session load {load_ms} ms");

    let started = std::time::Instant::now();
    let outcome = engine.recognise(&image).expect("inference must succeed");
    let infer_ms = started.elapsed().as_millis();

    println!("--- result ---");
    println!("image {}x{} · regions {} · lines {} · inference {} ms",
        image.width, image.height, outcome.regions_detected, outcome.lines.len(), infer_ms);
    for line in &outcome.lines {
        println!("{:>2}. {:>5.1}%  x={:.0} y={:.0} w={:.0} h={:.0} vertical={}  {}",
            line.rank, line.score * 100.0,
            line.quad.min_x(), line.quad.min_y(),
            line.quad.max_x() - line.quad.min_x(), line.quad.max_y() - line.quad.min_y(),
            line.vertical, line.text);
    }
    if !outcome.region_errors.is_empty() {
        println!("region errors: {:?}", outcome.region_errors);
    }

    // The class-count/dictionary contract is asserted at load time; reaching here proves it held.
    // These numbers come from the reconciled evidence (graph head, inference.yml, training config),
    // so a silent regression in the dictionary asset fails the test instead of degrading the text.
    assert_eq!(info.rec_classes, 18_710, "the exported CTC head emits 18,710 classes");
    assert_eq!(info.dict_len, 18_708, "the official ppocrv6_dict.txt has 18,708 entries");
    assert!(info.use_space_char, "PP-OCRv6_medium_rec.yml sets use_space_char: true");
    assert_eq!(info.rec_classes, info.dict_len + if info.use_space_char { 2 } else { 1 },
        "recogniser class count must equal dictionary + blank (+ space)");
    assert!(outcome.regions_detected > 0, "the detector must find text on a real cover");
    assert!(!outcome.lines.is_empty(), "recognition must produce at least one line");

    // Ground truth that is not self-referential: the bundled cover is The Hobbit, and the existing
    // Python/Chaquopy device test (OcrDeviceTest) asserts the same word with a different engine.
    let text = outcome.text();
    assert!(
        text.to_lowercase().contains("hobbit"),
        "the cover's title must be decoded, got: {text:?}"
    );
    for line in &outcome.lines {
        assert!(
            line.score.is_finite() && line.score > 0.0 && line.score <= 1.0,
            "every reported score must be a real probability, got {} for {:?}",
            line.score,
            line.text
        );
    }
    assert!(
        outcome.lines.iter().any(|line| line.score > 0.5),
        "at least one line must be recognised confidently"
    );
    println!("joined text: {:?}", text);
}

/// Multilingual smoke test over crops rendered by `tools/make-ocr-crops.py`.
///
/// Qualitative only: it proves the recogniser produces meaningful text for each script, and prints
/// what it read. No CER is claimed — that needs reference transcriptions of real scans, which this
/// repository does not have.
#[test]
#[ignore = "requires the PP-OCRv6 ONNX models, an ONNX Runtime library and BC_OCR_CROPS"]
fn ppocrv6_medium_reads_multilingual_crops() {
    let _serial = runtime_guard();
    let paths = models().expect("BC_OCR_MODELS (or the individual paths) must be set");
    let directory = env_path("BC_OCR_CROPS")
        .expect("BC_OCR_CROPS must point at a directory of rendered text crops");
    let mut files: Vec<PathBuf> = std::fs::read_dir(&directory)
        .unwrap_or_else(|e| panic!("{} could not be listed: {e}", directory.display()))
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| {
            // `manifest.json` lives next to the crops; only decodable images are recognised.
            path.is_file()
                && path
                    .extension()
                    .and_then(|extension| extension.to_str())
                    .map(|extension| {
                        matches!(extension.to_ascii_lowercase().as_str(), "png" | "jpg" | "jpeg" | "bmp" | "webp")
                    })
                    .unwrap_or(false)
        })
        .collect();
    files.sort();
    assert!(!files.is_empty(), "no crops in {}", directory.display());

    let mut engine = OcrEngine::open(&paths, RuntimeConfig::Default).expect("engine must open");
    let mut silent: Vec<String> = Vec::new();
    for file in &files {
        let image = load_image(file);
        let started = std::time::Instant::now();
        let outcome = engine.recognise(&image).expect("inference must succeed");
        let elapsed = started.elapsed().as_millis();
        let name = file.file_name().unwrap().to_string_lossy().to_string();
        let text = outcome.text();
        println!(
            "{name:<26} {elapsed:>4} ms  regions {:>2}  lines {:>2}  {:?}",
            outcome.regions_detected,
            outcome.lines.len(),
            text
        );
        for line in &outcome.lines {
            println!("      {:>5.1}%  {}", line.score * 100.0, line.text);
        }
        if !outcome.region_errors.is_empty() {
            println!("      region errors: {:?}", outcome.region_errors);
        }
        if text.trim().is_empty() {
            silent.push(name);
        }
    }
    assert!(silent.is_empty(), "these crops decoded to no text at all: {silent:?}");
}

/// Detector-stage diagnostics: proves ONNX Runtime resolves, both graphs load, and the detector
/// executes and produces usable regions, while recording the class layout that reconciles the head
/// with the dictionary.
///
/// The recogniser's CTC head emits 18,710 classes = 18,708 dictionary characters + CTC blank +
/// the appended space class (`use_space_char: true`). This test stops before decoding on purpose: it
/// isolates detection, so a detector regression cannot hide behind recognition.
///
/// It goes through [`OcrEngine::detect`] instead of building sessions itself, because ONNX Runtime
/// allows one environment per process and the engine owns it.
#[test]
#[ignore = "requires the PP-OCRv6 ONNX models, the dictionary asset and an ONNX Runtime library"]
fn ppocrv6_medium_detector_reads_a_real_cover_and_the_class_layout_is_reported() {
    let _serial = runtime_guard();
    let paths = models().expect("BC_OCR_MODELS (or the individual paths) must be set");
    let image_path = env_path("BC_OCR_IMAGE").expect("BC_OCR_IMAGE must point at a cover image");
    let image = load_image(&image_path);

    let mut engine = OcrEngine::open(&paths, RuntimeConfig::Default).expect("engine must open");
    let info = engine.info().clone();
    println!("detector   output {} {:?}", info.det_output, info.det_output_shape);
    println!("recognizer output {} {:?}", info.rec_output, info.rec_output_shape);
    assert_eq!(
        info.rec_output_shape.last().copied(),
        Some(18_710),
        "the CTC head must expose 18,708 characters + blank + space"
    );

    let started = std::time::Instant::now();
    let report = engine.detect(&image).expect("detector inference must succeed");
    let det_ms = started.elapsed().as_millis();
    println!(
        "detector: {det_ms} ms, map {}x{}, {} of {} values above the threshold, {} regions",
        report.map_height,
        report.map_width,
        report.hot_values,
        report.map_height * report.map_width,
        report.regions.len()
    );
    for (index, detection) in report.regions.iter().take(6).enumerate() {
        println!(
            "  {}. score {:.2} x={:.0}..{:.0} y={:.0}..{:.0} vertical={}",
            index + 1,
            detection.score,
            detection.quad.min_x(),
            detection.quad.max_x(),
            detection.quad.min_y(),
            detection.quad.max_y(),
            detection.quad.is_vertical()
        );
    }
    let regions: Vec<Region> = report
        .regions
        .iter()
        .map(|detection| Region { quad: detection.quad, vertical: detection.quad.is_vertical() })
        .collect();
    println!(
        "reading order indices: {:?}",
        reading_order(&regions).iter().map(|ordered| ordered.index).collect::<Vec<_>>()
    );
    assert!(!report.regions.is_empty(), "the detector must find text regions on a real cover");
    assert!(report.regions.iter().all(|d| d.quad.is_valid()), "every quad must be usable");
}

