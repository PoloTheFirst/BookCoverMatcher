package com.bookcovermatcher.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.vision.GuideGeometry
import com.bookcovermatcher.core.vision.ImageOps
import com.bookcovermatcher.core.vision.PixelImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Receives Auto Scan ticks on the camera's analysis thread.
 * [frame] converts the current camera frame to the guide crop; call it only if the frame is needed
 * (during the cooldown it is skipped, so no work is wasted). It must not be called after `onTick` returns.
 */
fun interface AutoScanSink {
    fun onTick(nowMs: Long, frame: () -> PixelImage?)
}

/**
 * CameraX wrapper: live preview plus a throttled analysis stream.
 *
 * Replaces v1's `camera.js` (`getUserMedia` + `grabVideoFrame`). Frames are cropped to the same guide
 * rectangle as in v1 (see [GuideGeometry]); manual captures and Auto Scan ticks both come from the
 * analysis stream, so the picture that is matched is the one that was on screen.
 */
class CameraController(context: Context) {

    private val appContext = context.applicationContext
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val pendingManual = AtomicReference<CompletableDeferred<PixelImage>?>(null)

    /** Aspect (width / height) of the on-screen viewfinder; set by the UI so the crop matches what is shown. */
    @Volatile var viewAspect: Float = 0.75f

    @Volatile var autoSink: AutoScanSink? = null
    @Volatile var autoIntervalMs: Long = 400L

    private var lastAutoTickAt = 0L          // analysis thread only
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null

    private val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            handleFrame(image)
        } catch (t: Throwable) {
            pendingManual.getAndSet(null)?.completeExceptionally(t)
        } finally {
            image.close()
        }
    }

    // ---------- lifecycle ----------

    /** Binds preview + analysis to [owner]; CameraX pauses and resumes them with the lifecycle. */
    fun start(owner: LifecycleOwner, view: PreviewView, onLive: () -> Unit, onError: (String) -> Unit) {
        previewView = view
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    onError("No camera found on this device.")
                } else {
                    val resolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                    val preview = Preview.Builder().setResolutionSelector(resolution).build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor, analyzer)

                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, selector, preview, analysis)
                    onLive()
                }
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun stop() {
        pendingManual.getAndSet(null)?.cancel()
        provider?.unbindAll()
        camera = null
    }

    fun shutdown() {
        stop()
        analysisExecutor.shutdown()
    }

    /** Tap-to-focus; [x], [y] are in the preview view's pixel coordinates. */
    fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val cam = camera ?: return
        try {
            val point = view.meteringPointFactory.createPoint(x, y)
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        } catch (e: Exception) {
            // focusing is best effort
        }
    }

    // ---------- frames ----------

    /** Waits for the next camera frame and returns its guide crop (v1: `grabVideoFrame`). */
    suspend fun grabGuideFrame(timeoutMs: Long = 2500L): PixelImage {
        if (camera == null) throw IllegalStateException(NOT_READY)
        val request = CompletableDeferred<PixelImage>()
        pendingManual.set(request)
        try {
            return withTimeout(timeoutMs) { request.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(NOT_READY)
        } finally {
            pendingManual.compareAndSet(request, null)
        }
    }

    private fun handleFrame(image: ImageProxy) {
        val manual = pendingManual.getAndSet(null)
        val now = SystemClock.elapsedRealtime()
        val sink = autoSink
        val autoDue = sink != null && now - lastAutoTickAt >= autoIntervalMs
        if (manual == null && !autoDue) return

        var cached: PixelImage? = null
        var failure: Throwable? = null
        val provide: () -> PixelImage? = {
            if (cached == null && failure == null) {
                try {
                    cached = guideCrop(image)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            cached
        }

        if (manual != null) {
            val img = provide()
            if (img != null) manual.complete(img)
            else manual.completeExceptionally(failure ?: IllegalStateException(NOT_READY))
        }
        if (autoDue && sink != null) {
            lastAutoTickAt = now
            sink.onTick(now, provide)
        }
    }

    private fun guideCrop(image: ImageProxy): PixelImage {
        val raw = image.toBitmap()
        var upright = raw
        try {
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
            val g = GuideGeometry.guideRect(upright.width, upright.height, viewAspect)
            val px = IntArray(g.w * g.h)
            upright.getPixels(px, 0, g.w, g.x, g.y, g.w, g.h)
            return limitSize(PixelImage(px, g.w, g.h))
        } finally {
            if (upright !== raw) upright.recycle()
            raw.recycle()
        }
    }

    private fun limitSize(img: PixelImage): PixelImage {
        val s = GuideGeometry.downscale(img.width, img.height, Config.MAX_IMAGE_DECODE)
        if (s >= 1f) return img
        val w = max(1, (img.width * s).roundToInt())
        val h = max(1, (img.height * s).roundToInt())
        return PixelImage(ImageOps.areaResizeArgb(img.argb, img.width, img.height, w, h), w, h)
    }

    companion object {
        const val NOT_READY = "Camera stream is not ready yet."
    }
}
