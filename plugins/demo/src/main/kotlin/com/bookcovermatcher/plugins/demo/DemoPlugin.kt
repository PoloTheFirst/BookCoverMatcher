// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.demo

import com.bookcovermatcher.plugins.*

private val demo = PluginDescriptor(
    "bcm.demo", "Demo card", "Show a sample card on this page.", "1.0",
    2..2, setOf(Capability.INFORMATION_CARD),
)

val demoRegistration = Registration(demo) { DemoPlugin() }

private class DemoPlugin : BundledPlugin {
    override val descriptor = demo
    override suspend fun activate() = InfoCard("Hello from BCM", "Your demo plugin is active.")
}
