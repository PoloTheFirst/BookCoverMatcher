// SPDX-License-Identifier: GPL-3.0-or-later

//! Host-only evaluation harness: runs the real pipeline on an image and prints JSON.
//!
//! Development tool (`cargo run --example eval`), never packaged: it exists so the Rust engine can be
//! compared stage-by-stage with `tools/ocr-reference/reference_paddle.py` and scored by
//! `tools/ocr-reference/metrics.py`.
//!
//! `--dump-crops DIR` writes every recognition crop as a PNG, which is what makes a detector/geometry
//! problem distinguishable from a recogniser problem: the same crop file can then be fed to the
//! Python reference recogniser.

use std::env;
use std::path::PathBuf;

use bc_ocr::engine::{DetectorReport, ModelInfo, ModelPaths, OcrEngine, OcrOutcome, RuntimeConfig};
use bc_ocr::image::RgbaImage;

fn arg_value(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1).cloned())
}

fn load_image(path: &PathBuf) -> RgbaImage {
    let decoded = image::open(path).unwrap_or_else(|e| panic!("image {}: {e}", path.display()));
    let rgba = decoded.to_rgba8();
    let (width, height) = (rgba.width() as usize, rgba.height() as usize);
    RgbaImage::new(width, height, rgba.into_raw()).expect("rgba buffer")
}

fn json_escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 8);
    for ch in text.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let image_path = PathBuf::from(arg_value(&args, "--image").expect("--image is required"));
    let models_dir = arg_value(&args, "--models")
        .or_else(|| env::var("BC_OCR_MODELS").ok())
        .expect("--models or BC_OCR_MODELS");
    let dict = arg_value(&args, "--dict")
        .or_else(|| env::var("BC_OCR_DICT").ok())
        .expect("--dict or BC_OCR_DICT");
    let json_path = arg_value(&args, "--json");
    let dump_dir = arg_value(&args, "--dump-crops").map(PathBuf::from);

    let paths = ModelPaths::new(
        PathBuf::from(&models_dir).join("det.onnx"),
        PathBuf::from(&models_dir).join("rec.onnx"),
        PathBuf::from(&dict),
    );
    let mut engine = OcrEngine::open(&paths, RuntimeConfig::default()).expect("engine");
    let image = load_image(&image_path);

    let started = std::time::Instant::now();
    let detector = engine.detect(&image).expect("detect");
    let det_ms = started.elapsed().as_millis();

    let started = std::time::Instant::now();
    let outcome = engine.recognise(&image).expect("recognise");
    let rec_ms = started.elapsed().as_millis();
    let info = engine.info().clone();
    if let Some(dir) = &dump_dir {
        std::fs::create_dir_all(dir).expect("dump dir");
        for (index, line) in outcome.lines.iter().enumerate() {
            let (w, h) = line.quad.rect_size();
            let (cw, ch) = (w.round().max(8.0) as usize, h.round().max(8.0) as usize);
            let crop = image.crop_quad(&line.quad, cw, ch);
            image::save_buffer(
                dir.join(format!("crop_{index:02}.png")),
                &crop.data,
                crop.width as u32,
                crop.height as u32,
                image::ColorType::Rgba8,
            )
            .expect("crop png");
        }
    }
    write_json(
        image_path, image, detector, outcome, info, det_ms, rec_ms, json_path,
    );


#[allow(clippy::too_many_arguments)]
fn write_json(
    image_path: PathBuf,
    image: RgbaImage,
    detector: DetectorReport,
    outcome: OcrOutcome,
    info: ModelInfo,
    det_ms: u128,
    rec_ms: u128,
    json_path: Option<String>,
) {
    let regions: Vec<String> = detector
        .regions
        .iter()
        .map(|d| {
            let q: Vec<String> =
                d.quad.0.iter().map(|p| format!("[{:.1},{:.1}]", p.x, p.y)).collect();
            format!("{{\"score\":{:.4},\"box\":[{}]}}", d.score, q.join(","))
        })
        .collect();
    let lines: Vec<String> = outcome
        .lines
        .iter()
        .map(|l| {
            let q: Vec<String> =
                l.quad.0.iter().map(|p| format!("[{:.1},{:.1}]", p.x, p.y)).collect();
            format!(
                "{{\"text\":\"{}\",\"score\":{:.4},\"box\":[{}],\"vertical\":{},\"rank\":{}}}",
                json_escape(&l.text),
                l.score,
                q.join(","),
                l.vertical,
                l.rank
            )
        })
        .collect();

    let json = format!(
        "{{\n  \"engine\": \"rust-bc_ocr\",\n  \"image\": \"{}\",\n  \"image_size\": [{}, {}],\n  \
         \"det\": {{\"limit_side\": {}, \"map\": [{}, {}], \"regions\": {}, \"hot_values\": {}, \
         \"ms\": {}}},\n  \"rec_ms\": {},\n  \"model\": {{\"det_shape\": {:?}, \"rec_shape\": {:?}}},\n  \
         \"det_regions\": [{}],\n  \"lines\": [{}]\n}}",
        json_escape(&image_path.display().to_string()),
        image.width,
        image.height,
        bc_ocr::det::DEFAULT_LIMIT_SIDE,
        detector.map_width,
        detector.map_height,
        detector.regions.len(),
        detector.hot_values,
        det_ms,
        rec_ms,
        info.det_input_shape,
        info.rec_input_shape,
        regions.join(","),
        lines.join(",")
    );
    match json_path {
        Some(path) => {
            std::fs::write(&path, &json).expect("write json");
            println!(
                "{}: {} region(s), {} line(s) in {} ms -> {}",
                image_path.display(),
                detector.regions.len(),
                outcome.lines.len(),
                det_ms + rec_ms,
                path
            );
        }
        None => println!("{json}"),
    }
}

}
