// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.online.OnlineSearchController

@Suppress("UNUSED_PARAMETER")
@Composable
fun ReverseImageSection(
    ui: OnlineSearchUi,
    controller: OnlineSearchController,
    onOpenUrl: (String) -> Unit,
    bookmarkedKeys: Set<String> = emptySet(),
    wishlistedKeys: Set<String> = emptySet(),
    onToggleBookmark: (ReverseCandidateUi) -> Unit = {},
    onAddToWishlist: (ReverseCandidateUi) -> Unit = {},
    onBookmarkAllDiscovery: () -> Unit = {},
) = Unit
