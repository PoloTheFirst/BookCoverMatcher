package com.bookcovermatcher.core

import com.bookcovermatcher.core.vision.EncodedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The rule that decides whether a JPEG encoder actually produced a picture.
 *
 * `Bitmap.compress()` reports failure by returning `false` — it neither throws nor returns null —
 * so `encodeJpeg` used to hand a zero-length array straight to the OCR bridge, where it came back
 * much later as a generic "could not read the cover". `encodeJpeg` itself needs a device, but the
 * acceptance rule it now applies is plain JVM and lives in [EncodedImage], which is what this
 * tests.
 */
class EncodedImageTest {

    private val limit = 12 * 1024 * 1024

    @Test fun aPlausibleJpegPassesThroughUnchanged() {
        val jpeg = ByteArray(EncodedImage.MIN_JPEG_BYTES)

        assertSame(jpeg, EncodedImage.requireValid(jpeg, limit))
    }

    @Test fun nullOutputIsRejected() = expectFailure("returned nothing") {
        EncodedImage.requireValid(null, limit)
    }

    @Test fun emptyOutputIsRejected() = expectFailure("empty file") {
        EncodedImage.requireValid(ByteArray(0), limit)
    }

    @Test fun aHeaderSizedBlobIsRejected() = expectFailure("too small") {
        EncodedImage.requireValid(ByteArray(EncodedImage.MIN_JPEG_BYTES - 1), limit)
    }

    @Test fun anOverLimitBlobIsRejectedBeforePythonSeesIt() = expectFailure("over the") {
        EncodedImage.requireValid(ByteArray(limit + 1), limit)
    }

    /** The floor has to sit above a real, if tiny, JPEG so it never rejects a legitimate cover. */
    @Test fun theFloorIsAboveTheSmallestRealJpeg() {
        // A 1x1 white baseline JPEG is 307 bytes; anything a camera produces is orders larger.
        assertTrue("floor must not reject the smallest possible JPEG",
            EncodedImage.MIN_JPEG_BYTES > 307)
    }

    private inline fun expectFailure(phrase: String, block: () -> Unit) {
        try {
            block()
        } catch (e: EncodedImage.EncodingFailed) {
            val message = e.message.orEmpty()
            if (!message.contains(phrase)) {
                fail("expected '$phrase' in '$message'")
            }
            return
        }
        fail("expected EncodingFailed")
    }
}
