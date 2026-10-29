package com.bookcovermatcher.core.catalog

/** The four parts of the report; the order is the order the screen lists them in. */
enum class HealthCategory(val label: String) {
    STRUCTURE("Workbook"),
    RECORDS("Records"),
    IMAGES("Images"),
    CONSISTENCY("Consistency"),
}

enum class HealthSeverity(val label: String) {
    /** A confirmed problem that makes the data wrong or unusable. */
    ERROR("Error"),

    /** A confirmed problem that a human should review but that does not block reading. */
    WARNING("Warning"),

    /** Missing optional data or coverage information; never an error by itself. */
    INFO("Info"),
}

/** How the user can act on a finding, kept separate from its severity. */
enum class HealthResolution(val label: String) {
    AUTOMATIC("Can be fixed automatically"),
    MANUAL("Needs your decision"),
    INFORMATIONAL("Information only"),
}

/**
 * One finding about one workbook.
 *
 * [id] is stable for the same underlying issue (category + subject), which is what keeps the report from
 * counting one problem twice. [recordId] matches [CatalogEntry.id] whenever the finding is about a record,
 * so the report, the gallery and the timeline can point at the same thing. Evidence lives in [detail].
 */
data class HealthFinding(
    val id: String,
    val category: HealthCategory,
    val severity: HealthSeverity,
    val title: String,
    val detail: String,
    val action: String,
    val resolution: HealthResolution,
    val sheetName: String? = null,
    val recordId: String? = null,
    val row: Int? = null,
    val field: CatalogField? = null,
    val imagePath: String? = null,
    /** For an aggregate finding: how many items it covers. Null for a single-item finding. */
    val count: Int? = null,
) {
    /** `LIST!128` or `LIST` or the workbook, for a compact list row. */
    val locationLabel: String
        get() = when {
            sheetName == null -> "Workbook"
            row == null -> sheetName
            else -> "$sheetName!${row + 1}"
        }
}

data class HealthSummary(
    val errors: Int,
    val warnings: Int,
    val info: Int,
    val total: Int,
    val byCategory: Map<HealthCategory, Int>,
)

/**
 * The report for one workbook and one mapping.
 *
 * [confirmed] and [informational] are kept apart because they mean different things: a confirmed finding
 * is a defect the parser can prove, an informational one is missing optional data or coverage.
 */
data class HealthReport(
    val findings: List<HealthFinding>,
    val summary: HealthSummary,
    val records: Int,
    val sheets: Int,
    val withCover: Int,
    val withoutCover: Int,
    val unmappedFields: List<CatalogField>,
) {
    val confirmed: List<HealthFinding> get() = findings.filter { it.severity != HealthSeverity.INFO }
    val informational: List<HealthFinding> get() = findings.filter { it.severity == HealthSeverity.INFO }

    fun matching(
        severity: HealthSeverity? = null,
        category: HealthCategory? = null,
    ): List<HealthFinding> = findings.filter { finding ->
        (severity == null || finding.severity == severity) &&
            (category == null || finding.category == category)
    }
}