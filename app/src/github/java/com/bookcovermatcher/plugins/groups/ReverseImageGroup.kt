// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.groups

import com.bookcovermatcher.plugins.PlatformGroup
import com.bookcovermatcher.online.IqdbReverseImageSource
import com.bookcovermatcher.online.SauceNaoReverseImageSource

internal class ReverseImageGroup : PlatformGroup(
    id = "bcm.reverse-image-discovery",
    index = 4,
    name = "Reverse Image Discovery",
    description = "Search a cover with SauceNAO or open IQDB in your browser.",
    entries = emptyList(),
    reverseSources = { key -> listOf(SauceNaoReverseImageSource(apiKey = key), IqdbReverseImageSource()) },
)
