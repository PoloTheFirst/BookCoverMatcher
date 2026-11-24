// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import com.bookcovermatcher.MainActivity
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ui.ProductResultCard
import com.bookcovermatcher.ui.theme.BookCoverTheme
import org.junit.Assert.*
import org.junit.Test

class OnlineResultActionsDeviceTest {
    private fun SemanticsNode.descendants(): Sequence<SemanticsNode> = sequence {
        yield(this@descendants)
        for (child in children) yieldAll(child.descendants())
    }

    @Test fun narrowCardKeepsWebsiteOnOneLineAndShowsWishlistMembership() {
        val item = mutableStateOf(ProductResultUi(
            id = "demo", key = "fixture:demo", title = "Demo book", authors = "", meta = "",
            languages = "", coverJpeg = null, detailUrl = "https://example.org/book",
            source = "fixture", provider = "fixture", sourceDomain = "example.org", identifier = null,
        ))
        var opened = ""
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            screen.onActivity { activity ->
                activity.setContent {
                    BookCoverTheme {
                        Box(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                            ProductResultCard(item.value, 0, { opened = it },
                                { item.value = item.value.copy(bookmarked = !item.value.bookmarked) },
                                { item.value = item.value.copy(wishlisted = true) })
                        }
                    }
                }
            }
            fun control(label: String, check: (SemanticsNode) -> Unit) {
                val deadline = SystemClock.uptimeMillis() + 5000
                do {
                    var found = false
                    screen.onActivity { activity ->
                        val roots = activity.window.decorView.allViews.filterIsInstance<RootForTest>()
                        val node = roots.flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
                            .firstOrNull { candidate ->
                                candidate.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                                    candidate.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                            }
                        if (node != null) { check(node); found = true }
                    }
                    if (found) return
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                fail("Missing result control: $label")
            }
            fun click(node: SemanticsNode) {
                val action = generateSequence(node) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
                assertTrue(action())
            }
            control("Expand result", ::click)
            var websiteCenterY = 0f
            var websiteRight = 0f
            control("Open website") { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertEquals(1, layouts.single().lineCount)
                websiteCenterY = node.positionInRoot.y + node.size.height / 2f
                websiteRight = node.positionInRoot.x + node.size.width
                click(node)
                assertEquals(item.value.detailUrl, opened)
            }
            control("↗") { arrow ->
                assertEquals(websiteCenterY, arrow.positionInRoot.y + arrow.size.height / 2f, 1f)
                assertTrue(arrow.positionInRoot.x >= websiteRight)
                assertTrue(arrow.size.width > 0)
            }
            control("+ Wishlist", ::click)
            control("✓ Wishlisted", ::click)
            control("★ Bookmark", ::click)
            control("★ Bookmarked", ::click)
            control("✓ Wishlisted") { assertTrue(item.value.wishlisted) }
        }
    }
}
