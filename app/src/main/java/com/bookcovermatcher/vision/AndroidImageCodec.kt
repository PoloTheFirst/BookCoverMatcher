package com.bookcovermatcher.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.bookcovermatcher.core.vision.ImageCodec
import com.bookcovermatcher.core.vision.ImageOps
import com.bookcovermatcher.core.vision.PixelImage
import com.bookcovermatcher.core.vision.EncodedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [ImageCodec] on top of `BitmapFactory` / `Bitmap`. Equivalent of v1's `image.js`. */
class AndroidImageCodec : ImageCodec {

    /** For pictures stored in the workbook (no EXIF handling, like a spreadsheet cell). */
    override fun decode(bytes: ByteArray, mime: String, maxEdge: Int): PixelImage? =
        decodeBytes(bytes, maxEdge, applyExifOrientation = false)

    /** For a photo chosen by the user: honors the EXIF rotation, as a browser `<img>` does. */
    fun decodePhoto(bytes: ByteArray, maxEdge: Int): PixelImage? =
        decodeBytes(bytes, maxEdge, applyExifOrientation = true)

    private fun decodeBytes(bytes: ByteArray, maxEdge: Int, applyExifOrientation: Boolean): PixelImage? {
        var bmp: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

            if (applyExifOrientation) {
                val oriented = applyExif(bmp, readOrientation(bytes))
                if (oriented !== bmp) { bmp.recycle(); bmp = oriented }
            }
            val scale = min(1f, maxEdge.toFloat() / max(bmp.width, bmp.height))
            if (scale < 1f) {
                val w = max(1, (bmp.width * scale).roundToInt())
                val h = max(1, (bmp.height * scale).roundToInt())
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
            }
            return toPixelImage(bmp)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            bmp?.recycle()
        }
    }

    override fun makeThumbJpeg(image: PixelImage, boxW: Int, boxH: Int): ByteArray {
        val scale = min(boxW.toFloat() / image.width, boxH.toFloat() / image.height)
        val dw = max(1, (image.width * scale).roundToInt())
        val dh = max(1, (image.height * scale).roundToInt())

        val out = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(THUMB_BACKGROUND)
            val dst = RectF((boxW - dw) / 2f, (boxH - dh) / 2f, (boxW + dw) / 2f, (boxH + dh) / 2f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            val src: Bitmap
            if (dw < image.width) {
                // strong down-scaling: box filter first, so thin text does not alias
                val small = ImageOps.areaResizeArgb(image.argb, image.width, image.height, dw, dh)
                src = Bitmap.createBitmap(small, dw, dh, Bitmap.Config.ARGB_8888)
            } else {
                src = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            }
            try {
                canvas.drawBitmap(src, null, dst, paint)
            } finally {
                src.recycle()
            }
            val bos = ByteArrayOutputStream(24 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bos)
            return bos.toByteArray()
        } finally {
            out.recycle()
        }
    }
    /**
     * Encodes a full-size picture as JPEG for something that needs to read it — currently the OCR
     * bridge, which cannot work from the 224-pixel thumbnail. Down-scaled to [maxEdge] first so a
     * 12-megapixel photo does not become a 5 MB upload.
     *
     * Unlike [makeThumbJpeg], which only has to look right on screen, this output is sent to a
     * service, so every failure mode is checked rather than assumed:
     *  * `Bitmap.compress()` reports failure by returning `false` — it neither throws nor returns
     *    null — so the result is checked, not trusted;
     *  * alpha is flattened onto white first, because JPEG has no transparency and Android's
     *    encoder would otherwise blend onto an implementation-defined colour. The offline thumbnail
     *    path already composites explicitly, and this matches it;
     *  * the byte array is validated by [EncodedImage.requireValid] before a caller can hand an
     *    empty file to Python and have it come back as a generic OCR error.
     *
     * @throws EncodedImage.EncodingFailed if there is no usable picture.
     */
    fun encodeJpeg(image: PixelImage, maxEdge: Int, quality: Int = OCR_QUALITY): ByteArray {
        if (image.width <= 0 || image.height <= 0) {
            throw EncodedImage.EncodingFailed("The captured picture has no size (${image.width}×${image.height}).")
        }
        val scale = min(1f, maxEdge.toFloat() / max(image.width, image.height))
        val w = max(1, (image.width * scale).roundToInt())
        val h = max(1, (image.height * scale).roundToInt())
        if (w * h <= 0) {
            throw EncodedImage.EncodingFailed("Resizing to ${w}×$h would produce an invalid picture.")
        }

        // Flatten first so the JPEG has no alpha channel left to guess about.
        val argb = if (w < image.width || h < image.height) {
            ImageOps.areaResizeArgb(image.argb, image.width, image.height, w, h)
        } else {
            image.argb.copyOf()
        }
        for (i in argb.indices) argb[i] = ImageOps.flattenOnWhite(argb[i])

        val src = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream(64 * 1024)
            if (!src.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw EncodedImage.EncodingFailed(
                    "Android could not encode the ${w}×$h cover as JPEG.",
                )
            }
            return EncodedImage.requireValid(out.toByteArray(), OCR_MAX_BYTES)
        } finally {
            src.recycle()
        }
    }


    companion object {
        private const val THUMB_BACKGROUND = 0xFF001428.toInt()
        private const val THUMB_QUALITY = 72

        /** OCR wants legible text, not a small file; Lens downscales anything over 1600 px anyway. */
        const val OCR_QUALITY = 84

        /** Long edge handed to the OCR bridge. */
        const val OCR_MAX_EDGE = 1280

        /** Keep in step with MAX_IMAGE_BYTES in `lens_bridge.py`; Python rejects anything larger. */
        const val OCR_MAX_BYTES = 12 * 1024 * 1024

        fun toPixelImage(bmp: Bitmap): PixelImage {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return PixelImage(px, w, h)
        }

        private fun readOrientation(bytes: ByteArray): Int = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return src
            }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
