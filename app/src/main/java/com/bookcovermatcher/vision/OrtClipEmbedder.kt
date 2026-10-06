package com.bookcovermatcher.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.bookcovermatcher.core.search.FlatIpIndex
import com.bookcovermatcher.core.vision.ClipPreprocessor
import com.bookcovermatcher.core.vision.ImageEmbedder
import com.bookcovermatcher.core.vision.PixelImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Runs the exported OpenCLIP image tower with ONNX Runtime.
 *
 * Model contract (what `tools/export_openclip_onnx.py` writes):
 *  * one input, float32 `[1, 3, S, S]`, already CLIP-normalised by [ClipPreprocessor];
 *  * one output, float32 `[1, D]` (un-normalised image embedding) - this class L2-normalises it.
 */
class OrtClipEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val inputSize: Int,
    override val dim: Int,
    override val fingerprint: String,
) : ImageEmbedder, AutoCloseable {

    /** One inference at a time (ONNX Runtime parallelises inside a single run). */
    @Synchronized
    override fun embed(image: PixelImage): FloatArray {
        val chw = ClipPreprocessor.toChw(image.argb, image.width, image.height, inputSize)
        val raw = infer(env, session, inputName, inputSize, chw)
        check(raw.size == dim) { "Model returned ${raw.size} values, expected $dim." }
        return FlatIpIndex.l2Normalize(raw)
    }

    override fun close() {
        session.close()
    }

    companion object {
        /**
         * Opens [modelFile], checks that it looks like an image encoder and runs one warm-up inference
         * (which also reveals the embedding size).
         *
         * @throws IllegalArgumentException if the file is not a usable image encoder
         * @throws ai.onnxruntime.OrtException if ONNX Runtime cannot load it
         */
        fun open(modelFile: File, modelFingerprint: String): OrtClipEmbedder {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
                env.createSession(modelFile.absolutePath, options)
            }
            try {
                require(session.inputNames.size == 1) { "Expected a model with a single image input." }
                val inputName = session.inputNames.first()
                val info = session.inputInfo[inputName]?.info as? TensorInfo
                    ?: throw IllegalArgumentException("The model input is not a tensor.")
                val shape = info.shape
                require(shape.size == 4 && shape[1] == 3L) {
                    "This does not look like an image encoder (expected input [1, 3, H, W], got ${shape.toList()})."
                }
                val size = if (shape[2] > 0) shape[2].toInt() else ClipPreprocessor.DEFAULT_SIZE
                require(shape[3] <= 0 || shape[3].toInt() == size) { "Only square model inputs are supported." }

                // warm-up on a mid-grey picture; the length of the output is the embedding size
                val dim = infer(env, session, inputName, size, FloatArray(3 * size * size)).size
                require(dim in 8..8192) { "Unexpected embedding size $dim." }
                return OrtClipEmbedder(env, session, inputName, size, dim, "$modelFingerprint:d$dim:s$size")
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }

        private fun infer(env: OrtEnvironment, session: OrtSession, inputName: String, size: Int, chw: FloatArray): FloatArray {
            val buffer = ByteBuffer.allocateDirect(chw.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(chw)
            buffer.rewind()
            val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
            val input = OnnxTensor.createTensor(env, buffer, shape)
            try {
                val result = session.run(mapOf(inputName to input))
                try {
                    val out = result.get(0) as OnnxTensor
                    val fb = out.floatBuffer
                    val values = FloatArray(fb.remaining())
                    fb.get(values)
                    return values
                } finally {
                    result.close()
                }
            } finally {
                input.close()
            }
        }
    }
}
