// SPDX-License-Identifier: GPL-3.0-or-later

//! `bc_ocr` — the native PP-OCRv6 OCR core for BookCoverMatcher.
//!
//! # Status (v2.5.0-dev.5, migration in progress — **not** complete)
//!
//! | Stage | State |
//! |---|---|
//! | charset + CTC decoding ([`dict`]) | done, tested |
//! | quad geometry + perspective crop ([`geom`]) | done, tested |
//! | RGBA image + resampling ([`image`]) | done, tested |
//! | detector preprocessing + DB post-processing ([`det`]) | done, tested |
//! | reading order ([`order`]) | done, tested |
//! | recogniser preprocessing/decoding ([`rec`]) | done, tested — 18,710 classes reconciled with the model |
//! | ONNX Runtime sessions + the full pipeline ([`engine`]) | done — verified on the host with the real models |
//! | JNI exports, Kotlin wiring | **not written** |
//!
//! The recognition dictionary is the model's own vocabulary: 18,708 characters + CTC blank + the
//! appended space class = the 18,710 classes the exported graph emits, so the recogniser's output
//! decodes into real text (English, Japanese, Traditional and Simplified Chinese) on the host.
//!
//! Nothing here is used by the Android application yet: `app/` loads no `libbc_ocr.so`, so the
//! shipped OCR engine is still the embedded-Python one. That is the rollback-safe state the
//! migration prompt requires until native inference is verified on a device.

pub mod det;
pub mod dict;
pub mod engine;
pub mod geom;
pub mod image;
pub mod order;
pub mod rec;

/// The version recorded in logs and in the agent report, so a device log can be tied to a build.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
