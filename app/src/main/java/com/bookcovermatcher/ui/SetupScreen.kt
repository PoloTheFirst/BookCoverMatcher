package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette

/**
 * Shown until the OpenCLIP model file is available. The model is ~100 MB, so it is not part of the source tree:
 * `tools/export_openclip_onnx.py` creates it once, and it is either bundled at build time or imported here.
 */
@Composable
fun SetupScreen(model: ModelUi, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    append("Book")
                    withStyle(SpanStyle(color = Palette.Accent)) { append("Cover") }
                    append(" Matcher")
                },
                color = Palette.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Scan a cover · embed it · find it inside your spreadsheet",
                modifier = Modifier.padding(top = 4.dp),
                color = Palette.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(18.dp))

        Panel("Set up the image model") {
            when (model) {
                is ModelUi.Checking -> Working("Checking the image model…", null)
                is ModelUi.Installing -> Working(model.label, model.progress)
                is ModelUi.Ready -> Working("Starting…", null)
                is ModelUi.Missing -> Instructions(error = null, onImport = onImport)
                is ModelUi.Failed -> Instructions(error = model.message, onImport = onImport)
            }
        }
    }
}

@Composable
private fun Working(label: String, progress: Float?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Palette.Accent, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, color = Palette.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Accent,
                trackColor = Color.White.copy(alpha = 0.09f),
            )
        }
    }
}

@Composable
private fun Instructions(error: String?, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (error != null) {
            Text(
                error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = Color(0xFFFFD9D9),
                fontSize = 13.sp,
            )
        }
        Text(
            "BookCover Matcher compares covers with OpenCLIP, an image-embedding model that runs entirely on this phone " +
                "(nothing is uploaded). The model file is about 100 MB, so it is not part of the source code. " +
                "Create it once on a computer:",
            color = Palette.Text,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
        )
        Text(
            "pip install -r tools/requirements.txt\npython tools/export_openclip_onnx.py",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(12.dp),
            color = Palette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Text(
            "Then copy openclip_image.onnx to this phone and import it below. " +
                "(Developers can instead drop it into app/src/main/assets/models/ before building.)",
            color = Palette.Muted,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        GradientButton(
            onClick = onImport,
            colors = Palette.PrimaryGradient,
            contentColor = Palette.OnAccent,
            modifier = Modifier.fillMaxWidth(),
            glow = Palette.Accent.copy(alpha = 0.6f),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.OnAccent, modifier = Modifier.size(22.dp))
            ButtonLabel("Import model file", Palette.OnAccent)
        }
    }
}
