// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the rebuilt Python libraries without sending an image or account data off-device. */
@RunWith(AndroidJUnit4::class)
class NativeDependencyDeviceTest {
    @Test fun cameraXNativeLibrariesLoad() {
        // Linker smoke test only; camera capture remains a separate device/UI check.
        System.loadLibrary("image_processing_util_jni")
        System.loadLibrary("surface_util_jni")
    }

    @Test fun onnxJavaRuntimeRunsBundledClipModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = java.io.File(context.cacheDir, "native-dependency-clip.onnx")
        try {
            context.assets.open("models/openclip_image.onnx").use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
            com.bookcovermatcher.vision.OrtClipEmbedder.open(model, "instrumentation").use { embedder ->
                assertTrue("CLIP warm-up must produce an embedding", embedder.dim == 512)
            }
        } finally {
            model.delete()
        }
    }

    @Test fun pillowDecodesJpegAndLoadsFreeType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val python = PythonRuntime(context).python()
        assertNotNull("Python runtime must load", python)
        val size = python!!.getModule("builtins").callAttr("tuple", arrayOf(8, 8))
        val image = python.getModule("PIL.Image").callAttr("new", "RGB", size, "red")
        val buffer = python.getModule("io").callAttr("BytesIO")
        image.callAttr("save", buffer, "JPEG")
        buffer.callAttr("seek", 0)
        val decoded = python.getModule("PIL.Image").callAttr("open", buffer)
        decoded.callAttr("load")
        assertTrue(decoded.get("size")!!.toString().contains("8"))
        val font = python.getModule("PIL.ImageFont").callAttr("load_default")
        assertNotNull(font.callAttr("getmask", "BookCoverMatcher"))
        assertTrue(ChromeLensPyBridge(PythonRuntime(context)).availability() is OcrAvailability.Ready)
    }
}
