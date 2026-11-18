// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class PluginStatus { DISABLED, ENABLING, ENABLED, DISABLING, FAILED, UNAVAILABLE }

internal data class PluginRow(
    val descriptor: PluginDescriptor,
    val status: PluginStatus,
    val contribution: PluginContribution? = null,
    val message: String? = null,
)

internal val PluginRow.card get() = contribution as? InfoCard

internal interface PluginChoices {
    fun imported(id: String): Boolean
    fun enabled(id: String): Boolean
    fun save(id: String, enabled: Boolean)
}

/** One lock and at most one activation lifetime per ID. Work is owned by the host ViewModel. */
internal class PluginManager(
    registrations: List<Registration>,
    private val choices: PluginChoices,
    private val owner: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 5000,
) {
    private class Entry(val registration: Registration) {
        val lock = Mutex()
        var lifetime: Job? = null
        @Volatile var cleanupFailed = false
    }

    private val entries = registrations.associate { it.descriptor.id to Entry(it) }
    init {
        require(entries.size == registrations.size) { "Duplicate plugin ID" }
        require(entries.keys.all { it.matches(Regex("[a-z][a-z0-9_.-]{0,79}")) })
    }
    private val state = MutableStateFlow(registrations.map {
        val d = it.descriptor
        if (HOST_PLUGIN_API in d.hostVersions && d.capabilities.isNotEmpty())
            PluginRow(d, PluginStatus.DISABLED)
        else PluginRow(d, PluginStatus.UNAVAILABLE, message = "Incompatible with this app version")
    })
    val rows = state.asStateFlow()

    private val importedIds = MutableStateFlow(emptySet<String>())
    val imported = importedIds.asStateFlow()

    suspend fun importDescriptor(id: String): String = withContext(worker) {
        val entry = entries[id] ?: return@withContext "This plugin is unavailable in this edition."
        entry.lock.withLock {
            if (row(entry).status == PluginStatus.UNAVAILABLE) return@withLock "Incompatible plugin."
            if (id in importedIds.value) return@withLock "Already imported."
            val initial = choices.enabled(id)
            choices.save(id, initial)
            importedIds.update { it + id }
            if (initial) enable(entry)
            "Imported ${entry.registration.descriptor.name}."
        }
    }

    init {
        for (entry in entries.values) {
            // Acquire the lock before exposing the manager so restoration precedes user input.
            owner.launch(start = CoroutineStart.UNDISPATCHED) {
                entry.lock.withLock {
                    withContext(worker) {
                        if (row(entry).status != PluginStatus.UNAVAILABLE) {
                            try {
                                if (choices.imported(id(entry))) {
                                    importedIds.update { it + id(entry) }
                                    if (choices.enabled(id(entry))) enable(entry)
                                }
                            } catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                set(entry, PluginStatus.FAILED, message = "Could not restore setting")
                            }
                        }
                    }
                }
            }
        }
    }

    fun toggle(id: String, enabled: Boolean): Job? {
        val entry = entries[id] ?: return null
        return owner.launch(worker) {
            entry.lock.withLock {
                if (id !in importedIds.value || row(entry).status == PluginStatus.UNAVAILABLE) return@withLock
                if (enabled) enable(entry) else disable(entry)
            }
        }
    }

    private fun id(entry: Entry) = entry.registration.descriptor.id
    private fun row(entry: Entry) = state.value.first { it.descriptor.id == id(entry) }
    private fun set(entry: Entry, status: PluginStatus, card: PluginContribution? = null, message: String? = null) {
        state.update { list -> list.map {
            if (it.descriptor.id == id(entry)) PluginRow(it.descriptor, status, card, message) else it
        } }
    }

    private suspend fun enable(entry: Entry) {
        if (row(entry).status == PluginStatus.ENABLED) return
        if (entry.cleanupFailed) {
            set(entry, PluginStatus.UNAVAILABLE, message = "Cleanup failed. Reopen the app to retry.")
            return
        }
        if (entry.lifetime?.isCompleted == false) {
            set(entry, PluginStatus.FAILED, message = "Still stopping. Try again shortly.")
            return
        }
        set(entry, PluginStatus.ENABLING)
        val ready = CompletableDeferred<PluginContribution>()
        val job = owner.launch(worker) {
            try {
                val plugin = entry.registration.create()
                try {
                    require(plugin.descriptor == entry.registration.descriptor)
                    val contribution = plugin.activate()
                    val capability = when (contribution) {
                        is SettingsPanelContribution -> Capability.SETTINGS_PANEL
                        is InfoCard -> Capability.INFORMATION_CARD
                        is CatalogueProvider -> Capability.CATALOGUE_PROVIDER
                        is PlatformGroupContribution -> Capability.PLATFORM_GROUP
                    }
                    require(capability in plugin.descriptor.capabilities)
                    ready.complete(contribution)
                    awaitCancellation()
                } finally {
                    try {
                        withContext(NonCancellable) { plugin.deactivate() }
                    } catch (e: Exception) {
                        entry.cleanupFailed = true
                        throw e
                    }
                }
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            }
        }
        entry.lifetime = job
        try {
            val card = withTimeout(timeoutMs) { ready.await() }
            choices.save(id(entry), true)
            set(entry, PluginStatus.ENABLED, card)
        } catch (e: Exception) {
            job.cancel()
            currentCoroutineContext().ensureActive()
            val message = try {
                choices.save(id(entry), false)
                "Could not enable. Try again."
            } catch (_: Exception) { "Could not save setting. Try again." }
            withTimeoutOrNull(timeoutMs) { job.join() }
            set(entry, PluginStatus.FAILED, message = message)
        }
    }

    private suspend fun disable(entry: Entry) {
        val previous = row(entry)
        if (previous.status == PluginStatus.DISABLED) return
        set(entry, PluginStatus.DISABLING)
        try {
            choices.save(id(entry), false)
        } catch (_: Exception) {
            set(entry, previous.status, previous.contribution, "Could not save setting. Try again.")
            return
        }
        val job = entry.lifetime
        job?.cancel()
        val stopped = withTimeoutOrNull(timeoutMs) { job?.join(); true } == true
        when {
            !stopped -> set(entry, PluginStatus.FAILED, message = "Still stopping. Try again shortly.")
            entry.cleanupFailed -> set(entry, PluginStatus.UNAVAILABLE, message = "Cleanup failed. Reopen the app to retry.")
            else -> set(entry, PluginStatus.DISABLED)
        }
    }
}
