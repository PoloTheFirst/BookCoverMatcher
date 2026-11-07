//! `bc_ocr` — the native PP-OCRv6 OCR core for BookCoverMatcher.
//!
//! # Status (v2.5.0-dev.5)
//!
//! This crate is the **platform-independent half** of the migration described in
//! `historical_prompt/v2.5.0-dev.5.md`: the parts whose correctness can be proven on the build
//! host with `cargo test`, and which do not depend on ONNX Runtime, JNI or a device.
//!
//! What is here and tested:
//!
//! * [`dict`] — the PP-OCR `CTCLabelDecode` index space (`blank = 0`, dictionary from 1, optional
//!   trailing space) and the greedy decoder, including repeat collapsing, blank skipping and an
//!   honest per-character confidence. A wrong index shift here would mis-map every character of
//!   every recognised line, so it is the first thing pinned by tests.
//! * [`geom`] — corner ordering with PaddleOCR's `get_mini_boxes` rule, quadrilateral size and
//!   vertical/horizontal classification (a vertical Japanese column must be recognised as such),
//!   the affine solve and the bilinear perspective crop that turns a detected quad into an
//!   upright recogniser input, with clamping for degenerate or out-of-bounds quads.
//!
//! What is deliberately **not** here yet, and why — see `native/ocr/README.md`:
//!
//! * `det` (detector preprocessing, DB probability-map post-processing with `thresh 0.2`,
//!   `box_thresh 0.45`, `unclip_ratio 1.4`),
//! * `rec` (48×320 BGR recogniser preprocessing and CTC/NRTR head selection),
//! * `order` (reading order for mixed vertical/horizontal Japanese, Chinese and English blocks),
//! * the ONNX Runtime session layer, and the JNI exports
//!   (`Java_com_bookcovermatcher_online_NativeOcr_*`).
//!
//! Those stages are no-ops for the Android application today: nothing in `app/` loads
//! `libbc_ocr.so`, so the shipped OCR path is still the existing embedded-Python engine. That is
//! the rollback-safe state the release prompt requires while the native pipeline is unverified.

pub mod dict;
pub mod geom;

/// The version recorded in logs and in the agent report, so a device log can be tied to a build.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
