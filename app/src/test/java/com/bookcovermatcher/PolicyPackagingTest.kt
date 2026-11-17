// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher

import com.bookcovermatcher.core.markdown.Markdown
import com.bookcovermatcher.core.markdown.MdBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PolicyPackagingTest {
    @Test fun packagedPoliciesMatchCanonicalSourcesAndRenderAsDocuments() {
        for (name in listOf("PRIVACY_POLICY.md", "TERMS_AND_CONDITIONS.md")) {
            val source = File("../$name").readBytes()
            val packaged = File("src/main/assets/licenses/$name").readBytes()
            assertArrayEquals("Packaged policy must match approved source: $name", source, packaged)
            val blocks = Markdown.parse(source.toString(Charsets.UTF_8))
            assertTrue("Policy must have readable content", blocks.size > 15)
            assertTrue("Policy must start with its own title", blocks.first() is MdBlock.Heading)
            assertTrue("Offline document must use absolute web links", !source.toString(Charsets.UTF_8).contains("](/apps/"))
        }
    }
}
