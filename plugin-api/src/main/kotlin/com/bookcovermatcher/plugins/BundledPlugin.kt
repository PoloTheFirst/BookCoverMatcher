// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

/** Public contract for trusted implementations linked into the application. */
const val HOST_PLUGIN_API = 2

enum class Capability { INFORMATION_CARD, CATALOGUE_PROVIDER, PLATFORM_GROUP, SETTINGS_PANEL }

data class PluginDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val hostVersions: IntRange,
    val capabilities: Set<Capability>,
)

sealed interface PluginContribution
data class InfoCard(val title: String, val body: String) : PluginContribution
abstract class SettingsPanelContribution : PluginContribution
abstract class PlatformGroupContribution : PluginContribution
abstract class CatalogueProvider(val providerId: String) : PluginContribution

interface BundledPlugin {
    val descriptor: PluginDescriptor

    /** Prepare this activation's contribution. Implementations must cooperate with cancellation. */
    suspend fun activate(): PluginContribution

    /** Release activation resources, including after partial initialization. */
    suspend fun deactivate() {}
}

class Registration(val descriptor: PluginDescriptor, val create: () -> BundledPlugin)
