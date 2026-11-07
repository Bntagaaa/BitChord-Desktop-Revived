package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.node.Image as MarkdownImageNode
import org.commonmark.node.Link as MarkdownLinkNode
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import java.net.URI

/**
 * Github release bodies use Markdown/GFM just like README.md. Render their
 * actual headings, links, lists, code, tables and images in Compose rather
 * than displaying the raw Markdown text or executing remote HTML/JS.
 */
internal object DesktopReleaseNotesMarkdown {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()
    private val rawBase = URI.create("https://raw.githubusercontent.com/Bntagaaa/BitChord-Desktop-Revived/1.9.1/")
    private val pageBase = URI.create("https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/1.9.1/")

    fun parse(markdown: String): Node = parser.parse(markdown.take(32_000))

    fun safeLinkUrl(destination: String): String? = safeUrl(destination, image = false)

    fun safeImageUrl(destination: String): String? = safeUrl(destination, image = true)

    private fun safeUrl(destination: String, image: Boolean): String? = runCatching {
        val value = destination.trim()
        if (value.isEmpty() || value.length > 2048 || value.any { it.isISOControl() } ||
            value.startsWith("//") || value.startsWith("/") || value.split('/').any { it == ".." }) return null
        val given = URI(value)
        val resolved = if (given.isAbsolute) given else (if (image) rawBase else pageBase).resolve(given)
        if (!resolved.scheme.equals("https", true) || resolved.host.isNullOrBlank() ||
            resolved.userInfo != null || resolved.port != -1) return null
        val host = resolved.host.lowercase()
        if (image && host !in setOf("github.com", "raw.githubusercontent.com", "img.shields.io", "i.imgur.com", "cdn.jsdelivr.net") &&
            !host.endsWith(".githubusercontent.com")) return null
        if (!image) return resolved.normalize().toASCIIString()

        // GitHub's blob pages are HTML, not images. Prefer raw content.
        val path = resolved.rawPath.orEmpty()
        if (host == "github.com" && path.matches(Regex("^/[^/]+/[^/]+/blob/[^/]+/.+"))) {
            val rawPath = path.replaceFirst("/blob/", "/")
            return "https://raw.githubusercontent.com" + rawPath
        }
        resolved.normalize().toASCIIString()
    }.getOrNull()
}

private fun Node.children(): List<Node> = buildList {
    var current = firstChild
    while (current != null) {
        add(current)
        current = current.next
    }
}

@Composable
internal fun DesktopReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val ast = remember(markdown) { DesktopReleaseNotesMarkdown.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ast.children().forEach { ReleaseMarkdownBlock(it) }
    }
}

@Composable
private fun ReleaseMarkdownBlock(node: Node) {
    when (node) {
        is Heading -> ReleaseMarkdownInline(
            node,
            when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(fontWeight = FontWeight.SemiBold),
        )
        is Paragraph -> {
            val images = markdownImages(node)
            if (hasRenderableText(node)) ReleaseMarkdownInline(node, MaterialTheme.typography.bodySmall)
            images.take(16).forEach { (url, description) -> ReleaseMarkdownImage(url, description) }
        }
        is BulletList, is OrderedList -> {
            val numbered = node is OrderedList
            val start = (node as? OrderedList)?.startNumber ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                node.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (numbered) "${start + index}." else "•", style = MaterialTheme.typography.bodySmall,
                            color = DesktopAccent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            item.children().forEach { ReleaseMarkdownBlock(it) }
                        }
                    }
                }
            }
        }
        is BlockQuote -> Column(
            Modifier.fillMaxWidth().background(DesktopCardInsetFill, RoundedCornerShape(9.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { node.children().forEach { ReleaseMarkdownBlock(it) } }
        is FencedCodeBlock -> ReleaseMarkdownCode(node.literal)
        is IndentedCodeBlock -> ReleaseMarkdownCode(node.literal)
        is ThematicBreak -> HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
        is TableBlock -> ReleaseMarkdownTable(node)
        is HtmlBlock -> {
            val document = remember(node.literal) { Jsoup.parseBodyFragment(node.literal) }
            val text = document.body().text()
            if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = DesktopForeground)
            document.select("img[src]").take(16).forEach { image ->
                ReleaseMarkdownImage(image.attr("src"), image.attr("alt"))
            }
        }
        else -> node.children().forEach { ReleaseMarkdownBlock(it) }
    }
}

private fun markdownImages(node: Node): List<Pair<String, String>> = buildList {
    fun walk(current: Node) {
        when (current) {
            is MarkdownImageNode -> add(current.destination to current.title.ifBlank { imageAlt(current) })
            is HtmlInline -> Jsoup.parseBodyFragment(current.literal).select("img[src]").forEach {
                add(it.attr("src") to it.attr("alt"))
            }
            else -> current.children().forEach(::walk)
        }
    }
    walk(node)
}

private fun imageAlt(image: MarkdownImageNode): String = image.children()
    .filterIsInstance<org.commonmark.node.Text>().joinToString("") { it.literal }

private fun hasRenderableText(node: Node): Boolean {
    fun text(current: Node): Boolean = when (current) {
        is MarkdownImageNode -> false
        is org.commonmark.node.Text -> current.literal.isNotBlank()
        is Code -> current.literal.isNotBlank()
        else -> current.children().any(::text)
    }
    return text(node)
}

@Composable
private fun ReleaseMarkdownInline(node: Node, style: TextStyle) {
    val accent = DesktopAccent
    val foreground = DesktopForeground
    val spans = remember(node, accent, foreground) {
        buildAnnotatedString { appendMarkdown(node, accent) }
    }
    ClickableText(
        text = spans,
        style = style.copy(color = foreground),
        onClick = { offset ->
            spans.getStringAnnotations(tag = "release-link", start = offset, end = offset)
                .firstOrNull()?.item?.let(DesktopExternalLinks::open)
        },
    )
}

private fun AnnotatedString.Builder.appendMarkdown(node: Node, accent: Color) {
    when (node) {
        is org.commonmark.node.Text -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
        is SoftLineBreak -> append(" ")
        is HardLineBreak -> append("\n")
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.children().forEach { appendMarkdown(it, accent) }
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
            node.children().forEach { appendMarkdown(it, accent) }
        }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            node.children().forEach { appendMarkdown(it, accent) }
        }
        is MarkdownLinkNode -> {
            val url = DesktopReleaseNotesMarkdown.safeLinkUrl(node.destination)
            if (url != null) {
                pushStringAnnotation(tag = "release-link", annotation = url)
                withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                    node.children().forEach { appendMarkdown(it, accent) }
                }
                pop()
            } else node.children().forEach { appendMarkdown(it, accent) }
        }
        is MarkdownImageNode -> Unit // Drawn as a real image below the paragraph.
        is HtmlInline -> {
            // Raw HTML is never passed to a WebView or executed.
            if (node.literal.startsWith("<br", true)) append("\n")
            else if (!node.literal.contains("<img", true)) append(Jsoup.parseBodyFragment(node.literal).text())
        }
        else -> node.children().forEach { appendMarkdown(it, accent) }
    }
}

@Composable
private fun ReleaseMarkdownImage(destination: String, description: String) {
    val url = remember(destination) { DesktopReleaseNotesMarkdown.safeImageUrl(destination) }
    if (url == null) {
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        return
    }
    val shape = RoundedCornerShape(10.dp)
    SubcomposeAsyncImage(
        model = url,
        contentDescription = description.ifBlank { "Release screenshot" },
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(shape)
            .background(DesktopCardInsetFill).clickable { DesktopExternalLinks.open(url) },
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = DesktopAccent)
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (description.isBlank()) "Image unavailable" else description, style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary)
            }
        },
    )
}

@Composable
private fun ReleaseMarkdownCode(value: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DesktopCardInsetFill).padding(10.dp)
        .horizontalScroll(rememberScrollState())) {
        Text(value.trimEnd(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = DesktopForeground, softWrap = false)
    }
}

@Composable
private fun ReleaseMarkdownTable(table: TableBlock) {
    val rows = table.children().flatMap { part -> part.children() }.filterIsInstance<TableRow>()
    val cells = rows.maxOfOrNull { it.children().size }?.coerceIn(1, 8) ?: return
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.background(if (index == 0) DesktopCardInsetFill else Color.Transparent)
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.children().filterIsInstance<TableCell>().forEach { cell ->
                    Box(Modifier.width(128.dp).padding(horizontal = 5.dp)) {
                        ReleaseMarkdownInline(cell,
                            MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal
                            ))
                    }
                }
                repeat((cells - row.children().size).coerceAtLeast(0)) { Spacer(Modifier.width(128.dp)) }
            }
            HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
        }
    }
}
