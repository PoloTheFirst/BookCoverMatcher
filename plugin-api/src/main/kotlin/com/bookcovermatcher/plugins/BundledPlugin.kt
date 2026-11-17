// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

/** Public contract for trusted implementations linked into the application. */
const val HOST_PLUGIN_API = 1

enum class Capability { INFORMATION_CARD }

data class PluginDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val hostVersions: IntRange,
    val capabilities: Set<Capability>,
)

data class InfoCard(val title: String, val body: String)

interface BundledPlugin {
    val descriptor: PluginDescriptor

    /** Prepare this activation's card. Implementations must cooperate with cancellation. */
    suspend fun activate(): InfoCard

    /** Release activation resources, including after partial initialization. */
    suspend fun deactivate() {}
}

class Registration(val descriptor: PluginDescriptor, val create: () -> BundledPlugin)
