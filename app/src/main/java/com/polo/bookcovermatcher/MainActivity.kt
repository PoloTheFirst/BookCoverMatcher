package com.polo.bookcovermatcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.polo.bookcovermatcher.ui.theme.BookCoverMatcherTheme

/**
 * Entry point Activity. Uses Jetpack Compose for UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BookCoverMatcherTheme {
                BookCoverMatcherApp()
            }
        }
    }
}