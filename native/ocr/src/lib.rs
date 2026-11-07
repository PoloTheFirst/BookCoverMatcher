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
//! | recogniser preprocessing/decoding wiring (`rec`) | **not written** |
//! | ONNX Runtime sessions, `ort` dependency | **not written** |
//! | JNI exports, Kotlin wiring | **not written** |
//!
//! Nothing here is used by the Android application yet: `app/` loads no `libbc_ocr.so`, so the
//! shipped OCR engine is still the embedded-Python one. That is the rollback-safe state the
//! migration prompt requires until native inference is verified on a device.

pub mod det;
pub mod dict;
pub mod geom;
pub mod image;
pub mod order;

/// The version recorded in logs and in the agent report, so a device log can be tied to a build.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
