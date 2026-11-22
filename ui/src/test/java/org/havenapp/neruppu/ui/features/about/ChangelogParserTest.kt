/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.havenapp.neruppu.ui.features.about

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/*
 * Testing strategy (parseChangelog):
 *  - bullets: "•", "-", "*", none
 *  - blank lines and surrounding whitespace
 *  - empty file -> fallback title
 *  - the real bundled changelogs all parse and keep the 500-char F-Droid limit
 */
class ChangelogParserTest {

    @Test
    fun `First line is the title and bullet markers are stripped`() {
        val entry = parseChangelog(10, "v26.47.1 - Fork\n• One\n- Two\n* Three\nFour\n")
        assertEquals("v26.47.1 - Fork", entry.title)
        assertEquals(listOf("One", "Two", "Three", "Four"), entry.lines)
        assertEquals(10L, entry.versionCode)
    }

    @Test
    fun `Blank lines and whitespace are ignored`() {
        val entry = parseChangelog(9, "\n  v1 \n\n   •  Item  \n\n")
        assertEquals("v1", entry.title)
        assertEquals(listOf("Item"), entry.lines)
    }

    @Test
    fun `Empty file falls back to the build number`() {
        val entry = parseChangelog(7, "")
        assertEquals("Build 7", entry.title)
        assertEquals(emptyList<String>(), entry.lines)
    }

    @Test
    fun `Bundled changelogs parse and fit the F-Droid limit`() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fastlane/metadata/org.havenapp.neruppu/en-US/changelogs") }
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.matches(Regex("""\d+\.txt""")) }!!
        assert(files.isNotEmpty())
        files.forEach { f ->
            val text = f.readText()
            assert(text.length <= 500) { "${f.name} is ${text.length} chars; F-Droid allows 500" }
            val entry = parseChangelog(f.nameWithoutExtension.toLong(), text)
            assert(entry.title.isNotBlank() && entry.lines.isNotEmpty()) { "${f.name} has no notes" }
        }
    }
}
