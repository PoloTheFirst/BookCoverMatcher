package com.bookcovermatcher.core.search

import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.excel.CellRange
import com.bookcovermatcher.core.excel.RawImage
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.excel.ZipSource
import com.bookcovermatcher.core.vision.ImageCodec
import com.bookcovermatcher.core.vision.ImageEmbedder

/**
 * Reads the pictures of one workbook range (through the unchanged v1 extraction workflow) and
 * embeds every one of them. Each picture is decoded, embedded and thumbnailed right inside the
 * extractor's decode callback, so only one decoded bitmap is alive at a time.
 */
class CatalogBuilder(
    private val codec: ImageCodec,
    private val embedder: ImageEmbedder,
) {
    private class Embedded(val thumb: ByteArray, val embedding: FloatArray)

    /**
     * @param onProgress `(done, total)` candidates handled so far.
     * @param checkCancelled should throw (e.g. `CancellationException`) to abort.
     */
    fun build(
        zip: ZipSource,
        range: CellRange,
        includeFloating: Boolean = Config.EXTRACT_FLOATING_IMAGES,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): CatalogIndex {
        val plan = WorkbookImageExtractor.plan(zip, range, includeFloating)
        onProgress(0, plan.total)

        val extracted = WorkbookImageExtractor.run(
            zip = zip,
            plan = plan,
            onStep = onProgress,
            decode = { raw: RawImage ->
                checkCancelled()
                decodeAndEmbed(raw)
            },
        )

        val items = extracted.map {
            IndexedImage(it.cell, it.row, it.col, it.source, it.imageId, it.payload.thumb, it.payload.embedding)
        }
        return CatalogIndex(embedder.dim, items)
    }

    private fun decodeAndEmbed(raw: RawImage): Embedded? {
        val image = codec.decode(raw.bytes, raw.mime, Config.MAX_BUFFER_DECODE) ?: return null
        val embedding = embedder.embed(image)
        val thumb = codec.makeThumbJpeg(
            image,
            Config.THUMB_MATCH_W * Config.THUMB_PIXEL_SCALE,
            Config.THUMB_MATCH_H * Config.THUMB_PIXEL_SCALE,
        )
        return Embedded(thumb, embedding)
    }
}
