package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** An inline piece of a Markdown line. */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

/** One block of a Markdown document. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
}

private val urlPattern = Regex("""https?://\S+""")

/**
 * A small Markdown subset: `#`..`######` headings, `- ` / `* ` bullets, paragraphs (one per non-blank line),
 * `**bold**`, `*italic*` and `[text](http(s)://url)`. Anything else, or anything malformed, stays literal text.
 * Blank lines are skipped; CRLF and LF both work.
 */
fun parseMarkdown(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    for (raw in text.split('\n')) {
        val line = raw.trimEnd('\r')
        if (line.isBlank()) continue
        val trimmed = line.trimStart()
        val hashes = trimmed.takeWhile { it == '#' }.length
        when {
            hashes in 1..6 && trimmed.length > hashes && trimmed[hashes] == ' ' && trimmed.substring(hashes).isNotBlank() ->
                out += MdBlock.Heading(hashes, parseSpans(trimmed.substring(hashes + 1).trim()))
            (trimmed.startsWith("- ") || trimmed.startsWith("* ")) && trimmed.substring(2).isNotBlank() ->
                out += MdBlock.Bullet(parseSpans(trimmed.substring(2).trim()))
            else -> out += MdBlock.Paragraph(parseSpans(line.trim()))
        }
    }
    return out
}

private fun parseSpans(s: String): List<MdSpan> {
    val spans = ArrayList<MdSpan>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            spans += MdSpan.Plain(plain.toString())
            plain.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '*' && s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2 && s.substring(i + 2, end).isNotBlank()) {
                flush()
                spans += MdSpan.Bold(s.substring(i + 2, end))
                i = end + 2
                continue
            }
        } else if (c == '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i + 1 && s.substring(i + 1, end).isNotBlank() && s[i + 1] != '*') {
                flush()
                spans += MdSpan.Italic(s.substring(i + 1, end))
                i = end + 1
                continue
            }
        } else if (c == '[') {
            val mid = s.indexOf("](", i + 1)
            if (mid > i + 1) {
                val close = s.indexOf(')', mid + 2)
                if (close > mid + 2) {
                    val label = s.substring(i + 1, mid)
                    val url = s.substring(mid + 2, close)
                    if (label.isNotBlank() && !label.contains('[') && urlPattern.matches(url)) {
                        flush()
                        spans += MdSpan.Link(label, url)
                        i = close + 1
                        continue
                    }
                }
            }
        }
        plain.append(c)
        i++
    }
    flush()
    return spans
}

private fun annotated(spans: List<MdSpan>, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(markdownItalicStyle) { append(span.text) }
            is MdSpan.Link -> withLink(
                LinkAnnotation.Url(
                    span.url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(span.text) }
        }
    }
}

/** Renders [text] lazily (long documents only compose what is on screen). Fills the space it is given. */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(blocks) { _, block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    annotated(block.spans, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•")
                    Text(annotated(block.spans, linkColor), modifier = Modifier.weight(1f))
                }
                is MdBlock.Paragraph -> Text(annotated(block.spans, linkColor), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
