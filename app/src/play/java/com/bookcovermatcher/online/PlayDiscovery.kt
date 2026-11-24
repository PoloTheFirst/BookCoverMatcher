// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.content.Context
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.data.OnlineSearchPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("UNUSED_PARAMETER")
internal fun createDiscovery(
    context: Context,
    scope: CoroutineScope,
    prefs: OnlineSearchPrefs,
    state: MutableStateFlow<OnlineSearchUi>,
    capture: () -> ByteArray?,
    onMessage: (String, ToastKind) -> Unit,
): DiscoveryLifecycle? = null
