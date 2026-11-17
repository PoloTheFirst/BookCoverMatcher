// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.R
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette

/** A navigation destination only; plugin installation and loading are not implemented. */
@Composable
fun PluginsScreen() {
    Column(Modifier.fillMaxWidth()) {
        Text("Plugins", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        Panel("Plugin support") {
            Icon(
                painter = painterResource(R.drawable.ic_puzzle),
                contentDescription = null,
                tint = Palette.Accent,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No plugins available yet", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Plugin downloads and installation are not available in this version.",
                color = Palette.Muted,
                fontSize = 13.sp,
            )
        }
    }
}
