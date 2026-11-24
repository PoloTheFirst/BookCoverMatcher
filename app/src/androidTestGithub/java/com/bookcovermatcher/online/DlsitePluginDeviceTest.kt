// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.content.Context
import android.content.ContextWrapper
import android.util.LruCache
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.core.online.*
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.plugins.PluginSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DlsitePluginDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "plugin-fixture-" + UUID.randomUUID()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
    }

    @Test fun explicitLegacySettingAndPluginPrecedence() {
        org.junit.Assume.assumeNotNull(ProviderCatalog.byId("dl"))
        val prefs = OnlineSearchPrefs(context)
        val directory = File(base.cacheDir, prefix)
        val settings = PluginSettings(directory) { "dl" in prefs.explicitProviderIds.orEmpty() }
        val id = "bcm.dlsite"
        try {
            assertFalse(settings.enabled(id))
            prefs.selectedProviders = setOf("ol")
            assertFalse(settings.enabled(id))
            prefs.selectedProviders = setOf("dl")
            assertTrue(settings.enabled(id))
            assertFalse(settings.imported(id))
            settings.save(id, false)
            assertTrue(settings.imported(id))
            assertFalse(settings.enabled(id))
        } finally {
            directory.deleteRecursively()
            base.deleteSharedPreferences(prefix + "bookcover_online")
        }
    }

    @Test fun switchingPreservesAWorkbookAndSavedDlsiteBookmark() = runBlocking {
        org.junit.Assume.assumeNotNull(ProviderCatalog.byId("dl"))
        val root = File(base.cacheDir, prefix)
        val workbooks = com.bookcovermatcher.data.WorkbookLibrary(File(root, "workbooks"))
        val bookmarks = com.bookcovermatcher.core.wishlist.WishlistRepository(File(root, "bookmarks"))
        val owner = CoroutineScope(SupervisorJob())
        try {
            val bytes = syntheticWorkbook()
            workbooks.import(bytes.inputStream(), "catalogue.xlsx")
            val entry = workbooks.list().single()
            val url = "https://www.dlsite.com/books/work/=/product_id/BJ00000001.html"
            val item = com.bookcovermatcher.core.wishlist.ItemDraft(
                com.bookcovermatcher.core.wishlist.ItemKind.ONLINE_RESULT, "Synthetic listing",
                url = url, provider = "DLsite",
            )
            bookmarks.bookmark(item)
            val manager = com.bookcovermatcher.plugins.PluginManager(
                com.bookcovermatcher.plugins.editionPlugins, PluginSettings(File(root, "choices")) { false }, owner)
            manager.importDescriptor("bcm.dlsite")
            for (on in listOf(true, false, true)) {
                manager.toggle("bcm.dlsite", on)!!.join()
                assertEquals(on, manager.rows.value.single { it.descriptor.id == "bcm.dlsite" }.contribution != null)
                assertEquals(entry.id, workbooks.list().single().id)
                assertArrayEquals(bytes, workbooks.fileOf(entry).readBytes())
                assertEquals(url, bookmarks.library().items.single().url)
            }
        } finally {
            owner.cancel()
            root.deleteRecursively()
        }
    }

    private class FixtureSource(
        override val name: String, val id: String, val thumbnail: String? = null,
        val beforeReply: () -> Unit = {},
    ) : ProductSearchDataSource {
        val calls = AtomicInteger()
        override fun search(query: String, limit: Int): ProductSearchOutcome {
            calls.incrementAndGet()
            beforeReply()
            val item = ProductSearchResult(id, "$name $query", emptyList(), publishedYear = null,
                publishers = emptyList(), pageCount = null, languages = emptyList(), coverUrl = thumbnail,
                detailUrl = "https://example.org/$id", source = name, providerId = id)
            return ProductSearchOutcome.Found(query, listOf(item), 1)
        }
    }

    @Test fun libraryGroupHonorsSelectionsAndGeneralBookSwitch() = runBlocking {
        val library = com.bookcovermatcher.plugins.editionGroups.firstOrNull { it.index == 0 }
        org.junit.Assume.assumeNotNull(library)
        val prefs = OnlineSearchPrefs(context)
        val store = context.getSharedPreferences("bookcover_online", Context.MODE_PRIVATE)
        store.edit().putStringSet("online_providers", setOf("gb", "dl")).commit()
        prefs.enabled = true
        val book = FixtureSource("Open Library", "ol")
        val google = FixtureSource("Google Books", "gb")
        val group = com.bookcovermatcher.plugins.ActivePlatforms(library!!, listOf(book, google))
        var groups = emptyList<com.bookcovermatcher.plugins.ActivePlatforms>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = withContext(Dispatchers.Main) {
            OnlineSearchController(context, owner, prefs, activeGroups = { groups })
        }
        suspend fun search(query: String, count: Int) {
            withContext(Dispatchers.Main) { controller.search(query) }
            withTimeout(5000) {
                controller.ui.first { (it.catalogue as? CatalogueUi.Found)?.let { found ->
                    found.query == query && found.items.size == count
                } == true }
            }
        }
        try {
            withContext(Dispatchers.Main) {
                controller.search("No imported library")
                groups = listOf(group)
                controller.refreshCataloguePlugins()
            }
            assertEquals(0, book.calls.get() + google.calls.get())
            search("First query", 1)
            assertEquals(0, book.calls.get())
            assertEquals(1, google.calls.get())
            withContext(Dispatchers.Main) {
                controller.toggleProvider("ol")
                controller.setSecondarySources(false)
                controller.search("General books off")
            }
            assertEquals(setOf("ol", "gb", "dl"), prefs.explicitProviderIds)
            assertEquals(1, book.calls.get() + google.calls.get())
            withContext(Dispatchers.Main) { controller.setSecondarySources(true) }
            search("Both libraries", 2)
            assertEquals(1, book.calls.get())
            assertEquals(2, google.calls.get())
        } finally {
            owner.cancel()
            base.deleteSharedPreferences(prefix + "bookcover_online")
        }
    }

    @Test fun switchingRevokesQueriesAndCoversAndPreservesOtherResults() = runBlocking {
        org.junit.Assume.assumeTrue(ProviderCatalog.byId("dl") != null && ProviderCatalog.byId("ol") != null)
        val prefs = OnlineSearchPrefs(context)
        prefs.enabled = true
        prefs.selectedProviders = setOf("ol", "dl")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val book = FixtureSource("Open Library", "ol")
        val release = CountDownLatch(1)
        val coverStarted = CountDownLatch(1)
        val cache = object : LruCache<String, ByteArray>(4) {
            override fun create(key: String): ByteArray {
                coverStarted.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return byteArrayOf(41)
            }
        }
        val queryStarted = CountDownLatch(1)
        val queryReleased = CountDownLatch(1)
        val slow = FixtureSource("DLsite", "dl") {
            queryStarted.countDown()
            check(queryReleased.await(10, TimeUnit.SECONDS))
        }
        val original = FixtureSource("DLsite", "dl", "https://example.org/fixture")
        val current = java.util.concurrent.atomic.AtomicReference<ProductSearchDataSource?>()
        val ocr = object : OcrTextSource {
            override fun availability() = OcrAvailability.Ready("fixture", "1")
            override fun recognise(imageBytes: ByteArray, ocrLanguage: String?, targetLanguage: String?,
                                   timeoutSeconds: Int) =
                OcrText("Example Catalogue", listOf(1f), "en", null, 2, 1, "1")
        }
        val library = com.bookcovermatcher.plugins.editionGroups.single { it.index == 0 }
        val dlsite = com.bookcovermatcher.plugins.editionGroups.single { it.index == 1 }
        val books = com.bookcovermatcher.plugins.ActivePlatforms(library, listOf(book))
        val controller = withContext(Dispatchers.Main) {
            OnlineSearchController(context, owner, prefs, bridge = ocr, covers = CoverImageLoader(cache = cache),
                activeGroups = { listOfNotNull(books, current.get()?.let { com.bookcovermatcher.plugins.ActivePlatforms(dlsite, listOf(it)) }) })
        }
        suspend fun select(source: ProductSearchDataSource?) = withContext(Dispatchers.Main) {
            current.set(source)
            controller.refreshCataloguePlugins()
        }
        suspend fun search(query: String) = withContext(Dispatchers.Main) { controller.search(query) }
        suspend fun awaitFound(count: Int) = withTimeout(5000) {
            controller.ui.first { (it.catalogue as? CatalogueUi.Found)?.items?.size == count }
        }
        try {
            search("Offline plugin")
            awaitFound(1)
            assertEquals(1, book.calls.get())
            select(original)
            assertEquals(0, original.calls.get())
            withContext(Dispatchers.Main) { controller.onCapture(byteArrayOf(1, 2, 3)) }
            val mixed = awaitFound(2).catalogue as CatalogueUi.Found
            assertEquals(1, original.calls.get())
            search(mixed.query)
            assertEquals(1, original.calls.get())
            assertTrue(coverStarted.await(5, TimeUnit.SECONDS))
            select(null)
            assertEquals("Open Library", (awaitFound(1).catalogue as CatalogueUi.Found).items.single().source)
            select(slow)
            search(mixed.query)
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
            select(null)
            val replacement = FixtureSource("DLsite", "dl")
            select(replacement)
            search("New activation")
            awaitFound(2)
            release.countDown()
            queryReleased.countDown()
            // Drain the old cover and query jobs before inspecting the new result with the same provider ID.
            withTimeout(5000) { owner.coroutineContext.job.children.toList().joinAll() }
            val latest = controller.ui.value.catalogue as CatalogueUi.Found
            assertEquals("New activation", latest.query)
            assertNull(latest.items.single { it.source == "DLsite" }.coverJpeg)
            withContext(Dispatchers.Main) { controller.setEnabled(false) }
            search("Master switch off")
            assertEquals(1, replacement.calls.get())
        } finally {
            release.countDown()
            queryReleased.countDown()
            owner.cancel()
            base.deleteSharedPreferences(prefix + "bookcover_online")
        }
    }
}
