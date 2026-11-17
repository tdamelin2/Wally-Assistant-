package dev.tim.assistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownViewTest {
    private fun plain(s: String) = listOf<MdSpan>(MdSpan.Plain(s))

    @Test
    fun headings_bullets_and_paragraphs() {
        val blocks = parseMarkdown("# Title\n## Sub\n- one\n* two\n\nHello world")
        assertEquals(
            listOf(
                MdBlock.Heading(1, plain("Title")),
                MdBlock.Heading(2, plain("Sub")),
                MdBlock.Bullet(plain("one")),
                MdBlock.Bullet(plain("two")),
                MdBlock.Paragraph(plain("Hello world")),
            ),
            blocks,
        )
    }

    @Test
    fun crlf_works_like_lf() {
        assertEquals(parseMarkdown("# A\n- b\nc"), parseMarkdown("# A\r\n- b\r\nc"))
    }

    @Test
    fun bold_italic_and_link_spans() {
        val b = parseMarkdown("a **b** c *d* [e](https://x.org/p)").single() as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("a "), MdSpan.Bold("b"), MdSpan.Plain(" c "), MdSpan.Italic("d"),
                MdSpan.Plain(" "), MdSpan.Link("e", "https://x.org/p"),
            ),
            b.spans,
        )
    }

    @Test
    fun malformed_or_unsupported_stays_literal() {
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("**open"))), parseMarkdown("**open"))
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("[x](javascript:alert(1))"))), parseMarkdown("[x](javascript:alert(1))"))
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("[x](nope)"))), parseMarkdown("[x](nope)"))
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("#nospace"))), parseMarkdown("#nospace"))
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("####### seven"))), parseMarkdown("####### seven"))
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(plain("-"))), parseMarkdown("- "))
    }

    @Test
    fun hebrew_text_is_kept_as_is() {
        val text = "# שלום\n- פריט **מודגש**"
        val blocks = parseMarkdown(text)
        assertEquals(MdBlock.Heading(1, plain("שלום")), blocks[0])
        assertEquals(MdBlock.Bullet(listOf(MdSpan.Plain("פריט "), MdSpan.Bold("מודגש"))), blocks[1])
    }

    @Test
    fun empty_and_blank_text_give_no_blocks() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("\n \n").isEmpty())
    }

    @Test
    fun a_maximum_size_body_parses() {
        val body = "line **x** [a](https://a.b)\n".repeat(7_000).take(200_000)
        assertTrue(parseMarkdown(body).size >= 6_000)
    }
}
