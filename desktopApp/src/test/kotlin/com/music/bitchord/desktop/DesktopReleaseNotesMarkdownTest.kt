package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis

class DesktopReleaseNotesMarkdownTest {
    private fun allNodes(root: Node): List<Node> = buildList {
        fun visit(n: Node) {
            add(n)
            var child = n.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(root)
    }

    @Test fun githubReleaseBodyHasRealMarkdownStructure() {
        val source = """
            # What's New
            **New features** with [documentation](https://github.com/Bntagaaa/BitChord-Desktop-Revived)
            - A new player
            - Better updates

            | Platform | Status |
            | --- | --- |
            | Windows | Ready |

            ![Update preview](https://github.com/user-attachments/assets/01234567-89ab-cdef-0123-456789abcdef)

            ```sh
            echo update
            ```
        """.trimIndent()
        val nodes = allNodes(DesktopReleaseNotesMarkdown.parse(source))
        assertTrue(nodes.any { it is Heading })
        assertTrue(nodes.any { it is StrongEmphasis })
        assertTrue(nodes.any { it is Link })
        assertTrue(nodes.any { it is Image })
        assertTrue(nodes.any { it is TableBlock })
        assertTrue(nodes.any { it is FencedCodeBlock })
    }

    @Test fun validGithubImagesLoadOverHttpsIncludingRelativeReadmePaths() {
        assertEquals("https://github.com/user-attachments/assets/0123",
            DesktopReleaseNotesMarkdown.safeImageUrl("https://github.com/user-attachments/assets/0123"))
        assertEquals("https://raw.githubusercontent.com/Bntagaaa/BitChord-Desktop-Revived/1.9.1/docs/img.png",
            DesktopReleaseNotesMarkdown.safeImageUrl("docs/img.png"))
        assertEquals("https://raw.githubusercontent.com/example/repo/main/docs/screen.png",
            DesktopReleaseNotesMarkdown.safeImageUrl("https://github.com/example/repo/blob/main/docs/screen.png"))
        assertNotNull(DesktopReleaseNotesMarkdown.safeImageUrl("https://user-images.githubusercontent.com/abc.png"))
        assertNull(DesktopReleaseNotesMarkdown.safeImageUrl("http://example.com/preview.png"))
        assertNull(DesktopReleaseNotesMarkdown.safeImageUrl("https://127.0.0.1/private"))
        assertNull(DesktopReleaseNotesMarkdown.safeImageUrl("../private.png"))
        assertNull(DesktopReleaseNotesMarkdown.safeImageUrl("//example.com/test.png"))
        assertNull(DesktopReleaseNotesMarkdown.safeImageUrl("file:///etc/passwd"))
    }

    @Test fun linksCannotLaunchScriptsOrLocalPaths() {
        assertNotNull(DesktopReleaseNotesMarkdown.safeLinkUrl("https://github.com/Bntagaaa/BitChord-Desktop-Revived"))
        assertNull(DesktopReleaseNotesMarkdown.safeLinkUrl("javascript:alert(1)"))
        assertNull(DesktopReleaseNotesMarkdown.safeLinkUrl("file:///tmp/private"))
        assertNull(DesktopReleaseNotesMarkdown.safeLinkUrl("https://user:pass@github.com/example"))
        assertNull(DesktopReleaseNotesMarkdown.safeLinkUrl("../../other"))
        assertFalse(DesktopReleaseNotesMarkdown.safeLinkUrl("readme.md").isNullOrBlank())
    }
}
