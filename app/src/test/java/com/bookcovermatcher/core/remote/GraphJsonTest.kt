// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the Graph documents this app reads (Phase 3). */
class GraphJsonTest {

    private val acceptAll: (String) -> Boolean = { true }

    private val childrenResponse = """
        {
          "value": [
            {"id":"F1","name":"Catalogue","folder":{"childCount":2},"parentReference":{"path":"/drive/root:"}},
            {"id":"A1","name":"covers.xlsx","file":{"mimeType":"application/vnd.ms-excel"},
             "size":4096,"eTag":"\"{AAA},3\"","cTag":"\"c:{AAA},2\"",
             "lastModifiedDateTime":"2026-11-08T12:34:56Z","parentReference":{"path":"/drive/root:/Catalogue"}},
            {"id":"A2","name":"notes.txt","file":{},"size":12},
            {"id":"A3","name":"broken"}
          ]
        }
    """.trimIndent()

    @Test fun foldersAndFilesAreSeparatedAndMetadataIsKept() {
        val result = GraphJson.listing(folderId = null, body = childrenResponse, accept = acceptAll)

        val listing = (result as RemoteResult.Ok).value
        assertEquals(1, listing.folders.size)
        assertEquals("Catalogue", listing.folders.single().name)
        assertTrue(listing.folders.single().isFolder)

        assertEquals("every non-folder object is a file", 3, listing.files.size)
        val workbook = listing.files.first { it.id == "A1" }
        assertEquals("covers.xlsx", workbook.name)
        assertEquals(4096L, workbook.sizeBytes)
        assertEquals("\"{AAA},3\"", workbook.eTag)
        assertEquals("\"c:{AAA},2\"", workbook.cTag)
        assertEquals("a real timestamp becomes epoch millis", 1_794_141_296_000L, workbook.lastModified)
        assertEquals("/drive/root:/Catalogue", workbook.path)

        val noMetadata = listing.files.first { it.id == "A3" }
        assertNull("a missing size stays unknown, not zero", noMetadata.sizeBytes)
        assertNull("a missing timestamp stays unknown", noMetadata.lastModified)
    }

    @Test fun aFileWithoutAFileOrFolderFacetIsTreatedAsAFile() {
        // `{"id":"A3","name":"broken"}` has neither facet; it must not be listed as a folder.
        val listing = (GraphJson.listing(null, childrenResponse, acceptAll) as RemoteResult.Ok).value

        assertTrue("A3 is a file", listing.files.any { it.id == "A3" })
        assertEquals(0, listing.folders.count { it.id == "A3" })
    }

    @Test fun theFileFilterDecidesWhatIsKept() {
        val result = GraphJson.listing(null, childrenResponse) { it.endsWith(".xlsx") }

        val listing = (result as RemoteResult.Ok).value
        assertEquals("only the workbook survives", listOf("A1"), listing.files.map { it.id })
        assertEquals("folders are always kept", 1, listing.folders.size)
    }

    @Test fun aNextLinkIsCarriedButAnEmptyOneIsNot() {
        val withNext = """{"value":[],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/root/children?${'$'}skiptoken=x"}"""
        val listing = (GraphJson.listing(null, withNext, acceptAll) as RemoteResult.Ok).value
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/children?${'$'}skiptoken=x", listing.nextPageToken)

        val emptyNext = """{"value":[],"@odata.nextLink":""}"""
        assertNull((GraphJson.listing(null, emptyNext, acceptAll) as RemoteResult.Ok).value.nextPageToken)
    }

    @Test fun aResponseThatIsNotJsonIsAFailureNotAnEmptyList() {
        val result = GraphJson.listing(null, "<html>error</html>", acceptAll)

        assertEquals(RemoteFailure.Unstable, (result as RemoteResult.Err).failure)
    }

    @Test fun anItemDocumentBecomesAPresentState() {
        val body = """{"id":"A1","name":"covers.xlsx","file":{},"size":10,"eTag":"E1",
            "lastModifiedDateTime":"2026-11-08T00:00:00Z"}""".trimIndent()

        val item = (GraphJson.item(body) as RemoteResult.Ok).value
        assertEquals("A1", item.id)
        assertEquals("E1", item.eTag)
    }

    @Test fun anErrorDocumentInAProbeIsReportedAsNotFound() {
        val body = """{"error":{"code":"itemNotFound","message":"The resource could not be found."}}"""

        assertEquals(RemoteFailure.NotFound, (GraphJson.item(body) as RemoteResult.Err).failure)
    }

    @Test fun anUnparsableTimestampStaysUnknown() {
        val body = """{"id":"A1","name":"x.xlsx","file":{},"lastModifiedDateTime":"yesterday"}"""

        assertNull((GraphJson.item(body) as RemoteResult.Ok).value.lastModified)
    }
}
