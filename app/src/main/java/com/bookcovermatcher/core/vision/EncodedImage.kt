package com.bookcovermatcher.core.vision

/**
 * The rule for "did that JPEG encoder actually produce a picture?".
 *
 * `Bitmap.compress()` reports failure by returning `false` — it does not throw and does not
 * return null — so an encoder that quietly wrote nothing used to hand an empty `ByteArray`
 * downstream, where it surfaced much later as a generic OCR error. Keeping the acceptance test
 * here, as plain JVM code, means the rule is unit-tested instead of only existing inside a
 * `Bitmap` call that a desktop test cannot make.
 *
 * [MIN_JPEG_BYTES] is well below the smallest real JPEG of a photographed cover (a 1×1 white
 * JPEG is 307 bytes) and far above 0, so it rejects "the encoder wrote nothing or a header"
 * without rejecting any legitimate result.
 */
object EncodedImage {

    const val MIN_JPEG_BYTES = 512

    class EncodingFailed(message: String) : IllegalStateException(message)

    /** @return the encoded bytes, verified non-empty and plausibly a picture. */
    fun requireValid(jpeg: ByteArray?, byteLimit: Int): ByteArray {
        if (jpeg == null) throw EncodingFailed("The image encoder returned nothing.")
        if (jpeg.size == 0) throw EncodingFailed("The image encoder produced an empty file.")
        if (jpeg.size < MIN_JPEG_BYTES) {
            throw EncodingFailed("The encoded image is only ${jpeg.size} bytes — too small to be a picture.")
        }
        if (jpeg.size > byteLimit) {
            throw EncodingFailed("The encoded image is ${jpeg.size} bytes, over the ${byteLimit}byte limit.")
        }
        return jpeg
    }
}
