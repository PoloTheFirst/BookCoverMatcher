package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.ReverseImageProviderUi
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import com.bookcovermatcher.online.OnlineSearchController
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

/**
 * Reverse-image discovery: the two providers, their states, and the candidates to review.
 *
 * The section exists to keep four things visibly separate, because conflating them is the whole failure mode
 * this feature is guarded against:
 *
 *  1. **SauceNAO is tried first**, IQDB second — the rows say so, in that order, with each provider's own state.
 *  2. **IQDB is manual-only** in this build, and the row says exactly that instead of pretending to search.
 *  3. **A candidate is a suggestion** until the user confirms or rejects it; each row shows that decision.
 *  4. **Provider scores are attributed**, never merged into one number.
 */
@Composable
fun ReverseImageSection(
    ui: OnlineSearchUi,
    controller: OnlineSearchController,
    onOpenUrl: (String) -> Unit,
) {
    val reverse = ui.reverse
    FieldLabel("C \u00B7 Reverse-image discovery (SauceNAO first, IQDB second)")

    reverse.providers.forEach { provider ->
        ReverseProviderRow(provider, onOpenUrl)
        Spacer(Modifier.height(6.dp))
    }

    SauceNaoKeyBlock(reverse.sauceNaoKeyHint, controller)
    Spacer(Modifier.height(10.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reverse.running) {
            SmallAction("Cancel", Palette.Danger, controller::cancelReverseSearch)
        } else {
            GradientButton(
                onClick = controller::runReverseSearch,
                colors = Palette.AccentGradient,
                contentColor = Palette.OnAccent2,
                modifier = Modifier.weight(1f),
                minHeight = 42.dp,
                glow = Palette.Accent2.copy(alpha = 0.4f),
            ) { ButtonLabel("Search by this cover", Palette.OnAccent2, 12.5f) }
        }
    }
    Text(
        "SauceNAO searches the image itself. IQDB is manual-only, so its row opens its own page instead.",
        color = Palette.Muted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 6.dp),
    )

    reverse.note?.let {
        Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }

    if (reverse.candidates.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append(reverse.candidates.size).append(" candidate(s)")
                append(" \u00B7 ").append(reverse.undecided.size).append(" undecided")
                if (reverse.confirmedCount > 0) append(" \u00B7 ").append(reverse.confirmedCount).append(" confirmed")
                if (reverse.rejectedCount > 0) append(" \u00B7 ").append(reverse.rejectedCount).append(" rejected")
            },
            color = Palette.Muted,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        reverse.candidates.forEach { candidate ->
            CandidateCard(
                candidate = candidate,
                onOpenUrl = onOpenUrl,
                onConfirm = controller::confirmCandidate,
                onReject = controller::rejectCandidate,
                onDismiss = controller::dismissCandidate,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    IqdbPasteBlock(controller)
}

@Composable
private fun ReverseProviderRow(provider: ReverseImageProviderUi, onOpenUrl: (String) -> Unit) {
    val (label, colour) = stateLabel(provider.state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Navy650)
            .border(1.dp, Palette.Navy700, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
            Spacer(Modifier.width(7.dp))
            Text(
                "${provider.priority} \u00B7 ${provider.displayName}",
                color = Palette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = colour, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (provider.searchPageUrl != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onOpenUrl(provider.searchPageUrl) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("Open \u2197", color = Palette.Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        provider.detail?.let {
            Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
        val meta = buildString {
            if (provider.resultCount > 0) append(provider.resultCount).append(" result(s)")
            if (provider.quota != null) {
                if (isNotEmpty()) append(" \u00B7 ")
                append("quota: ").append(provider.quota)
            }
        }
        if (meta.isNotEmpty()) {
            Text(meta, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Label + colour for every provider state, so no state is ever drawn as a plain success. */
private fun stateLabel(state: ReverseImageState): Pair<String, Color> = when (state) {
    ReverseImageState.READY -> "READY" to Palette.Ok
    ReverseImageState.SEARCHING -> "SEARCHING\u2026" to Palette.Accent
    ReverseImageState.SUCCESS -> "RESULTS" to Palette.Ok
    ReverseImageState.NO_RESULTS -> "NOTHING FOUND" to Palette.Warn
    ReverseImageState.NOT_CONFIGURED -> "NEEDS KEY" to Palette.Warn
    ReverseImageState.MANUAL_ONLY -> "MANUAL ONLY" to Palette.Muted
    ReverseImageState.TIMEOUT -> "TIMED OUT" to Palette.Danger
    ReverseImageState.RATE_LIMITED -> "RATE LIMITED" to Palette.Danger
    ReverseImageState.QUOTA_EXCEEDED -> "QUOTA USED UP" to Palette.Danger
    ReverseImageState.UNAVAILABLE -> "UNREACHABLE" to Palette.Danger
    ReverseImageState.BLOCKED -> "BLOCKED" to Palette.Danger
    ReverseImageState.CANCELLED -> "CANCELLED" to Palette.Muted
    ReverseImageState.ERROR -> "FAILED" to Palette.Danger
}

/**
 * The SauceNAO key: enter once, then only ever see stars.
 *
 * The field is deliberately one-way — after saving, the panel shows `••••••••ab12` and offers **Replace** and
 * **Remove**, never the key itself. *Replace* opens the same field without deleting the working key first, so a
 * mistyped replacement cannot leave the user with no key at all. Nothing here writes the key to a log, a toast or
 * a crash report.
 */
@Composable
private fun SauceNaoKeyBlock(hint: String?, controller: OnlineSearchController) {
    var text by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    if (hint != null && !replacing) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SauceNAO key stored: $hint", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            SmallAction("Replace", Palette.Accent) { replacing = true }
            SmallAction("Remove", Palette.Danger, controller::clearSauceNaoKey)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Navy650)
                .border(1.dp, Palette.Navy700, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    if (replacing) "New SauceNAO API key" else "SauceNAO API key",
                    color = Palette.Muted,
                    fontSize = 11.5.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Palette.Text, fontSize = 11.5.sp),
                singleLine = true,
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SmallAction("Save key", Palette.Accent) {
            controller.saveSauceNaoKey(text)
            text = ""
            replacing = false
        }
        if (replacing) {
            SmallAction("Cancel", Palette.Muted) {
                text = ""
                replacing = false
            }
        }
    }
    Text(
        "Stored encrypted with an Android Keystore key; never shown again. Without it SauceNAO is not queried.",
        color = Palette.Muted,
        fontSize = 9.5.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One candidate: what it is, who found it, what the user decided. */
@Composable
private fun CandidateCard(
    candidate: ReverseCandidateUi,
    onOpenUrl: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Navy650)
            .border(1.dp, decisionColour(candidate.decision), RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                candidate.title.ifBlank { "(no title sent by the provider)" },
                color = Palette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(top = 2.dp),
            )
            if (candidate.isIqdbOnly) {
                // Dismiss this IQDB card from the list. Only IQDB-only candidates get it: a candidate SauceNAO also
                // found keeps its row, because throwing it away would discard the primary provider's evidence.
                IconButton(
                    onClick = { onDismiss(candidate.key) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Dismiss IQDB result",
                        tint = Palette.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        val details = buildString {
            if (candidate.circle.isNotBlank()) append("circle ").append(candidate.circle)
            if (candidate.author.isNotBlank()) {
                if (isNotEmpty()) append(" \u00B7 ")
                append("author ").append(candidate.author)
            }
            if (candidate.productId.isNotBlank()) {
                if (isNotEmpty()) append(" \u00B7 ")
                append(candidate.productId)
            }
        }
        if (details.isNotEmpty()) {
            Text(details, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(candidate.evidence, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        if (candidate.providerCount > 1) {
            Text(
                "Reported by ${candidate.providerCount} providers \u2014 stronger provenance, not a probability.",
                color = Palette.Muted,
                fontSize = 9.5.sp,
            )
        }
        if (candidate.manual) {
            Text("You supplied this candidate.", color = Palette.Warn, fontSize = 9.5.sp)
        }
        candidate.note?.let { Text(it, color = Palette.Muted, fontSize = 9.5.sp) }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                decisionLabel(candidate.decision),
                // White on the navy card: the suggestion warning used to be drawn in the card colour and was
                // effectively invisible (v2.5.0-dev.2 defect). Colour is fixed per state, so it cannot fall back
                // to a dark token on recomposition.
                color = decisionTextColour(candidate.decision),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            candidate.sourceUrl?.let { url -> SmallAction("Open \u2197", Palette.Accent) { onOpenUrl(url) } }
            SmallAction("Confirm", Palette.Ok) { onConfirm(candidate.key) }
            SmallAction("Reject", Palette.Danger) { onReject(candidate.key) }
        }
    }
}

private fun decisionLabel(decision: CandidateDecision): String = when (decision) {
    CandidateDecision.SUGGESTED -> "Suggestion \u2014 not confirmed by you"
    CandidateDecision.CONFIRMED -> "Confirmed by you"
    CandidateDecision.REJECTED -> "Rejected by you"
}

/**
 * The colour of the decision line.
 *
 * `SUGGESTED` is **white** (`Palette.Text`, the app's white body text) because it is the one state the user must
 * be able to read at a glance on the navy card; it previously reused the card border colour and was invisible.
 */
private fun decisionTextColour(decision: CandidateDecision): Color = when (decision) {
    CandidateDecision.SUGGESTED -> Color.White
    CandidateDecision.CONFIRMED -> Palette.Ok
    CandidateDecision.REJECTED -> Palette.Danger
}

private fun decisionColour(decision: CandidateDecision): Color = when (decision) {
    CandidateDecision.SUGGESTED -> Palette.Navy700
    CandidateDecision.CONFIRMED -> Palette.Ok
    CandidateDecision.REJECTED -> Palette.Danger
}
/** A small text button, matching the panel's other inline actions. */
@Composable
private fun SmallAction(label: String, colour: Color, onClick: () -> Unit) {
    Text(
        label,
        color = colour,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * The IQDB half of the workflow: open IQDB in the browser, then paste what it found.
 *
 * The app never submits the image to IQDB (no public API; its robots file disallows its search routes), so this
 * block is the *only* way an IQDB result enters the app — and the candidate it produces is flagged as
 * user-supplied.
 *
 * **Always expanded, by requirement.** v2.5.0-dev.2 hid this behind a "Paste an IQDB result" button, which made
 * the one IQDB input path look like an optional extra. There is no toggle and no collapsed state left: the field
 * and its action are rendered directly, and the only local state is the text being typed.
 */
@Composable
private fun IqdbPasteBlock(controller: OnlineSearchController) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Navy650)
            .border(1.dp, Palette.Navy700, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text("Paste an IQDB result you found", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            "Open IQDB in your browser, search the cover there, then paste the result here \u2014 one per line, " +
                "e.g. \"92% https://danbooru.donmai.us/posts/123\". The app did not search IQDB; it only " +
                "normalises what you pasted.",
            color = Palette.Muted,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Navy700)
                .padding(8.dp),
        ) {
            if (text.isEmpty()) Text("92% https://\u2026", color = Palette.Muted, fontSize = 11.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Palette.Text, fontSize = 11.sp),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("", modifier = Modifier.weight(1f))
            SmallAction("Add results", Palette.Ok) {
                controller.importIqdbResult(text)
                text = ""
            }
        }
    }
}
