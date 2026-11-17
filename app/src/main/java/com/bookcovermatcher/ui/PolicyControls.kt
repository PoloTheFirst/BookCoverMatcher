// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

private val policyDocuments = listOf(
    ReaderDocument("licenses/PRIVACY_POLICY.md", "Privacy Policy", "How BookCoverMatcher handles data", "Privacy Policy"),
    ReaderDocument("licenses/TERMS_AND_CONDITIONS.md", "Terms and Conditions", "Use, responsibilities and software rights", "Terms and Conditions"),
)

/** Local packaged policies: opening the reader does not request the public website. */
@Composable
internal fun PolicyControls(onOpenUrl: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        policyDocuments.forEachIndexed { index, document ->
            GradientButton(
                onClick = { selected = index }, colors = Palette.NavyGradient,
                contentColor = Palette.Text, modifier = Modifier.weight(1f), minHeight = 48.dp,
            ) {
                Text(document.title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    selected?.let { index ->
        PackagedDocumentReader(policyDocuments, index, { selected = null }, onOpenUrl, onOpenUrl)
    }
}
