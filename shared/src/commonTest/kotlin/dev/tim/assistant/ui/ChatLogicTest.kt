package dev.tim.assistant.ui

import dev.tim.assistant.ai.QueuedMessage
import dev.tim.assistant.model.ChatSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ChatLogicTest {
    @Test
    fun the_tail_row_follows_the_phone_text_then_the_dots() {
        assertEquals(ChatTail.None, chatTail(typing = false, streamingText = null))
        assertEquals(ChatTail.Dots, chatTail(typing = true, streamingText = null))
        assertEquals(ChatTail.Dots, chatTail(typing = true, streamingText = ""))
        assertEquals(ChatTail.Dots, chatTail(typing = false, streamingText = ""))
        assertEquals(ChatTail.Streaming, chatTail(typing = true, streamingText = "Hi"))
    }

    @Test
    fun the_slow_hint_never_shows_while_the_phone_writes() {
        assertTrue(slowHintAllowed(slowBrain = true, streamingText = null))
        assertFalse(slowHintAllowed(slowBrain = true, streamingText = ""))
        assertFalse(slowHintAllowed(slowBrain = true, streamingText = "Hi"))
        assertFalse(slowHintAllowed(slowBrain = false, streamingText = null))
    }

    @Test
    fun the_list_follows_a_growing_bubble_only_at_the_bottom() {
        assertTrue(followStreamingBubble(ChatTail.Streaming, lastVisibleIndex = 4, rowCount = 5))
        assertFalse(followStreamingBubble(ChatTail.Streaming, lastVisibleIndex = 2, rowCount = 5))
        assertFalse(followStreamingBubble(ChatTail.Streaming, lastVisibleIndex = null, rowCount = 5))
        assertFalse(followStreamingBubble(ChatTail.Dots, lastVisibleIndex = 4, rowCount = 5))
        assertTrue(isAtListBottom(null, 0))
    }

    @Test
    fun slow_reply_hint_shows_only_after_ten_seconds() {
        assertNull(slowReplyHint(10_000, hpBrain = true))
        assertEquals(SLOW_REPLY_HINT, slowReplyHint(10_001, hpBrain = true))
    }

    @Test
    fun slow_reply_hint_is_never_shown_when_the_xps_answers_or_the_brain_is_unknown() {
        assertNull(slowReplyHint(60_000, hpBrain = false))
    }

    private val utc = TimeZone.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(utc).toEpochMilliseconds()

    @Test
    fun time_labels_follow_the_canvas() {
        val now = at(2026, 11, 11, 15, 0)
        assertEquals("Now", chatTimeLabel(now - 60_000, now, utc))
        assertEquals("09:05", chatTimeLabel(at(2026, 11, 11, 9, 5), now, utc))
        assertEquals("Yesterday", chatTimeLabel(at(2026, 11, 10), now, utc))
        assertEquals("Mon", chatTimeLabel(at(2026, 11, 9), now, utc))
        assertEquals("Nov 4", chatTimeLabel(at(2026, 11, 4), now, utc))
        assertEquals("Oct 24", chatTimeLabel(at(2026, 10, 24), now, utc))
    }

    @Test
    fun the_count_and_undo_countdown() {
        assertEquals("6 of 10", chatCountText(6, 10))
        assertEquals(24, undoSecondsLeft(124_000, 100_001))
        assertEquals(0, undoSecondsLeft(100, 100))
        assertEquals("24 s", undoCountdownText(124_000, 100_500))
        assertEquals("3 min", undoCountdownText(280_000, 100_000))
        assertNull(undoCountdownText(100, 200))
    }

    @Test
    fun enter_sends_shift_enter_makes_a_line_and_other_keys_are_ignored() {
        assertEquals(ComposerKey.Send, composerKeyAction(isEnter = true, shift = false))
        assertEquals(ComposerKey.Newline, composerKeyAction(isEnter = true, shift = true))
        assertEquals(ComposerKey.Ignore, composerKeyAction(isEnter = false, shift = false))
        assertEquals(ComposerKey.Ignore, composerKeyAction(isEnter = false, shift = true))
    }

    @Test
    fun alt_enter_asks_a_model_and_alt_shift_enter_asks_codex_without_changing_the_rest() {
        assertEquals(ComposerKey.AskModel, composerKeyAction(isEnter = true, shift = false, alt = true))
        assertEquals(ComposerKey.AskCodex, composerKeyAction(isEnter = true, shift = true, alt = true))
        assertEquals(ComposerKey.Ignore, composerKeyAction(isEnter = false, shift = false, alt = true))
        assertEquals(ComposerKey.Send, composerKeyAction(isEnter = true, shift = false, alt = false))
    }

    @Test
    fun queued_messages_show_oldest_first_and_never_twice() {
        val a = QueuedMessage("m2", "c", "second", 20)
        val b = QueuedMessage("m1", "c", "first", 10)
        val c = QueuedMessage("m0", "c", "already there", 5)
        assertEquals(listOf("m1", "m2"), queuedForDisplay(listOf(a, b, c), setOf("m0")).map { it.messageId })
    }

    @Test
    fun the_pinned_chat_cannot_be_deleted_and_a_preview_is_one_line() {
        assertFalse(chatCanDelete(ChatSummaryDto("w", "Weekly report", null, 1, true)))
        assertTrue(chatCanDelete(ChatSummaryDto("a", "x", null, 1, false)))
        assertEquals("No messages yet", chatPreview(ChatSummaryDto("a", "x", null, 1, false)))
        assertEquals("one two", chatPreview(ChatSummaryDto("a", "x", "one\ntwo", 1, false)))
    }
}
