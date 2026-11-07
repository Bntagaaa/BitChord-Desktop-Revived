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

    @Test fun onlyWhatsNewAndPrecedingBannerAreShownFromActualReleaseStyle() {
        val body = """
            <img width="1920" height="1080" alt="Desktop" src="https://github.com/user-attachments/assets/cb42bea9-f955-4344-8161-cc044f57ba22" />

            <h2 align="center">What's New</h2>

            - 🎬 **Music Video Integration:** Play videos.
            - ⚡ **Instant Playback:** Start songs faster.

            ---

            <h2 align="center">Installation</h2>

            Download packages from Assets.

            <h2 align="center">Credits</h2>

            Thank you to contributors.
        """.trimIndent()
        val section = DesktopReleaseNotesMarkdown.whatsNew(body)
        assertEquals("https://github.com/user-attachments/assets/cb42bea9-f955-4344-8161-cc044f57ba22", section.preview?.first)
        assertEquals("Desktop", section.preview?.second)
        val contents = section.blocks.flatMap(::allNodes).filterIsInstance<org.commonmark.node.Text>()
            .joinToString(" ") { it.literal }
        assertTrue(contents.contains("Music Video Integration"))
        assertTrue(contents.contains("Instant Playback"))
        assertFalse(contents.contains("Installation"))
        assertFalse(contents.contains("Credits"))
        assertFalse(contents.contains("Download packages"))
    }

    @Test fun markdownHeadingKeepsNestedSubsectionsAndSeparatesOtherReleaseSections() {
        val body = """
            # BitChord 1.9.0

            ![Banner](https://github.com/user-attachments/assets/banner)

            ## What’s New
            ### Playback
            **Instant playback** is now supported.

            ## Installation
            Never show package manager instructions in the popup.
        """.trimIndent()
        val section = DesktopReleaseNotesMarkdown.whatsNew(body)
        assertEquals("https://github.com/user-attachments/assets/banner", section.preview?.first)
        assertTrue(section.blocks.any { it is Heading && it.level == 3 })
        val contents = section.blocks.flatMap(::allNodes).filterIsInstance<org.commonmark.node.Text>()
            .joinToString(" ") { it.literal }
        assertTrue(contents.contains("Instant playback"))
        assertFalse(contents.contains("package manager"))
    }

    @Test fun imageInsideWhatsNewBecomesHeroWithoutRenderingTwice() {
        val section = DesktopReleaseNotesMarkdown.whatsNew("""
            ## What's New

            ![Screenshot](https://github.com/user-attachments/assets/banner)

            - New portable updater
        """.trimIndent())
        assertEquals("https://github.com/user-attachments/assets/banner", section.preview?.first)
        assertTrue(section.blocks.none { it is org.commonmark.node.Paragraph })
        assertTrue(section.blocks.any { it is org.commonmark.node.BulletList })
    }

    @Test fun missingWhatsNewDoesNotLeakInstallOrCreditsIntoPopup() {
        val section = DesktopReleaseNotesMarkdown.whatsNew("""
            # BitChord 1.8.3

            Download packages below.

            ## Installation
            Run the installer.
        """.trimIndent())
        assertNull(section.preview)
        assertTrue(section.blocks.isEmpty())
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
