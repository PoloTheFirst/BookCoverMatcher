// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Resolving the technology icon a Shields.io badge carries (v2.6.0 crash-fix 2).
 *
 * The fixtures are the real badge documents the README references — `java`, `kotlin`, `git` (an embedded **SVG**
 * icon) and `bash` (an embedded **PNG** icon) — so the assertions are about the artefacts the app actually
 * downloads, not about an idealised badge.
 *
 * The defect these guard: the badge's background and text drew, and the icon's 14×14 box stayed empty, because the
 * icon is a nested SVG inside `<image href="data:image/svg+xml;base64,…">` and the renderer understands neither
 * SVG 2's `href` nor an SVG payload.
 */
class SvgEmbeddedImagesTest {

    private fun badge(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/author/badge-$name.svg"),
    ) { "the $name badge fixture is missing" }.bufferedReader().use { it.readText() }

    @Test fun theShieldsBadgeCarriesItsIconAsANestedSvgInADataUri() {
        // The source evidence: the icon is present, in a form the renderer cannot read.
        val java = badge("java")
        assertTrue("the icon reference exists", java.contains("data:image/svg+xml;base64,"))
        assertTrue("it uses SVG 2's bare href", Regex("<image[^>]*\\shref=\"").containsMatchIn(java))
        assertFalse("and not the xlink form", java.contains("xlink:href"))
        val payload = java.substringAfter("data:image/svg+xml;base64,").substringBefore('"')
        val icon = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        assertTrue("the payload really is an SVG document", icon.trimStart().startsWith("<svg"))
        assertTrue("and it contains the icon's geometry", icon.contains("<path d=\"M11.915"))
    }

    @Test fun theJavaIconBecomesPositionedGeometryInTheBadgeDocument() {
        val prepared = SvgEmbeddedImages.prepare(badge("java"))
        assertFalse(
            "no unresolvable embedded SVG may remain",
            prepared.contains("data:image/svg+xml;base64,"),
        )
        assertTrue("the icon's own geometry is kept", prepared.contains("M11.915"))
        assertTrue("its colour is inherited from the embedded root", prepared.contains("fill=\"white\""))
        // 14 / 24 = 0.58333…, placed at the <image> box's x=5, y=3.
        assertTrue(
            "the icon is placed and scaled like the <image> would have been: $prepared",
            prepared.contains("<g transform=\"translate(5,3) scale(0.58333)\""),
        )
        assertTrue("the background is untouched", prepared.contains("fill=\"#ed8b00\""))
        assertTrue("the text is untouched", prepared.contains(">Java<"))
    }

    @Test fun everyBadgeInTheReadmeKeepsItsIconAndItsText() {
        for (name in listOf("java", "kotlin", "git")) {
            val prepared = SvgEmbeddedImages.prepare(badge(name))
            assertFalse("$name: the embedded reference is resolved", prepared.contains("data:image/svg+xml"))
            assertTrue("$name: an icon group was inserted", prepared.contains("<g transform=\"translate("))
            assertTrue("$name: the badge keeps its own <text>", prepared.contains("<text"))
        }
    }

    @Test fun aRasterIconIsPointedAtTheXlinkAttributeTheRendererReads() {
        val prepared = SvgEmbeddedImages.prepare(badge("bash"))
        assertTrue("the PNG payload is kept", prepared.contains("data:image/png;base64,"))
        assertTrue("the reference now uses xlink:href", prepared.contains("xlink:href=\"data:image/png;base64,"))
        assertTrue("and the namespace is declared", prepared.contains("xmlns:xlink=\"http://www.w3.org/1999/xlink\""))
        assertFalse("the bare attribute is gone", Regex("<image[^>]*\\shref=").containsMatchIn(prepared))
    }

    @Test fun aDocumentWithNoEmbeddedImageIsReturnedUnchanged() {
        val plain = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"
        assertEquals("identity, so a plain SVG costs one scan", plain, SvgEmbeddedImages.prepare(plain))
        assertFalse(SvgEmbeddedImages.needsPreparation(plain))
    }

    @Test fun textAndBackgroundStillRenderAroundTheIcon() {
        val prepared = SvgEmbeddedImages.prepare(badge("java"))
        // The clip path, both background rects, the gradient wash and the scaled text group must all survive, or the
        // icon would be "fixed" by breaking the badge.
        assertTrue(prepared.contains("<clipPath id=\"r\">"))
        assertTrue(prepared.contains("fill=\"url(#s)\""))
        assertTrue(prepared.contains("<g transform=\"scale(.1)\">"))
        assertTrue(prepared.contains("textLength=\"250\""))
    }

    @Test fun aHostilePayloadCannotSmuggleScriptThroughTheIcon() {
        val script = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">" +
            "<script>alert(1)</script><path d=\"M0 0h24v24H0z\"/></svg>"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray())
        val hostile = "<svg><image x=\"0\" y=\"0\" width=\"14\" height=\"14\" " +
            "href=\"data:image/svg+xml;base64,$encoded\"/></svg>"
        val prepared = SvgEmbeddedImages.prepare(hostile)
        assertTrue("the icon is inlined, so the sanitising path ran", prepared.contains("<g transform="))
        assertFalse("no script element may survive", prepared.contains("<script", ignoreCase = true))
        assertFalse("and no script body may survive", prepared.contains("alert(1)", ignoreCase = true))
        assertTrue("the icon's drawing is kept", prepared.contains("M0 0h24v24H0z"))
    }

    @Test fun anEmbeddedSvgWithNoGeometryIsLeftAloneRatherThanGuessed() {
        // Without a viewBox or a size there is nothing to scale against, so the reference is preserved untouched
        // instead of being inlined at an invented size.
        val noGeometry = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0h10v10H0z\"/></svg>"
        val encoded = Base64.getEncoder().encodeToString(noGeometry.toByteArray())
        val document = "<svg><image x=\"0\" y=\"0\" width=\"14\" height=\"14\" " +
            "href=\"data:image/svg+xml;base64,$encoded\"/></svg>"
        assertEquals(document, SvgEmbeddedImages.prepare(document))
    }

    @Test fun malformedInputCannotThrow() {
        val broken = "<svg><image href=\"data:image/svg+xml;base64,!!!not-base64!!!\"/></svg>"
        assertEquals("an undecodable payload is left as it was", broken, SvgEmbeddedImages.prepare(broken))
        assertTrue(SvgEmbeddedImages.prepare("<svg><image href=").isNotEmpty())
        assertEquals("", SvgEmbeddedImages.prepare(""))
        assertTrue(SvgEmbeddedImages.prepare("<svg/>").isNotEmpty())
    }

    @Test fun anOversizedPayloadIsRefusedInsteadOfDecoded() {
        val huge = "A".repeat(SvgEmbeddedImages.MAX_PAYLOAD_CHARS + 16)
        val document = "<svg><image href=\"data:image/svg+xml;base64,$huge\"/></svg>"
        assertEquals("a payload over the cap is not decoded", document, SvgEmbeddedImages.prepare(document))
    }

    @Test fun anExternalReferenceIsNeverTouched() {
        val external = "<svg><image x=\"0\" y=\"0\" width=\"14\" height=\"14\" " +
            "href=\"https://example.org/icon.svg\"/></svg>"
        assertEquals("the app never fetches an external reference", external, SvgEmbeddedImages.prepare(external))
    }
}
