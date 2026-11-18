// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class PluginStatus { DISABLED, ENABLING, ENABLED, DISABLING, FAILED, UNAVAILABLE, PAUSED, FETCHING }

internal data class PluginRow(
    val descriptor: PluginDescriptor,
    val status: PluginStatus,
    val contribution: PluginContribution? = null,
    val message: String? = null,
    val rememberedOn: Boolean = false,
)

internal val PluginRow.card get() = contribution as? InfoCard

internal interface PluginChoices {
    fun imported(id: String): Boolean
    fun enabled(id: String): Boolean
    fun save(id: String, enabled: Boolean)
    fun removed(id: String): Boolean
    fun remove(id: String)
}

/** Fetch returns only after the edition has verified the descriptor and its expected ID. */
internal class PluginCatalogue(
    val parent: String,
    val fetch: Map<String, suspend () -> Unit>,
)

/** Settings changes are serialized. Network requests run independently outside the lock. */
internal class PluginManager(
    registrations: List<Registration>,
    private val choices: PluginChoices,
    private val owner: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 5000,
    val catalogue: PluginCatalogue? = null,
) {
    private class Entry(val registration: Registration) {
        var download: Job? = null
        var lifetime: Job? = null
        @Volatile var cleanupFailed = false
    }

    private val lock = Mutex()
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
        lock.withLock {
            if (isChild(id)) return@withLock "Manage this plugin in Plugin Marketplace."
            if (row(entry).status == PluginStatus.UNAVAILABLE) return@withLock "Incompatible plugin."
            if (id in importedIds.value) return@withLock "Already imported."
            install(entry)
            if (id == catalogue?.parent) reconcileCatalogue()
            "Imported ${entry.registration.descriptor.name}."
        }
    }

    private fun isChild(id: String) = catalogue?.fetch?.containsKey(id) == true
    private fun parentActive() = entries[catalogue?.parent]?.let { row(it).status == PluginStatus.ENABLED } == true

    private suspend fun install(entry: Entry) {
        val initial = choices.enabled(id(entry))
        choices.save(id(entry), initial)
        importedIds.update { it + id(entry) }
        set(entry, PluginStatus.DISABLED)
        if (initial) enable(entry)
    }

    init {
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock {
                withContext(worker) {
                    for (entry in entries.values) {
                        if (row(entry).status == PluginStatus.UNAVAILABLE) continue
                        val id = id(entry)
                        try {
                            if (choices.imported(id)) {
                                importedIds.update { it + id }
                                if (!isChild(id) && choices.enabled(id)) enable(entry)
                            }
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            set(entry, PluginStatus.FAILED, message = "Could not restore setting")
                        }
                    }
                    // Give upgraded standalone providers a visible, initially off parent.
                    catalogue?.let { catalog ->
                        if (importedIds.value.any(::isChild) && catalog.parent !in importedIds.value &&
                            !choices.removed(catalog.parent)) {
                            install(entries.getValue(catalog.parent))
                        }
                    }
                    reconcileCatalogue()
                }
            }
        }
    }

    fun toggle(id: String, enabled: Boolean): Job? {
        val entry = entries[id] ?: return null
        return owner.launch(worker) {
            lock.withLock {
                if (id !in importedIds.value || row(entry).status == PluginStatus.UNAVAILABLE) return@withLock
                if (isChild(id) && !parentActive()) return@withLock
                if (enabled) enable(entry) else disable(entry)
                if (id == catalogue?.parent) reconcileCatalogue()
            }
        }
    }

    private suspend fun reconcileCatalogue() {
        val catalog = catalogue ?: return
        val active = parentActive()
        for ((id, fetch) in catalog.fetch) {
            val entry = entries[id] ?: continue
            if (row(entry).status == PluginStatus.UNAVAILABLE) continue
            if (!active) {
                entry.download?.cancel()
                entry.download = null
                if (id in importedIds.value) {
                    val selected = choices.enabled(id)
                    stop(entry)
                    if (row(entry).status == PluginStatus.DISABLED)
                        set(entry, PluginStatus.PAUSED, message = "Inactive · Marketplace is off", rememberedOn = selected)
                } else set(entry, PluginStatus.DISABLED)
            } else if (id in importedIds.value) {
                if (choices.enabled(id)) enable(entry)
                else if (row(entry).status == PluginStatus.PAUSED) set(entry, PluginStatus.DISABLED)
            } else if (!choices.removed(id)) {
                startDownload(entry, fetch)
            }
        }
    }

    fun download(id: String) = owner.launch(worker) {
        lock.withLock {
            val entry = entries[id] ?: return@withLock
            val fetch = catalogue?.fetch?.get(id) ?: return@withLock
            if (parentActive() && id !in importedIds.value && row(entry).status != PluginStatus.UNAVAILABLE)
                startDownload(entry, fetch)
        }
    }

    private fun startDownload(entry: Entry, fetch: suspend () -> Unit) {
        if (entry.download?.isActive == true) return
        set(entry, PluginStatus.FETCHING)
        entry.download = owner.launch(worker) {
            try {
                fetch()
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (parentActive()) install(entry)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    set(entry, PluginStatus.FAILED, message = "Download failed. Check your connection and retry.")
                }
            }
        }
    }

    fun remove(id: String) = owner.launch(worker) {
        lock.withLock {
            val entry = entries[id] ?: return@withLock
            if (id !in importedIds.value) return@withLock
            try {
                choices.remove(id)
            } catch (_: Exception) {
                state.update { rows -> rows.map { if (it.descriptor.id == id) it.copy(message = "Could not remove. Try again.") else it } }
                return@withLock
            }
            stop(entry)
            importedIds.update { it - id }
            if (id == catalogue?.parent) reconcileCatalogue()
        }
    }

    private fun id(entry: Entry) = entry.registration.descriptor.id
    private fun row(entry: Entry) = state.value.first { it.descriptor.id == id(entry) }
    private fun set(entry: Entry, status: PluginStatus, card: PluginContribution? = null, message: String? = null, rememberedOn: Boolean = false) {
        state.update { list -> list.map {
            if (it.descriptor.id == id(entry)) PluginRow(it.descriptor, status, card, message, rememberedOn) else it
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
        stop(entry)
    }

    private suspend fun stop(entry: Entry) {
        val job = entry.lifetime
        job?.cancel()
        val stopped = job == null || withTimeoutOrNull(timeoutMs) { job.join(); true } == true
        when {
            !stopped -> set(entry, PluginStatus.FAILED, message = "Still stopping. Try again shortly.")
            entry.cleanupFailed -> set(entry, PluginStatus.UNAVAILABLE, message = "Cleanup failed. Reopen the app to retry.")
            else -> set(entry, PluginStatus.DISABLED)
        }
    }
}
