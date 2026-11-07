// SPDX-License-Identifier: GPL-3.0-or-later

//! Android entry points: the only `unsafe`-adjacent surface of this crate.
//!
//! Kotlin calls `com.bookcovermatcher.ocr.NativeOcrLib`, whose `external fun`s resolve to the symbols
//! below (the symbol name mirrors the declaring class: `Java_com_bookcovermatcher_ocr_NativeOcrLib_*`).
//! The interface is deliberately small and dumb:
//!
//! | Kotlin | Rust | Returns |
//! |---|---|---|
//! | `version()` | crate version | `String` |
//! | `create(det, rec, dict, runtimeLib)` | opens both sessions | handle `> 0`, or `0` + a thrown exception |
//! | `describe(handle)` | model metadata | [`crate::wire::info`] text |
//! | `recognise(handle, argb, w, h)` | the whole pipeline | [`crate::wire::result`] text |
//! | `destroy(handle)` | drops the sessions | — |
//!
//! # Safety rules this module enforces
//!
//! * **No pointers cross the boundary.** Kotlin holds an `i64` id from [`crate::registry`]; a stale
//!   id is an error, never a use-after-free.
//! * **No panic escapes.** Every entry point runs inside `catch_unwind`; a panic becomes a Java
//!   `IllegalStateException`, because unwinding into the ART runtime is undefined behaviour.
//! * **No unbounded allocation.** Dimensions are checked (`1..=8192` per side, `≤ 16.7 Mpx`) before
//!   anything is allocated, so a malformed call cannot ask for gigabytes.
//! * **One inference at a time.** The engine sits behind a `Mutex`, matching the crate's `&mut self`
//!   contract; ONNX Runtime parallelises inside a single `run`, and concurrent `run` on one session
//!   is not supported by the binding.
//! * **Cancellation is cooperative.** ONNX Runtime's C API offers no way to abort a `run` already
//!   in flight, so a Kotlin `Job` cancellation cannot stop inference; the caller discards the
//!   result. This is documented rather than pretended.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JIntArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::engine::{ModelInfo, ModelPaths, OcrEngine, RuntimeConfig};
use crate::image::RgbaImage;
use crate::registry::Registry;
use crate::wire;
use crate::VERSION;

/// The engine plus the metadata `describe` reports.
struct EngineHandle {
    engine: Mutex<OcrEngine>,
    info: ModelInfo,
}

static ENGINES: Registry<EngineHandle> = Registry::new();

const ILLEGAL_STATE: &str = "java/lang/IllegalStateException";
const ILLEGAL_ARGUMENT: &str = "java/lang/IllegalArgumentException";

/// A refused call: the Java class to throw and the message to show in the log.
type Failure = (&'static str, String);

fn state(message: impl Into<String>) -> Failure {
    (ILLEGAL_STATE, message.into())
}

fn argument(message: impl Into<String>) -> Failure {
    (ILLEGAL_ARGUMENT, message.into())
}

/// Largest accepted image, in pixels and per side.
///
/// The app hands over a 1280-px-long-edge cover (≈1.6 Mpx), so these are an order of magnitude
/// above anything legitimate; they exist to bound a native allocation that Kotlin cannot see.
const MAX_PIXELS: usize = 16 * 1024 * 1024;
const MAX_EDGE: usize = 8192;

fn panic_text(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic payload".to_string()
    }
}

/// Runs `body`, converting a Rust error or a panic into a pending Java exception.
fn guarded<T>(
    env: &mut JNIEnv,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv) -> Result<T, Failure>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(&mut *env))) {
        Ok(Ok(value)) => value,
        Ok(Err((class, message))) => {
            let _ = env.throw_new(class, message);
            fallback
        }
        Err(payload) => {
            // A panic here is a bug in the crate, not in the caller. The engine table recovers
            // because a poisoned lock is taken by force, so the app can try again after reporting.
            let _ = env.throw_new(ILLEGAL_STATE, format!("native OCR panicked: {}", panic_text(payload)));
            fallback
        }
    }
}

fn jstring_result(
    env: &mut JNIEnv,
    body: impl FnOnce(&mut JNIEnv) -> Result<String, Failure>,
) -> jstring {
    match guarded(env, None, |env| body(env).map(Some)) {
        Some(text) => match env.new_string(text) {
            Ok(string) => string.into_raw(),
            // An exception (typically OutOfMemoryError) is already pending.
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

fn string_arg(env: &mut JNIEnv, value: &JString, what: &str) -> Result<String, Failure> {
    env.get_string(value)
        .map(String::from)
        .map_err(|e| argument(format!("{what}: the argument is not a usable string ({e})")))
}

fn handle_of(handle: jlong) -> Result<Arc<EngineHandle>, Failure> {
    ENGINES
        .get(handle)
        .ok_or_else(|| state(format!("unknown native OCR handle {handle} (already destroyed?)")))
}

/// ARGB ints (Android `Bitmap.getPixels`) to the RGBA bytes the engine uses.
///
/// The alpha byte is passed through untouched: detection and recognition read colour only, and a
/// premultiplied-to-straight-alpha conversion here would silently change dark-on-transparent edges.
fn rgba_from_argb(pixels: &[jint], width: usize, height: usize) -> Result<RgbaImage, Failure> {
    let mut data = Vec::with_capacity(width * height * 4);
    for &pixel in pixels {
        let value = pixel as u32;
        data.push(((value >> 16) & 0xff) as u8);
        data.push(((value >> 8) & 0xff) as u8);
        data.push((value & 0xff) as u8);
        data.push(((value >> 24) & 0xff) as u8);
    }
    RgbaImage::new(width, height, data).map_err(state)
}

fn checked_dimensions(width: jint, height: jint) -> Result<(usize, usize), Failure> {
    if width <= 0 || height <= 0 {
        return Err(argument(format!("image size {width}x{height} is not positive")));
    }
    let (width, height) = (width as usize, height as usize);
    if width > MAX_EDGE || height > MAX_EDGE {
        return Err(argument(format!(
            "image size {width}x{height} exceeds the {MAX_EDGE}-pixel side limit"
        )));
    }
    if width * height > MAX_PIXELS {
        return Err(argument(format!(
            "image size {width}x{height} exceeds the {MAX_PIXELS}-pixel limit"
        )));
    }
    Ok((width, height))
}

/// `NativeOcr.version()` — the crate version, so a device log names the build it ran.
#[no_mangle]
pub extern "system" fn Java_com_bookcovermatcher_ocr_NativeOcrLib_version<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    jstring_result(&mut env, |_| Ok(VERSION.to_string()))
}

/// `NativeOcr.create(detector, recognizer, dictionary, runtimeLibrary)`.
///
/// An empty `runtimeLibrary` means "let ONNX Runtime be found by name"; the app passes the absolute
/// path of the `libonnxruntime.so` it already loaded, which is the only reliable way on Android.
#[no_mangle]
pub extern "system" fn Java_com_bookcovermatcher_ocr_NativeOcrLib_create<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    detector: JString<'local>,
    recognizer: JString<'local>,
    dictionary: JString<'local>,
    runtime_library: JString<'local>,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let detector = string_arg(env, &detector, "detector path")?;
        let recognizer = string_arg(env, &recognizer, "recognizer path")?;
        let dictionary = string_arg(env, &dictionary, "dictionary path")?;
        let runtime = string_arg(env, &runtime_library, "runtime library path")?;
        let runtime = if runtime.trim().is_empty() {
            RuntimeConfig::Default
        } else {
            RuntimeConfig::FromPath(PathBuf::from(runtime))
        };
        // `OcrEngine::open` validates that all three files exist, that the graph's class count
        // matches the dictionary, and that ONNX Runtime can build both sessions.
        let engine =
            OcrEngine::open(&ModelPaths::new(detector, recognizer, dictionary), runtime).map_err(state)?;
        let info = engine.info().clone();
        Ok(ENGINES.insert(EngineHandle { engine: Mutex::new(engine), info }))
    })
}

/// `NativeOcr.describe(handle)` — model metadata in [`wire::info`] form.
#[no_mangle]
pub extern "system" fn Java_com_bookcovermatcher_ocr_NativeOcrLib_describe<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    jstring_result(&mut env, |_| {
        let engine = handle_of(handle)?;
        Ok(wire::info(&engine.info, VERSION))
    })
}

/// `NativeOcr.recognise(handle, argb, width, height)` — the whole pipeline on one image.
#[no_mangle]
pub extern "system" fn Java_com_bookcovermatcher_ocr_NativeOcrLib_recognise<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    argb: JIntArray<'local>,
    width: jint,
    height: jint,
) -> jstring {
    jstring_result(&mut env, |env| {
        let engine = handle_of(handle)?;
        let (width, height) = checked_dimensions(width, height)?;
        let expected = width * height;
        let length = env
            .get_array_length(&argb)
            .map_err(|e| argument(format!("image buffer: {e}")))?
            as usize;
        if length != expected {
            return Err(argument(format!(
                "image buffer holds {length} pixels but {width}x{height} needs {expected}"
            )));
        }
        let mut pixels: Vec<jint> = vec![0; expected];
        env.get_int_array_region(&argb, 0, &mut pixels)
            .map_err(|e| argument(format!("image buffer could not be read: {e}")))?;
        let image = rgba_from_argb(&pixels, width, height)?;

        // A poisoned lock means a previous call panicked; the sessions themselves are still usable,
        // so the engine is recovered instead of being made permanently unavailable.
        let mut engine = engine.engine.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let outcome = engine.recognise(&image).map_err(state)?;
        Ok(wire::result(&outcome))
    })
}

/// `NativeOcr.destroy(handle)` — drops the sessions. Idempotent by design.
#[no_mangle]
pub extern "system" fn Java_com_bookcovermatcher_ocr_NativeOcrLib_destroy<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        ENGINES.remove(handle);
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argb_converts_to_rgba_in_channel_order() {
        // 0xAARRGGBB: opaque red.
        let image = rgba_from_argb(&[0xFF_FF_00_00u32 as jint], 1, 1).expect("pixel");
        assert_eq!(image.data, vec![255, 0, 0, 255]);
    }

    #[test]
    fn a_negative_or_zero_size_is_refused_before_allocating() {
        assert!(checked_dimensions(0, 10).is_err());
        assert!(checked_dimensions(10, -1).is_err());
    }

    #[test]
    fn an_absurd_size_is_refused_instead_of_allocating() {
        let err = checked_dimensions(100_000, 100_000).unwrap_err();
        assert!(err.1.contains("side limit"), "{}", err.1);
        let err = checked_dimensions(8192, 8192).unwrap_err();
        assert!(err.1.contains("pixel limit"), "{}", err.1);
    }

    #[test]
    fn an_ordinary_cover_size_is_accepted() {
        assert_eq!(checked_dimensions(1280, 960).unwrap(), (1280, 960));
    }

    #[test]
    fn an_unknown_handle_is_reported_rather_than_dereferenced() {
        // `.err()` rather than `.unwrap_err()`: the engine type is deliberately not `Debug`.
        let err = handle_of(424_242).err().expect("an unknown id must be refused");
        assert!(err.1.contains("unknown native OCR handle"), "{}", err.1);
    }
}
