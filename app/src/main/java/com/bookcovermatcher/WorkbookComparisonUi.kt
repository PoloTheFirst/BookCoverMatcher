package com.bookcovermatcher

import com.bookcovermatcher.core.excel.comments.WorkbookDiff

/**
 * State of the two-workbook comparison screen.
 *
 * [selection] is the library ids ticked for comparison (max two). [diff] is the computed result; [error]
 * is a comparison-level failure, and a side that could not be parsed carries its own `failure` inside
 * [WorkbookDiff] instead of being shown as zero counts.
 */
data class WorkbookComparisonUi(
    val open: Boolean = false,
    val selection: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val diff: WorkbookDiff? = null,
) {
    fun isSelected(id: String): Boolean = id in selection
}
