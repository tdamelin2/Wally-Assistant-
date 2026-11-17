package dev.tim.assistant.server.chat.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardsTest {
    @Test
    fun negatedIsTheNegationRegexOnly() {
        for (t in listOf("I didn't spend 35", "never again", "no lunch today", "I skipped breakfast", "nothing special", "cannot go")) assertTrue(Guards.negated(t), t)
        for (t in listOf("lunch set me back 35", "I need to vent", "know the answer", "nobody came")) assertFalse(Guards.negated(t), t)
    }

    @Test
    fun softBlockedIsOnlyANotRealEventWord() {
        for (t in listOf("I need to call the dentist tomorrow", "spent 35 on budget lunch", "lunch will cost 35", "I should stop worrying", "I'll buy milk")) {
            assertTrue(Guards.softBlocked(t), t)
            assertTrue(Guards.blocked(t), t)
        }
        // another reason blocks it for good: negation, a sentence break, two amounts, two verbs, over 200 characters
        for (t in listOf(
            "I don't need to buy milk", "I need to call. Then rest", "I should pay 35 and 40", "I need to add milk and then find the key",
            "I need to " + "x".repeat(200), "spent 35 on lunch", "hello",
        )) assertFalse(Guards.softBlocked(t), t)
    }

    @Test
    fun plainCommandsAreNotBlocked() {
        assertFalse(Guards.blocked("check-in: mood 4 energy 3 sleep 3 stress 2"))
        assertFalse(Guards.blocked("add task buy 2 tickets on 15/03"))
        assertFalse(Guards.blocked("move the algebra exam to 2026-12-01 at 9am"))
        assertFalse(Guards.blocked("I spent 35 on lunch"))
        assertFalse(Guards.blocked("mark the physics homework as done"))
    }

    @Test
    fun nearMissesAreBlocked() {
        for (t in listOf(
            "lunch will cost 35", "I'll spend 35 on lunch", "lunch costs 35 there", "I owe Dana 35",
            "I ate nothing for breakfast", "I almost ate pizza for lunch", "I had to pay 35 for lunch",
        )) assertTrue(Guards.blocked(t), t)
    }

    @Test
    fun negationAndAbsenceWordsBlock() {
        for (t in listOf(
            "I didn't spend 35", "never ate lunch", "I spent 35 without a receipt", "I skipped lunch",
            "cancel the task", "undo that", "no longer needed", "I paid not 35 but 40",
        )) assertTrue(Guards.blocked(t), t)
    }

    @Test
    fun notRealPastEventsBlock() {
        for (t in listOf(
            "I would spend 35", "I might buy lunch", "should I add a task", "going to eat lunch", "gonna spend 35",
            "want to add a task", "need to buy milk", "if I spent 35", "a 35 shekel refund", "Dana paid for me",
            "I lent Dana 35", "budget for lunch is 35",
        )) assertTrue(Guards.blocked(t), t)
    }

    @Test
    fun moreThanOneSentenceBlocks() {
        assertTrue(Guards.blocked("I spent 35 on lunch. It was good"))
        assertTrue(Guards.blocked("I spent 35 on lunch!\nAdd task call mom"))
        assertFalse(Guards.blocked("I spent 35.5 on lunch"))
    }

    @Test
    fun twoMoneyAmountsBlockButDatesTimesAndScoresDoNot() {
        assertTrue(Guards.blocked("I spent 35 on lunch and 20 on coffee"))
        assertTrue(Guards.blocked("spent 35 on lunch, 12 on a taxi"))
        assertFalse(Guards.blocked("I spent 35 on lunch at 13:30"))
        assertFalse(Guards.blocked("I spent 35 on lunch on 3/4"))
        assertFalse(Guards.blocked("check-in: mood 5 energy 4 sleep 3 stress 1"))
        assertFalse(Guards.blocked("check-in mood 3, energy 2, sleep 3, stress 4"))
        assertFalse(Guards.blocked("please check-in mood 4 energy 4 sleep 4 stress 3"))
    }

    @Test
    fun twoVerbsJoinedByAndOrThenBlock() {
        assertTrue(Guards.blocked("add task call mom and mark the report done"))
        assertTrue(Guards.blocked("mark the report done then add task call mom"))
        assertFalse(Guards.blocked("add task call mom and dad"))
    }

    @Test
    fun overTwoHundredCharactersBlocks() {
        assertTrue(Guards.blocked("add task " + "a".repeat(200)))
        assertFalse(Guards.blocked("add task " + "a".repeat(100)))
    }

    @Test
    fun questionsAreDetected() {
        assertTrue(Guards.isQuestion("what is on today"))
        assertTrue(Guards.isQuestion("tasks left?"))
        assertTrue(Guards.isQuestion("any events tomorrow"))
        assertTrue(Guards.isQuestion("Can you add a task"))
        assertTrue(Guards.isQuestion("please how much did I spend"))
        assertFalse(Guards.isQuestion("add task call mom"))
        assertFalse(Guards.isQuestion("I spent 35 on lunch"))
        assertFalse(Guards.isQuestion("domain notes"))
    }

    @Test
    fun otherDayIsDetected() {
        for (t in listOf(
            "how much did I spend this month", "I spent 35 on lunch yesterday",
            "check-in for Monday: mood 4 energy 3 sleep 3 stress 2", "how much did I spend last week",
            "what's on next week", "what's on at the weekend", "what did I eat the day before", "I ate pizza 2 days ago",
            "lunch on the 5th", "spent 40 on 12/03", "spent 40 on 2026-03-12", "I spent 20 in March",
            "what's on later", "what have I spent so far", "so far I did lots", "all time spending", "מה יש מחר", "מה אכלתי אתמול",
        )) assertTrue(Guards.otherDay(t), t)
    }

    @Test
    fun soFarWithTheCurrentPeriodIsNotOtherDay() {
        for (t in listOf("what did I spend so far this week", "how much have I spent this week so far", "what is on today so far", "spending so far today")) {
            assertFalse(Guards.otherDay(t), t)
        }
        assertTrue(Guards.otherDay("what did I spend so far last week"))
    }

    @Test
    fun todayTextIsNotOtherDay() {
        for (t in listOf("what's on today", "I spent 35 on lunch", "what's on tomorrow", "show my tasks this week", "whatever")) {
            assertFalse(Guards.otherDay(t), t)
        }
    }

    @Test
    fun fourNamedScoresAreNotMoneyWithOrWithoutACheckinLead() {
        assertFalse(Guards.blocked("rough day but mood 4, energy 3, sleep 5, stress 2"))
        assertFalse(Guards.blocked("my numbers: sleep quality 4 stress 1 mood 3 energy 2"))
        // three named scores or a fifth number: the digits count as amounts again
        assertTrue(Guards.blocked("mood 4 energy 3 sleep 5 and I spent 40 on lunch"))
        assertTrue(Guards.blocked("rough day, mood 4 energy 3 stress 2 and 7"))
        assertTrue(Guards.blocked("mood 4 energy 3 sleep 5 stress 2 and 9 and 12"))
    }

    @Test
    fun hebrewIsDetected() {
        assertTrue(Guards.hasHebrew("מה יש היום"))
        assertTrue(Guards.hasHebrew("add task שלום"))
        assertFalse(Guards.hasHebrew("add task hello"))
        assertEquals(false, Guards.hasHebrew(""))
    }
}
