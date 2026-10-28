package com.bookcovermatcher.core.excel.comments

/**
 * Legacy (pre-threaded) Excel **notes** and the images stored with them.
 *
 * The model is read-only: nothing here can write to the workbook, and no field is inferred when the
 * package does not contain it. A comment with no resolvable image keeps its text and says so.
 */
object CommentScanLimits {
    /** Comments kept per workbook; beyond this the scan stops and reports [WorkbookCommentImages.truncated]. */
    const val MAX_COMMENTS = 5000

    /** Media files listed per workbook. */
    const val MAX_MEDIA_FILES = 1500

    /** Largest media part read for thumbnails/export (bytes). Bigger parts are listed but not decoded. */
    const val MAX_MEDIA_BYTES = 32L * 1024 * 1024

    /** Largest comments/VML XML part parsed (bytes). */
    const val MAX_XML_BYTES = 16L * 1024 * 1024
}

/** How a note shape pointed at an image. */
enum class MediaRefKind { SRC, RELID }

enum class MediaResolution {
    /** The reference resolved to an existing package part. */
    RESOLVED,

    /** The reference exists but could not be resolved to a part; [CommentMediaRef.detail] says why. */
    UNRESOLVED,
}

/**
 * One image reference found on a note shape.
 *
 * [mediaPath] is a normalised package path (`xl/media/image1.png`) when [resolution] is RESOLVED.
 * [reference] is the raw attribute value, kept so an unresolved case can be explained instead of
 * hidden.
 */
data class CommentMediaRef(
    val reference: String,
    val kind: MediaRefKind,
    val resolution: MediaResolution,
    val mediaPath: String? = null,
    val detail: String? = null,
)

/** What a commented cell contains, for the grid marker and the detail panel. */
enum class CommentImageState {
    /** Text only: no image reference at all on the note shape. */
    TEXT_ONLY,

    /** At least one image reference resolved to a package part. */
    WITH_IMAGES,

    /** The note shape references an image, but it could not be resolved. */
    UNRESOLVED_IMAGE,
}

/** One commented cell. */
data class LegacyCommentEntry(
    val sheetName: String,
    val sheetPath: String,
    val cell: String,
    /** Zero-based, derived from the authoritative comments XML ref. */
    val row: Int,
    val col: Int,
    val text: String,
    val author: String?,
    val media: List<CommentMediaRef>,
    /** VML coordinates, when a note shape was matched. Zero-based. */
    val vmlRow: Int? = null,
    val vmlCol: Int? = null,
    /** True when the VML shape pointed at a different cell than the comments XML (XML wins). */
    val coordinateMismatch: Boolean = false,
) {
    val state: CommentImageState
        get() = when {
            media.any { it.resolution == MediaResolution.RESOLVED } -> CommentImageState.WITH_IMAGES
            media.isNotEmpty() -> CommentImageState.UNRESOLVED_IMAGE
            else -> CommentImageState.TEXT_ONLY
        }

    /** Resolved package paths, de-duplicated, in the order the note shape declared them. */
    val mediaPaths: List<String>
        get() = media.mapNotNull { it.mediaPath }.distinct()
}

/** Where one media file is used. */
data class MediaAssociation(
    val sheetName: String,
    val cell: String,
    /** Comment text, shortened for the list; the full text lives on [LegacyCommentEntry]. */
    val commentPreview: String,
)

/** One file under `xl/media/`, whether or not a comment uses it. */
data class WorkbookMediaEntry(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    /** `png`, `jpeg`, `gif`, `bmp`, `webp`, or the raw extension when the bytes are not recognised. */
    val format: String,
    /** Every comment this file is associated with. Empty means "no association found by this previewer". */
    val associations: List<MediaAssociation>,
) {
    val isReferenced: Boolean get() = associations.isNotEmpty()
}

enum class DiagnosticLevel { INFO, WARNING }

/** A recoverable problem: it never stops the rest of the workbook from being previewed. */
data class CommentDiagnostic(
    val level: DiagnosticLevel,
    val part: String?,
    val message: String,
)

/** A successfully scanned workbook. */
data class WorkbookCommentImages(
    val workbookName: String,
    /** SHA-256 of the workbook bytes, the cache identity. */
    val sha256: String,
    val sheets: List<String>,
    val comments: List<LegacyCommentEntry>,
    val media: List<WorkbookMediaEntry>,
    val diagnostics: List<CommentDiagnostic>,
    /** Parts under `xl/threadedComments/` found in the package (reported, never parsed as notes). */
    val threadedCommentParts: List<String>,
    /** True when this result came from the cache instead of a fresh scan. */
    val fromCache: Boolean = false,
    /** True when a scan limit stopped the walk; the UI must say so instead of silently dropping data. */
    val truncated: Boolean = false,
) {
    val imageCommentCount: Int get() = comments.count { it.state == CommentImageState.WITH_IMAGES }
    val unresolvedCount: Int get() = comments.count { it.state == CommentImageState.UNRESOLVED_IMAGE }
    val textOnlyCount: Int get() = comments.count { it.state == CommentImageState.TEXT_ONLY }
    val referencedMediaCount: Int get() = media.count { it.isReferenced }
}

/** The scan either produced a model or a single, user-readable reason it could not. */
sealed interface CommentScanResult {
    data class Success(val workbook: WorkbookCommentImages) : CommentScanResult
    data class Failure(val message: String, val diagnostics: List<CommentDiagnostic> = emptyList()) : CommentScanResult
}
