package dev.tim.assistant.ui.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DraftStoreTest {
    private val map = HashMap<String, String>()
    private val store = DraftStore({ map[it] }, { k, v -> map[k] = v })

    @Test
    fun save_load_clear_round_trip() {
        assertNull(store.load("draft.journal"))
        store.save("draft.journal", "{\"text\":\"hi\"}")
        assertEquals("{\"text\":\"hi\"}", store.load("draft.journal"))
        store.clear("draft.journal")
        assertNull(store.load("draft.journal"))
    }

    @Test
    fun an_empty_value_loads_as_null() {
        map["k"] = ""
        assertNull(store.load("k"))
    }

    @Test
    fun initialWriterText_prefers_the_entry_then_the_draft() {
        assertEquals("entry", initialWriterText("entry", "draft"))
        assertEquals("draft", initialWriterText(null, "draft"))
        assertEquals("", initialWriterText(null, null))
    }

    @Test
    fun journal_draft_round_trips_and_bad_json_is_empty() {
        assertEquals("line one\nline \"two\"", decodeJournalDraft(encodeJournalDraft("line one\nline \"two\"")))
        assertNull(decodeJournalDraft("not json"))
        assertNull(decodeJournalDraft("[1,2]"))
        assertNull(decodeJournalDraft(null))
        assertNull(decodeJournalDraft(""))
    }

    @Test
    fun dream_draft_keeps_mood_lucid_and_tags() {
        val d = DreamDraft("flying", 5, true, "sky, sea")
        assertEquals(d, decodeDreamDraft(encodeDreamDraft(d)))
        assertNull(decodeDreamDraft("{broken"))
        assertNull(decodeDreamDraft(null))
        assertEquals(DreamDraft("x", 3, false, ""), decodeDreamDraft("""{"text":"x","mood":"bad"}"""))
        assertEquals(5, decodeDreamDraft("""{"text":"x","mood":99}""")?.mood)
    }
}