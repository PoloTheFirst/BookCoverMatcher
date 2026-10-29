package com.bookcovermatcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.InsightsTab
import com.bookcovermatcher.WorkbookCommentsController
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CollectionTimeline
import com.bookcovermatcher.core.catalog.FieldState
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthFinding
import com.bookcovermatcher.core.catalog.HealthReport
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.MappingIssue
import com.bookcovermatcher.core.catalog.MappingIssueKind
import com.bookcovermatcher.core.catalog.MetricCount
import com.bookcovermatcher.core.catalog.TimelineEntry
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.ui.theme.Palette

/**
 * The Insights surface of the workbook preview: health, gallery, analytics, timeline and mapping.
 *
 * Every panel reads the one catalog the controller built, so a record looks the same in all of them.
 * Nothing here parses a workbook or computes a statistic: the panels display results.
 */
@Composable
fun InsightsPanel(
    state: WorkbookCommentsUi,
    controller: WorkbookCommentsController,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        InsightTabs(state, controller)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val catalog = state.catalog
            when {
                catalog == null && state.insightsBusy -> InsightNote("Reading the workbook's records…")
                catalog == null && state.insightsError != null -> Column(Modifier.fillMaxSize()) {
                    InsightNote(state.insightsError)
                    InsightAction("Try again") { controller.refreshInsights() }
                }
                catalog == null -> InsightNote("Nothing to report yet.")
                else -> when (state.insightsTab) {
                    InsightsTab.HEALTH -> HealthPanel(state, controller)
                    InsightsTab.GALLERY -> GalleryPanel(state, controller)
                    InsightsTab.ANALYTICS -> AnalyticsPanel(state, controller)
                    InsightsTab.TIMELINE -> TimelinePanel(state, controller)
                    InsightsTab.FIELDS -> FieldsPanel(state, controller)
                }
            }
        }
    }
}

@Composable
private fun InsightTabs(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in InsightsTab.entries) {
            ChoiceChip(tab.label, state.insightsTab == tab) { controller.showInsights(tab) }
        }
    }
}

/** A centred explanation: used for loading, empty and unavailable states alike. */
@Composable
internal fun InsightNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, fontSize = 11.5.sp)
    }
}

/** A small accent-coloured button, the same shape the image browser uses. */
@Composable
internal fun InsightAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** A labelled count with a proportional bar, used by the analytics breakdowns. */
@Composable
internal fun MetricBar(label: String, count: Int, max: Int, barColor: androidx.compose.ui.graphics.Color = Palette.Accent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Palette.Text,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.width(110.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Navy800),
        ) {
            val fraction = if (max <= 0) 0f else (count.toFloat() / max).coerceIn(0.02f, 1f)
            Box(Modifier.fillMaxWidth(fraction).height(7.dp).background(barColor))
        }
        Text(
            count.toString(),
            color = Palette.Muted,
            fontSize = 10.5.sp,
            modifier = Modifier.width(38.dp).padding(start = 6.dp),
        )
    }
}
// ---------------------------------------------------------------------------------------------
// Feature A: Spreadsheet Health Report
// ---------------------------------------------------------------------------------------------

@Composable
private fun HealthPanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val report = state.health
    if (report == null) {
        InsightNote("The report is still being built.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { HealthSummary(report) }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoiceChip(
                    "All ${report.summary.total}",
                    state.healthSeverity == null && state.healthCategory == null,
                ) { controller.setHealthFilter(null, null) }
                ChoiceChip("Errors ${report.summary.errors}", state.healthSeverity == HealthSeverity.ERROR) {
                    controller.setHealthFilter(HealthSeverity.ERROR)
                }
                ChoiceChip("Warnings ${report.summary.warnings}", state.healthSeverity == HealthSeverity.WARNING) {
                    controller.setHealthFilter(HealthSeverity.WARNING)
                }
                ChoiceChip("Info ${report.summary.info}", state.healthSeverity == HealthSeverity.INFO) {
                    controller.setHealthFilter(HealthSeverity.INFO)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (category in HealthCategory.entries) {
                    val count = report.summary.byCategory[category] ?: 0
                    ChoiceChip("${category.label} $count", state.healthCategory == category) {
                        controller.setHealthFilter(
                            state.healthSeverity,
                            if (state.healthCategory == category) null else category,
                        )
                    }
                }
            }
        }
        if (report.unmappedFields.isNotEmpty()) {
            item {
                InfoLine(
                    "Not mapped on any worksheet: " + report.unmappedFields.joinToString(", ") { it.label } +
                        ". Those fields are unmapped, not missing.",
                )
            }
        }
        item {
            InfoLine(
                "Confirmed problems come first, then missing optional data and coverage. The report never " +
                    "repairs anything and never writes to the workbook.",
            )
        }
        if (state.visibleFindings.isEmpty()) {
            item { InsightNote("No finding matches this filter.") }
        }
        items(state.visibleFindings, key = { it.id }) { finding -> FindingCard(finding, controller) }
    }
}

@Composable
private fun HealthSummary(report: HealthReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("Records", report.records.toString(), Modifier.weight(1f))
            StatTile("Worksheets", report.sheets.toString(), Modifier.weight(1f))
            StatTile("With cover", report.withCover.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("No cover", report.withoutCover.toString(), Modifier.weight(1f))
            StatTile("Errors", report.summary.errors.toString(), Modifier.weight(1f), Palette.Warn)
            StatTile("Warnings", report.summary.warnings.toString(), Modifier.weight(1f), Palette.Warn)
            StatTile("Info", report.summary.info.toString(), Modifier.weight(1f), Palette.Muted)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = Palette.Text,
) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(text, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FindingCard(finding: HealthFinding, controller: WorkbookCommentsController) {
    val color = when (finding.severity) {
        HealthSeverity.ERROR -> Palette.Warn
        HealthSeverity.WARNING -> Palette.Warn
        HealthSeverity.INFO -> Palette.Muted
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                finding.severity.label.uppercase(),
                color = color,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .border(1.dp, color, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                finding.title,
                color = Palette.Text,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val meta = buildString {
            append(finding.locationLabel)
            append(" \u00B7 ").append(finding.category.label)
            finding.count?.let { append(" \u00B7 ").append(it).append(" item(s)") }
            append(" \u00B7 ").append(finding.resolution.label)
        }
        Text(meta, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
        Text(
            finding.detail,
            color = Palette.Text.copy(alpha = 0.85f),
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(finding.action, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
        if (finding.recordId != null || finding.imagePath != null) {
            InsightAction("Go to source") { controller.openFindingSource(finding) }
        }
    }
}
// ---------------------------------------------------------------------------------------------
// Feature B: Visual Spreadsheet Browser
// ---------------------------------------------------------------------------------------------

@Composable
private fun GalleryPanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val catalog = state.catalog ?: return
    val filter = state.galleryFilter
    Column(Modifier.fillMaxSize()) {
        SearchField(filter.query, { controller.setGalleryQuery(it) }, "Search title, circle, artist, ID or cell")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceChip("All", !filter.onlyWithCover && !filter.onlyWithoutCover) {
                controller.setGalleryFilter(filter.copy(onlyWithCover = false, onlyWithoutCover = false))
            }
            ChoiceChip("With cover", filter.onlyWithCover) {
                controller.setGalleryFilter(filter.copy(onlyWithCover = true, onlyWithoutCover = false))
            }
            ChoiceChip("No cover", filter.onlyWithoutCover) {
                controller.setGalleryFilter(filter.copy(onlyWithCover = false, onlyWithoutCover = true))
            }
            ChoiceChip("Missing title", filter.onlyMissingTitle) {
                controller.setGalleryFilter(filter.copy(onlyMissingTitle = !filter.onlyMissingTitle))
            }
            ChoiceChip("Missing metadata", filter.onlyMissingMetadata) {
                controller.setGalleryFilter(filter.copy(onlyMissingMetadata = !filter.onlyMissingMetadata))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (sort in GallerySort.entries) {
                ChoiceChip(sort.label, state.gallerySort == sort) { controller.setGallerySort(sort) }
            }
            InsightAction(if (state.galleryAscending) "A \u2192 Z" else "Z \u2192 A") { controller.toggleGalleryOrder() }
        }
        Text(
            "${state.visibleCatalog.size} of ${catalog.entries.size} record(s), " +
                "${catalog.withVerifiedCover} with a verified cover",
            color = Palette.Muted,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (state.visibleCatalog.isEmpty()) {
            InsightNote("No record matches these filters.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.visibleCatalog, key = { it.id }) { entry -> GalleryCard(entry, controller) }
        }
    }
}

/**
 * One record as a card. A missing value is shown as missing (never padded) and the cover state is always
 * visible, so a card cannot look complete when it is not.
 */
@Composable
private fun GalleryCard(entry: CatalogEntry, controller: WorkbookCommentsController) {
    val coverPath = entry.images.firstOrNull { it.isVerified }?.mediaPath
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Navy700),
            contentAlignment = Alignment.Center,
        ) {
            when {
                coverPath != null -> CoverThumb(coverPath, controller)
                entry.hasUnresolvedImage -> Text("Cover unresolved", color = Palette.Warn, fontSize = 9.5.sp)
                else -> Text("No cover", color = Palette.Muted, fontSize = 9.5.sp)
            }
        }
        Text(
            entry.title ?: "No title",
            color = Palette.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val circle = entry.text(CatalogField.CIRCLE)
        val artist = entry.text(CatalogField.ARTIST)
        if (circle != null) Text(circle, color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist != null) {
            Text("Artist: $artist", color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        entry.text(CatalogField.IDENTIFIER)?.let {
            Text(it, color = Palette.Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(entry.locationLabel, color = Palette.Muted, fontSize = 9.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverPath != null) InsightAction("Open") { controller.openViewer(coverPath) }
            InsightAction("Source") { controller.openRecordSource(entry) }
        }
    }
}

/** A downsampled cover; the full image is only decoded when the viewer is opened. */
@Composable
private fun CoverThumb(path: String, controller: WorkbookCommentsController) {
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(path) {
        bytes = controller.mediaBytes(path)?.takeIf { !controller.tooLarge(it) }
    }
    val bitmap = remember(bytes) { bytes?.let { decodeInsightThumb(it, 240) } }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = path.substringAfterLast('/'),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
    } else {
        Text("Cover not readable", color = Palette.Muted, fontSize = 9.5.sp)
    }
}

/** One thumbnail is never a full-resolution bitmap: the sample size is doubled until it fits. */
private fun decodeInsightThumb(bytes: ByteArray, maxEdge: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest > 0 && longest / sample > maxEdge) sample *= 2
    android.graphics.BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}
// ---------------------------------------------------------------------------------------------
// Feature D: Collection Analytics
// ---------------------------------------------------------------------------------------------

@Composable
private fun AnalyticsPanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val analytics = state.analytics
    if (analytics == null) {
        InsightNote("The analytics are still being built.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("Records", analytics.records.toString(), Modifier.weight(1f))
                StatTile("Worksheets", analytics.sheets.toString(), Modifier.weight(1f))
                StatTile("With cover", analytics.withVerifiedCover.toString(), Modifier.weight(1f))
                StatTile("No cover", analytics.withoutVerifiedCover.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("No title", analytics.missingTitle.toString(), Modifier.weight(1f), Palette.Warn)
                StatTile("No circle", analytics.missingCircle.toString(), Modifier.weight(1f))
                StatTile("No artist", analytics.missingArtist.toString(), Modifier.weight(1f))
                StatTile("No ID", analytics.missingIdentifier.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("Duplicate IDs", analytics.duplicateIdentifierGroups.toString(), Modifier.weight(1f), Palette.Warn)
                StatTile("Rows with a duplicate ID", analytics.duplicateIdentifierRecords.toString(), Modifier.weight(1f))
                StatTile("Possible duplicates", analytics.potentialDuplicateGroups.toString(), Modifier.weight(1f))
            }
        }
        item {
            InfoLine(
                "One record is one source row. A blank cell counts as missing, never as zero. Potential " +
                    "duplicates are reported and never subtracted from the totals.",
            )
        }
        if (analytics.records == 0) item { InsightNote("This workbook has no catalogue records to analyse.") }
        if (analytics.mappedFields.isNotEmpty()) {
            item { InfoLine("Mapped fields: " + analytics.mappedFields.joinToString(", ") { it.label } + ".") }
        }
        if (analytics.unmappedFields.isNotEmpty()) {
            item { InfoLine("Unmapped fields: " + analytics.unmappedFields.joinToString(", ") { it.label } + ".") }
        }
        item { Breakdown("By circle", analytics.byCircle) }
        item { Breakdown("By artist", analytics.byArtist) }
        item { Breakdown("By tag", analytics.byTags) }
        item { Breakdown("By worksheet", analytics.byWorksheet) }
        item { YearBreakdown(analytics.byPublicationYear, analytics.unknownPublicationYear) }
        item {
            InfoLine(
                "Publication years come from the mapped date column only. A record whose date is absent or " +
                    "unreadable stays in the unknown count instead of being given a year.",
            )
        }
        item { InsightAction("Open the gallery") { controller.showInsights(InsightsTab.GALLERY) } }
    }
}

@Composable
private fun Breakdown(title: String, values: List<MetricCount>) {
    if (values.isEmpty()) return
    val max = values.maxOf { it.count }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$title \u00B7 top ${values.size}",
            color = Palette.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        for (value in values) MetricBar(value.label, value.count, max)
    }
}

@Composable
private fun YearBreakdown(values: List<MetricCount>, unknown: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "By publication year",
            color = Palette.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        if (values.isEmpty()) {
            Text("No readable publication year.", color = Palette.Muted, fontSize = 10.sp)
        } else {
            val max = values.maxOf { it.count }
            for (value in values) MetricBar(value.label, value.count, max)
        }
        Text("Unknown year: $unknown record(s)", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
// ---------------------------------------------------------------------------------------------
// Feature E: Collection Timeline
// ---------------------------------------------------------------------------------------------

@Composable
private fun TimelinePanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val timeline = state.timeline
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (mode in TimelineMode.entries) {
                val available = when (mode) {
                    TimelineMode.PUBLICATION -> state.publicationTimeline?.available == true
                    TimelineMode.ACQUISITION -> false
                    TimelineMode.IMPORT -> state.importTimeline?.available == true
                }
                ChoiceChip(if (available) mode.label else mode.label + " \u00B7 n/a", state.timelineMode == mode) {
                    controller.setTimelineMode(mode)
                }
            }
        }
        if (timeline == null) {
            InsightNote("The timeline is still being built.")
            return
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${timeline.datedCount} dated \u00B7 ${timeline.unknownCount} without a usable date",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                modifier = Modifier.weight(1f),
            )
            InsightAction(if (state.timelineAscending) "Oldest first" else "Newest first") {
                controller.toggleTimelineOrder()
            }
        }
        if (!timeline.available && timeline.unavailableReason != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Palette.Navy800)
                    .border(1.dp, Palette.Line, RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Text("This timeline is not available", color = Palette.Warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(timeline.unavailableReason, color = Palette.Text.copy(alpha = 0.85f), fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        val sections = if (state.timelineAscending) {
            timeline.sections.reversed().map { section -> section.copy(entries = section.entries.reversed()) }
        } else {
            timeline.sections
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (section in sections) {
                item(key = "year-${section.year}") { YearHeader(section.year.toString(), section.entries.size) }
                items(section.entries, key = { "entry-${it.recordId}" }) { entry ->
                    TimelineRow(entry, controller)
                }
            }
            if (timeline.unknown.isNotEmpty()) {
                item(key = "year-unknown") { YearHeader("No date known", timeline.unknown.size) }
                items(timeline.unknown, key = { "unknown-${it.recordId}" }) { entry ->
                    TimelineRow(entry, controller)
                }
            }
            if (timeline.sections.isEmpty() && timeline.unknown.isEmpty()) {
                item { InsightNote("No records to place on a timeline.") }
            }
        }
    }
}

@Composable
private fun YearHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text("$count record(s)", color = Palette.Muted, fontSize = 9.5.sp)
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, controller: WorkbookCommentsController) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Navy700),
            contentAlignment = Alignment.Center,
        ) {
            val path = entry.mediaPath
            if (path != null) CoverThumb(path, controller) else Text("\u2014", color = Palette.Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.dateLabel, color = Palette.Accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(entry.locationLabel, color = Palette.Muted, fontSize = 9.sp)
            }
            Text(
                entry.title.ifEmpty { "No title" },
                color = Palette.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
            val meta = listOfNotNull(
                entry.circle,
                entry.artist?.let { "Artist: $it" },
                entry.identifier,
            ).joinToString(" \u00B7 ")
            if (meta.isNotEmpty()) {
                Text(meta, color = Palette.Muted, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                entry.mediaPath?.let { path -> InsightAction("Open image") { controller.openViewer(path) } }
                InsightAction("Go to row") { controller.openTimelineEntry(entry) }
            }
        }
    }
}
// ---------------------------------------------------------------------------------------------
// Feature C: Column Mapping Profiles
// ---------------------------------------------------------------------------------------------

@Composable
private fun FieldsPanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val catalog = state.catalog ?: return
    var picking by remember { mutableStateOf<CatalogField?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceChip("Detected", state.activeProfile == null) { controller.selectProfile(null) }
            for (profile in state.profiles) {
                ChoiceChip(profile.name, state.activeProfile == profile.name) { controller.selectProfile(profile.name) }
            }
        }
        if (catalog.sheetNames.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (sheet in catalog.sheetNames) {
                    ChoiceChip(sheet, state.mappingSheet == sheet) { controller.selectMappingSheet(sheet) }
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                InfoLine(
                    "Applied mapping: ${catalog.profileName}. A profile stores header names, not column " +
                        "positions, so inserting a column does not shift the mapping. The workbook is never modified.",
                )
            }
            items(CatalogField.ordered, key = { it.name }) { field ->
                FieldRow(field, state.mappingDraft[field]) { picking = field }
            }
            if (state.mappingIssues.isNotEmpty()) {
                item { IssueBlock(state.mappingIssues) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    InsightAction("Apply mapping") { controller.applyMapping() }
                    InsightAction("Reset") { controller.resetMapping() }
                    InsightAction("Save profile\u2026") { saving = true }
                    if (state.activeProfile != null) {
                        InsightAction("Delete profile") { controller.deleteProfile(state.activeProfile) }
                    }
                }
            }
            item {
                Text(
                    "Preview \u00B7 first ${state.mappingPreview.size} record(s) of ${state.mappingSheet ?: "-"}",
                    color = Palette.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.mappingPreview.isEmpty()) {
                item { InsightNote("This worksheet has no records to preview.") }
            }
            items(state.mappingPreview, key = { it.id }) { entry -> PreviewRow(entry) }
        }
    }
    val field = picking
    if (field != null) {
        HeaderPickerDialog(
            field = field,
            headers = state.mappingHeaders,
            current = state.mappingDraft[field],
            onPick = { header -> controller.mapField(field, header); picking = null },
            onDismiss = { picking = null },
        )
    }
    if (saving) {
        SaveProfileDialog(
            defaultName = state.mappingSheet ?: "",
            onSave = { name -> controller.saveProfile(name); saving = false },
            onDismiss = { saving = false },
        )
    }
}

@Composable
private fun FieldRow(field: CatalogField, header: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                field.label + if (field.required) " *" else "",
                color = Palette.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (header == null) "Not mapped" else header,
                color = if (header == null) Palette.Muted else Palette.Accent,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Change", color = Palette.Muted, fontSize = 9.5.sp)
    }
}

@Composable
private fun IssueBlock(issues: List<MappingIssue>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Navy800)
            .border(1.dp, Palette.Warn, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Mapping issues", color = Palette.Warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        for (issue in issues) {
            val label = when (issue.kind) {
                MappingIssueKind.HEADER_MISSING -> "Missing column"
                MappingIssueKind.HEADER_AMBIGUOUS -> "Ambiguous column"
                MappingIssueKind.SHARED_HEADER -> "Shared column"
                MappingIssueKind.REQUIRED_UNMAPPED -> "Required field unmapped"
            }
            Text("$label \u00B7 ${issue.detail}", color = Palette.Text.copy(alpha = 0.85f), fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun PreviewRow(entry: CatalogEntry) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Text(entry.title ?: "No title", color = Palette.Text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(entry.text(CatalogField.CIRCLE), entry.text(CatalogField.ARTIST), entry.text(CatalogField.IDENTIFIER))
                .joinToString(" \u00B7 ")
                .ifEmpty { entry.locationLabel },
            color = Palette.Muted,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val missing = CatalogField.ordered.count { entry.state(it) == FieldState.MISSING }
        val unmapped = CatalogField.ordered.count { entry.state(it) == FieldState.UNMAPPED }
        Text(
            "${entry.locationLabel} \u00B7 missing $missing \u00B7 unmapped $unmapped",
            color = Palette.Muted,
            fontSize = 9.sp,
        )
    }
}
/** Which worksheet column feeds one canonical field: explicit, never guessed. */
@Composable
private fun HeaderPickerDialog(
    field: CatalogField,
    headers: List<String>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(
                "Map ${field.label}" + if (field.required) " (required)" else "",
                color = Palette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Columns of this worksheet, exactly as the workbook spells them.",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "none") { PickRow("Not mapped", current == null) { onPick(null) } }
                items(headers, key = { it }) { header ->
                    PickRow(header, current == header) { onPick(header) }
                }
            }
            InsightAction("Cancel", onDismiss)
        }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Palette.OnAccent else Palette.Text,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.Accent else Palette.Navy700)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

@Composable
private fun SaveProfileDialog(defaultName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text("Save mapping profile", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "The profile keeps the header names of this mapping so it can be reused on another workbook " +
                    "with the same columns. The workbook itself is not changed.",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            SearchField(name, { name = it }, "Profile name")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InsightAction("Save") { onSave(name) }
                InsightAction("Cancel", onDismiss)
            }
        }
    }
}