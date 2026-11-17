package dev.tim.assistant.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyStreamTest {
    @Test
    fun the_reply_grows_as_the_text_arrives() {
        assertEquals("", ReplyStream.extract("""{"kind":"answer","rep"""))
        assertEquals("", ReplyStream.extract("""{"kind":"answer","reply":"""))
        assertEquals("Hel", ReplyStream.extract("""{"kind":"answer","reply":"Hel"""))
        assertEquals("Hello", ReplyStream.extract("""{"kind":"answer","reply":"Hello"}"""))
    }

    @Test
    fun escapes_are_decoded_and_a_cut_off_escape_waits() {
        assertEquals("a\nb \"q\" \\", ReplyStream.extract("""{"kind":"answer","reply":"a\nb \"q\" \\"}"""))
        assertEquals("a", ReplyStream.extract("""{"kind":"answer","reply":"a\"""))
        assertEquals("a", ReplyStream.extract("""{"kind":"answer","reply":"a\u00"""))
        assertEquals("aé", ReplyStream.extract("""{"kind":"answer","reply":"aé"""))
    }

    @Test
    fun a_split_surrogate_pair_is_shown_only_when_whole() {
        assertEquals("x", ReplyStream.extract("""{"kind":"answer","reply":"x\ud83d"""))
        assertEquals("x", ReplyStream.extract("""{"kind":"answer","reply":"x\ud83d\ude"""))
        assertEquals("x😀", ReplyStream.extract("""{"kind":"answer","reply":"x😀"""))
    }

    @Test
    fun no_reply_field_gives_empty() {
        assertEquals("", ReplyStream.extract("""{"kind":"answer"}"""))
        assertEquals("", ReplyStream.extract(""))
        assertEquals("", ReplyStream.extract("not json"))
    }

    @Test
    fun a_reply_before_the_kind_waits_for_the_kind() {
        assertEquals("", ReplyStream.extract("""{"reply":"Hi there","ki"""))
        assertEquals("", ReplyStream.extract("""{"reply":"Hi there","kind":"ans"""))
        assertEquals("Hi there", ReplyStream.extract("""{"reply":"Hi there","kind":"answer"}"""))
    }

    @Test
    fun whitespace_around_colons_and_commas_is_fine() {
        assertEquals("ok", ReplyStream.extract("{ \"kind\" : \"answer\" ,\n \"reply\" :  \"ok\" }"))
    }

    @Test
    fun a_nested_kind_answer_inside_args_is_ignored() {
        assertEquals("", ReplyStream.extract("""{"kind":"action","args":{"kind":"answer","reply":"no"},"reply":"""))
        assertEquals("", ReplyStream.extract("""{"args":{"kind":"answer"},"reply":"nope"}"""))
        assertEquals("", ReplyStream.extract("""{"list":[{"kind":"answer"}],"reply":"nope"}"""))
    }

    @Test
    fun a_look_alike_inside_a_quoted_string_never_matches() {
        assertEquals("", ReplyStream.extract("""{"note":"\"kind\":\"answer\"","reply":"nope"}"""))
        assertEquals("real", ReplyStream.extract("""{"note":"\"kind\":\"action\"","kind":"answer","reply":"real"}"""))
    }

    @Test
    fun an_action_kind_gives_empty_even_with_a_reply() {
        assertEquals("", ReplyStream.extract("""{"kind":"action","reply":"Done"}"""))
    }

    @Test
    fun a_whole_document_is_extracted_at_every_cut() {
        val doc = """{"kind":"answer","args":{"x":[1,2,{"kind":"answer"}]},"reply":"Spent 35 ₪ on lunch.","extra":null}"""
        var last = ""
        for (n in 1..doc.length) {
            val got = ReplyStream.extract(doc.substring(0, n))
            assert(got.length >= last.length && got.startsWith(last)) { "shrank at cut $n: '$last' -> '$got'" }
            last = got
        }
        assertEquals("Spent 35 ₪ on lunch.", last)
    }
}
