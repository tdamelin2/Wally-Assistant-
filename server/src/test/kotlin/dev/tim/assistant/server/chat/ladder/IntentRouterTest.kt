package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentRouterTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun route(text: String) = IntentRouter.route(text, today)

    private fun assertMatch(text: String, intent: IntentName, slots: Map<String, String>) {
        assertEquals(RouteResult.Match(intent, slots), route(text), text)
    }

    private fun assertNone(vararg texts: String) {
        for (t in texts) assertEquals(RouteResult.NoMatch, route(t), t)
    }

    @Test
    fun createTask() {
        assertMatch("add task buy milk", IntentName.CREATE_TASK, mapOf("title" to "buy milk"))
        assertMatch("Add a task: call mom tomorrow", IntentName.CREATE_TASK, mapOf("title" to "call mom", "dueDate" to "2026-11-13"))
        assertMatch("new task, read chapter 3", IntentName.CREATE_TASK, mapOf("title" to "read chapter 3"))
        assertMatch("todo: send the form on 2026-12-01", IntentName.CREATE_TASK, mapOf("title" to "send the form", "dueDate" to "2026-12-01"))
        assertMatch("remind me to water the plants", IntentName.CREATE_TASK, mapOf("title" to "water the plants"))
        assertMatch("hey wally add task buy 2 tickets on 15/03", IntentName.CREATE_TASK, mapOf("title" to "buy 2 tickets", "dueDate" to "2027-03-15"))
        assertNone(
            "remind me to take my pills at 9pm", "add task", "add task buy milk yesterday", "add task force training",
            "new task force at work", "todo list is getting long", "add task buy milk tomorrow or friday",
        )
    }

    @Test
    fun addSpending() {
        assertMatch("I spent 35 on lunch", IntentName.ADD_SPENDING, mapOf("amount" to "35", "category" to "Food", "note" to "lunch"))
        assertMatch("paid 12.5 for parking", IntentName.ADD_SPENDING, mapOf("amount" to "12.5", "category" to "Transport", "note" to "parking"))
        assertMatch("just spent 80 shekels on shoes", IntentName.ADD_SPENDING, mapOf("amount" to "80", "category" to "Shopping", "note" to "shoes"))
        assertMatch("the movie cost me 45", IntentName.ADD_SPENDING, mapOf("amount" to "45", "category" to "Fun", "note" to "the movie"))
        assertNone(
            "Dana paid 35 for lunch", "Dana's lunch cost 35", "spent 35 minutes on lunch", "spent 35 dollars on lunch",
            "I spent 35 on something", "I spent 35 on a taxi to the restaurant", "I spent 35 on lunch the day before yesterday",
            "I spent 500 on groceries this week", "I spent 35 on lunch tomorrow", "I spent 35 on lunch in total",
        )
    }

    @Test
    fun aPastDayAtAnEdgeAddsTheDateSlotAndTypedOnAnEarlierDayDoesNot() {
        assertMatch(
            "I spent 35 on lunch yesterday", IntentName.ADD_SPENDING,
            mapOf("amount" to "35", "category" to "Food", "note" to "lunch", "date" to "2026-11-11"),
        )
        assertEquals(RouteResult.NoMatch, IntentRouter.route("I spent 35 on lunch yesterday", today, typedToday = false))
        assertMatch(
            "log lunch: salad yesterday at 13:00", IntentName.ADD_MEAL,
            mapOf("type" to "MEAL", "text" to "salad", "date" to "2026-11-11", "time" to "13:00"),
        )
    }

    @Test
    fun addMeal() {
        assertMatch("log lunch pasta salad", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "pasta salad"))
        assertMatch("log a snack: carrots", IntentName.ADD_MEAL, mapOf("type" to "SNACK", "text" to "carrots"))
        assertMatch("add dinner: soup and bread", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "soup and bread"))
        assertMatch("I ate toast for breakfast", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "toast"))
        assertNone(
            "I had guests for dinner", "I ate too much for dinner", "I ate out for lunch", "add lunch with Dana at 1",
            "add dinner tonight", "log lunch with Dana", "log lunch at 1pm", "log dinner meeting with the team",
            "I ate pasta for lunch yesterday",
        )
    }

    @Test
    fun addCheckin() {
        assertMatch(
            "check-in: mood 4 energy 3 sleep 3 stress 2", IntentName.ADD_CHECKIN,
            mapOf("mood" to "4", "energy" to "3", "sleepQuality" to "3", "stress" to "2"),
        )
        assertMatch(
            "checkin mood 1 energy 1 sleep 1 stress 5", IntentName.ADD_CHECKIN,
            mapOf("mood" to "1", "energy" to "1", "sleepQuality" to "1", "stress" to "5"),
        )
        assertMatch(
            "check in: stress 2, sleep quality 4, energy 3, mood 5", IntentName.ADD_CHECKIN,
            mapOf("mood" to "5", "energy" to "3", "sleepQuality" to "4", "stress" to "2"),
        )
        assertMatch(
            "please check-in mood 3 energy 3 sleep 3 stress 3", IntentName.ADD_CHECKIN,
            mapOf("mood" to "3", "energy" to "3", "sleepQuality" to "3", "stress" to "3"),
        )
        assertNone(
            "check-in: mood 4 energy 3 sleep 3", "check-in for Monday: mood 4 energy 3 sleep 3 stress 2",
            "check-in: mood 9 energy 3 sleep 3 stress 2", "I checked in at the hotel", "check in at the gym",
            "check-in tomorrow: mood 4 energy 3 sleep 3 stress 2",
        )
    }

    @Test
    fun addJournal() {
        assertMatch("journal: a quiet day", IntentName.ADD_JOURNAL, mapOf("text" to "a quiet day"))
        assertMatch("Journal, long walk helped", IntentName.ADD_JOURNAL, mapOf("text" to "long walk helped"))
        assertMatch("note to self: ask about the deadline", IntentName.ADD_JOURNAL, mapOf("text" to "ask about the deadline"))
        assertMatch("note to self, buy a notebook", IntentName.ADD_JOURNAL, mapOf("text" to "buy a notebook"))
        assertNone(
            "my journal is full", "journal entries are fun", "note to self is a good habit", "journal: I had a dream about school",
            "journal: tomorrow is a big day", "journal: not a good day",
        )
    }

    @Test
    fun addDream() {
        assertMatch("I dreamt that I flew", IntentName.ADD_DREAM, mapOf("text" to "I dreamt that I flew"))
        assertMatch("I dreamed about the sea", IntentName.ADD_DREAM, mapOf("text" to "I dreamed about the sea"))
        assertMatch("last night I dreamt of a train", IntentName.ADD_DREAM, mapOf("text" to "last night I dreamt of a train"))
        assertMatch("last night I dreamed the house was empty", IntentName.ADD_DREAM, mapOf("text" to "last night I dreamed the house was empty"))
        assertNone(
            "I had a dream job once", "I dreamed of being a pilot as a kid", "my dream is to travel", "journal: I had a dream about school",
            "did I dream last night?", "I didn't dream about it",
        )
    }

    @Test
    fun markTaskDone() {
        assertMatch("mark the report as done", IntentName.MARK_TASK_DONE, mapOf("name" to "report"))
        assertMatch("mark my laundry task", IntentName.MARK_TASK_DONE, mapOf("name" to "laundry"))
        assertMatch("check off the dishes", IntentName.MARK_TASK_DONE, mapOf("name" to "dishes"))
        assertMatch("finish the taxes task", IntentName.MARK_TASK_DONE, mapOf("name" to "taxes"))
        assertMatch("I completed the poster task", IntentName.MARK_TASK_DONE, mapOf("name" to "poster"))
        assertNone(
            "finish the report by Friday", "complete the form", "mark my words", "mark done", "I finished the report",
            "mark the report as done and add task call mom", "don't mark the report as done",
        )
    }

    @Test
    fun moveTask() {
        assertMatch("move the report task to friday", IntentName.MOVE_TASK, mapOf("name" to "report", "date" to "2026-11-13"))
        assertMatch("reschedule my taxes task to 2026-12-01", IntentName.MOVE_TASK, mapOf("name" to "taxes", "date" to "2026-12-01"))
        assertMatch("push the poster task to next week", IntentName.MOVE_TASK, mapOf("name" to "poster", "date" to "2026-11-19"))
        assertMatch("move the report to friday", IntentName.MOVE_TASK, mapOf("name" to "report", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("postpone the gym task to in 2 days", IntentName.MOVE_TASK, mapOf("name" to "gym", "date" to "2026-11-14"))
        assertNone(
            "move the report task to someday", "move the report task", "move the report task to friday at 9am",
            "move the report task to 2020-01-01", "move the report task to yesterday",
        )
    }

    @Test
    fun moveExam() {
        assertMatch("move the algebra exam to 2026-12-01 at 9am", IntentName.MOVE_EXAM, mapOf("name" to "algebra", "date" to "2026-12-01", "time" to "09:00"))
        assertMatch("reschedule my physics exam to friday", IntentName.MOVE_EXAM, mapOf("name" to "physics", "date" to "2026-11-13"))
        assertMatch("move the law exam to tomorrow at 6:30pm", IntentName.MOVE_EXAM, mapOf("name" to "law", "date" to "2026-11-13", "time" to "18:30"))
        assertMatch("move the art exam to 20/12", IntentName.MOVE_EXAM, mapOf("name" to "art", "date" to "2026-12-20"))
        assertNone(
            "move the algebra exam to someday", "move the algebra exam to friday at noon", "move the algebra exam",
            "move the algebra exam to 31/02", "move the algebra exam to friday at 25:00",
        )
    }

    @Test
    fun find() {
        assertMatch("find my algebra course", IntentName.FIND, mapOf("name" to "algebra", "kind" to "course"))
        assertMatch("open the lecture notes", IntentName.FIND, mapOf("name" to "lecture", "kind" to "doc"))
        assertMatch("show me the bank email", IntentName.FIND, mapOf("name" to "bank", "kind" to "mail"))
        assertMatch("find the dentist event", IntentName.FIND, mapOf("name" to "dentist", "kind" to "event"))
        assertNone("find my goal", "show me my goal", "find the spending")
        // no kind word: a match with kind any, which Ladder answers only for exactly one item
        assertMatch("open the window", IntentName.FIND, mapOf("name" to "window", "kind" to "any"))
    }

    @Test
    fun askToday() {
        assertMatch("what's on today", IntentName.ASK_TODAY, emptyMap())
        assertMatch("What is on today?", IntentName.ASK_TODAY, emptyMap())
        assertMatch("what do I have today", IntentName.ASK_TODAY, emptyMap())
        assertMatch("what is left today", IntentName.ASK_TODAY, emptyMap())
        assertNone("what's on today and tomorrow", "what's on today yesterday", "what's on")
        // another day is the new ask_events row (Phase 20), never today's overview
        assertMatch("what's on tomorrow", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-13"))
        assertMatch("what's on next week", IntentName.ASK_EVENTS, mapOf("period" to "next_week"))
    }

    @Test
    fun askTasks() {
        assertMatch("what are my tasks", IntentName.ASK_TASKS, emptyMap())
        assertMatch("show me my tasks", IntentName.ASK_TASKS, emptyMap())
        assertMatch("what's due tomorrow", IntentName.ASK_TASKS, mapOf("when" to "tomorrow"))
        assertMatch("what is due this week", IntentName.ASK_TASKS, mapOf("when" to "week"))
        assertNone("what are my tasks next week", "show me my task", "what are my tasks last month", "what's due", "what's due today and tomorrow")
        assertMatch("what's due yesterday", IntentName.ASK_TASKS, mapOf("period" to "yesterday"))
        assertMatch("tasks due next week", IntentName.ASK_TASKS, mapOf("period" to "next_week"))
        assertMatch("what's due on friday", IntentName.ASK_TASKS, mapOf("period" to "weekday:friday"))
    }

    @Test
    fun askEvents() {
        assertMatch("what's on my calendar today", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-12"))
        assertMatch("what is on my calendar tomorrow", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-13"))
        assertMatch("any events today", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-12"))
        assertMatch("Any events tomorrow?", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-13"))
        assertNone("what's on my calendar", "any events", "events", "any events today and friday")
        assertMatch("any events next week", IntentName.ASK_EVENTS, mapOf("period" to "next_week"))
        assertMatch("any events on friday", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-13"))
        assertMatch("any events yesterday", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-11"))
        assertMatch("what's on my calendar on 20/11", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-20"))
    }

    @Test
    fun askSpendingWeek() {
        assertMatch("how much have I spent this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("how much did I spend this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("How much did I spend this week?", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("how much have I spent this week.", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertNone("how much did I spend this week tomorrow")
        // Phase 20: other periods and no period are the new ask_spending row (no period means this week, with a category that category)
        assertMatch("how much did I spend last week", IntentName.ASK_SPENDING, mapOf("period" to "last_week"))
        assertMatch("how much did I spend this month", IntentName.ASK_SPENDING, mapOf("period" to "this_month"))
        assertMatch("how much did I spend", IntentName.ASK_SPENDING, mapOf("period" to "this_week"))
        assertMatch("how much did I spend on food", IntentName.ASK_SPENDING, mapOf("period" to "this_week", "category" to "Food"))
    }

    @Test
    fun newSpendingRow() {
        assertMatch("how much did I spend on food last month", IntentName.ASK_SPENDING, mapOf("period" to "last_month", "category" to "Food"))
        assertMatch("what did I spend yesterday", IntentName.ASK_SPENDING, mapOf("period" to "yesterday"))
        assertMatch("my spending on groceries in november", IntentName.ASK_SPENDING, mapOf("period" to "month:november", "category" to "Food"))
        assertNone(
            "how much did I spend on whatever last week", "how much did I spend tomorrow", "how much did I earn last week",
            "spending", "how much did I not spend last week", "how much did I spend yesterday and last week",
        )
    }

    @Test
    fun newExamRow() {
        assertMatch("when is my next exam", IntentName.ASK_EXAM, emptyMap())
        assertMatch("my upcoming exams", IntentName.ASK_EXAM, emptyMap())
        assertMatch("when's the physics exam", IntentName.ASK_EXAM, mapOf("name" to "physics"))
        assertNone("when is the exam tomorrow", "when is the next exam on friday", "when is the my exam", "exams", "when is the next exam yesterday")
    }

    @Test
    fun newHealthRow() {
        assertMatch("how did I sleep last night", IntentName.ASK_HEALTH, mapOf("metric" to "sleep", "period" to "last_night"))
        assertMatch("how many steps did I walk yesterday", IntentName.ASK_HEALTH, mapOf("metric" to "steps", "period" to "yesterday"))
        assertMatch("what was my resting heart rate last week", IntentName.ASK_HEALTH, mapOf("metric" to "heart", "period" to "last_week"))
        assertMatch("my steps", IntentName.ASK_HEALTH, mapOf("metric" to "steps", "period" to "today"))
        assertNone("steps", "sleep", "my steps last night", "my steps tomorrow", "my sleep last month", "how many steps did I take today and yesterday")
    }

    @Test
    fun newMealsRow() {
        assertMatch("what did I eat yesterday", IntentName.ASK_MEALS, mapOf("what" to "list", "period" to "yesterday"))
        assertMatch("how many calories did I have on friday", IntentName.ASK_MEALS, mapOf("what" to "calories", "period" to "weekday:friday"))
        assertMatch("my meals", IntentName.ASK_MEALS, mapOf("what" to "list", "period" to "today"))
        assertNone("meals", "what did I eat last week", "my calories last night", "what did I eat tomorrow")
    }

    @Test
    fun newCheckinRow() {
        assertMatch("how was my mood yesterday", IntentName.ASK_CHECKIN, mapOf("period" to "yesterday"))
        assertMatch("how was my day", IntentName.ASK_CHECKIN, mapOf("period" to "today"))
        assertMatch("my energy last week", IntentName.ASK_CHECKIN, mapOf("period" to "last_week"))
        assertNone("mood", "check-in", "how was my mood last month", "how was my mood tomorrow", "how was my day yesterday and today")
    }

    @Test
    fun everyKeptAskRowStillYieldsExactlyOneMatch() {
        assertMatch("what's on today", IntentName.ASK_TODAY, emptyMap())
        assertMatch("what are my tasks", IntentName.ASK_TASKS, emptyMap())
        assertMatch("how much have I spent this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("my spending this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
    }

    @Test
    fun aQuestionNeverMatchesAnAddOrAnEdit() {
        assertNone(
            "can you add task buy milk", "should I add task buy milk", "did I spend 35 on lunch?",
            "add task buy milk?", "what mark the report as done",
        )
    }

    @Test
    fun hebrewAndBlockedTextNeverMatch() {
        assertNone("מה יש היום", "add task שלום", "lunch will cost 35", "add task a. add task b", "I didn't ask what's on today")
    }

    @Test
    fun dreamTextOnlyMatchesTheDreamRow() {
        assertTrue(route("journal: I dreamt about school") is RouteResult.NoMatch)
        assertTrue(route("add task analyse the dream") is RouteResult.NoMatch)
    }

    @Test
    fun routerIsDeterministicForTheSameDay() {
        assertEquals(route("add task buy milk friday"), route("add task buy milk friday"))
        assertEquals(
            IntentRouter.route("add task buy milk friday", LocalDate.of(2026, 11, 13)),
            RouteResult.Match(IntentName.CREATE_TASK, mapOf("title" to "buy milk", "dueDate" to "2026-11-20")),
        )
    }

    // ---- Round 1 widenings (general phrasing forms) ----

    @Test
    fun createTaskTakesMoreCreationVerbsAndCleansTheTitle() {
        assertMatch("create a task: water the herbs", IntentName.CREATE_TASK, mapOf("title" to "water the herbs"))
        assertMatch("make a new task renew the passport", IntentName.CREATE_TASK, mapOf("title" to "renew the passport"))
        assertMatch("put task wash the car tomorrow", IntentName.CREATE_TASK, mapOf("title" to "wash the car", "dueDate" to "2026-11-13"))
        assertMatch("add a task to call the plumber on monday", IntentName.CREATE_TASK, mapOf("title" to "call the plumber", "dueDate" to "2026-11-16"))
        assertMatch("add task submit the form for friday", IntentName.CREATE_TASK, mapOf("title" to "submit the form", "dueDate" to "2026-11-13"))
        assertNone("create a task force", "make a task of it yesterday", "create task", "create a task and add a task")
    }

    @Test
    fun addSpendingKnowsMoreItemNouns() {
        assertMatch("I spent 42 on sushi", IntentName.ADD_SPENDING, mapOf("amount" to "42", "category" to "Food", "note" to "sushi"))
        assertMatch("paid 120 for a new jacket", IntentName.ADD_SPENDING, mapOf("amount" to "120", "category" to "Shopping", "note" to "a new jacket"))
        assertMatch("spent 30 on the water bill", IntentName.ADD_SPENDING, mapOf("amount" to "30", "category" to "Bills", "note" to "the water bill"))
        assertMatch("the museum cost me 25", IntentName.ADD_SPENDING, mapOf("amount" to "25", "category" to "Fun", "note" to "the museum"))
        assertMatch("spent 90 on vitamins", IntentName.ADD_SPENDING, mapOf("amount" to "90", "category" to "Health", "note" to "vitamins"))
        assertNone("I spent 30 on a gift and a burger", "I spent 30 on a thingamajig")
    }

    @Test
    fun journalVerbForm() {
        assertMatch("add to my journal that the exam went fine", IntentName.ADD_JOURNAL, mapOf("text" to "the exam went fine"))
        assertMatch("write in the journal: slept badly again", IntentName.ADD_JOURNAL, mapOf("text" to "slept badly again"))
        assertMatch("put this in my journal, a calm morning", IntentName.ADD_JOURNAL, mapOf("text" to "a calm morning"))
        assertNone("add to my journal", "write in my journal", "add to my journal that I will quit")
    }

    @Test
    fun findWithoutAKindWord() {
        assertMatch("find my physics", IntentName.FIND, mapOf("name" to "physics", "kind" to "any"))
        assertMatch("pull up the lease", IntentName.FIND, mapOf("name" to "lease", "kind" to "any"))
        assertMatch("look up my passport", IntentName.FIND, mapOf("name" to "passport", "kind" to "any"))
        assertMatch("find my chemistry course", IntentName.FIND, mapOf("name" to "chemistry", "kind" to "course"))
        assertNone("find my calendar", "open the tasks", "find my spending this week", "find tomorrow", "find the tasks")
    }

    @Test
    fun changeStyleVerbsAndDayMonthDates() {
        assertMatch("change my algebra exam to 5 December", IntentName.MOVE_EXAM, mapOf("name" to "algebra", "date" to "2026-12-05"))
        assertMatch("shift the lab exam to 3rd of January", IntentName.MOVE_EXAM, mapOf("name" to "lab", "date" to "2027-01-03"))
        assertMatch("reschedule the algebra exam to 6th March at 10:30", IntentName.MOVE_EXAM, mapOf("name" to "algebra", "date" to "2027-03-06", "time" to "10:30"))
        assertMatch("change the report task to 20 November", IntentName.MOVE_TASK, mapOf("name" to "report", "date" to "2026-11-20"))
        assertNone("change my algebra exam to 31 February", "change my algebra exam to 5 December or 6 December")
    }

    @Test
    fun spendingQuestionWithMoneyAndSlangVerbs() {
        for (t in listOf(
            "how much money have I spent this week", "how much money did I blow this week", "how much have I burned this week so far",
            "how much did I spend so far this week",
        )) assertMatch(t, IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertNone("how much money did I earn this week")
        assertMatch("how much money did I spend last week", IntentName.ASK_SPENDING, mapOf("period" to "last_week"))
        assertMatch("how much money have I spent", IntentName.ASK_SPENDING, mapOf("period" to "this_week"))
    }

    @Test
    fun movingWithoutTheWordTaskCarriesALooseMark() {
        assertMatch("move the dentist call to friday", IntentName.MOVE_TASK, mapOf("name" to "dentist call", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("push my laundry to tomorrow", IntentName.MOVE_TASK, mapOf("name" to "laundry", "date" to "2026-11-13", "loose" to "1"))
        assertNone("move the report to the shared folder", "move the algebra exam to friday at 9 or 10")
        assertMatch("move the algebra exam to friday", IntentName.MOVE_EXAM, mapOf("name" to "algebra", "date" to "2026-11-13"))
    }
    @Test
    fun round2JournalMoveFindForms() {
        assertMatch("diary entry: finally slept well", IntentName.ADD_JOURNAL, mapOf("text" to "finally slept well"))
        assertMatch("new journal note, felt calm at the library", IntentName.ADD_JOURNAL, mapOf("text" to "felt calm at the library"))
        assertNone("diary entry:", "journal entry")
        assertMatch("postpone the physics exam to friday", IntentName.MOVE_EXAM, mapOf("name" to "physics", "date" to "2026-11-13"))
        assertMatch("bump my calculus exam until 20 December", IntentName.MOVE_EXAM, mapOf("name" to "calculus", "date" to "2026-12-20"))
        assertNone("postpone the physics exam", "delay my exam till someday")
        assertMatch("push the report task until monday", IntentName.MOVE_TASK, mapOf("name" to "report", "date" to "2026-11-16"))
        assertMatch("delay the invoices till friday", IntentName.MOVE_TASK, mapOf("name" to "invoices", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("search for physics", IntentName.FIND, mapOf("name" to "physics", "kind" to "any"))
        assertMatch("look for my lease", IntentName.FIND, mapOf("name" to "lease", "kind" to "any"))
        assertMatch("search my chemistry notes", IntentName.FIND, mapOf("name" to "chemistry", "kind" to "doc"))
        assertNone("search for the calendar", "look for today", "search for tasks")
    }

    @Test
    fun round2NounPhraseReadsAndBoughtForShekels() {
        assertMatch("weekly spending", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("my expenses this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("show me spending total this week", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertMatch("this week's spend so far", IntentName.ASK_SPENDING_WEEK, emptyMap())
        assertNone("spending", "expenses for tomorrow")
        assertMatch("my expenses", IntentName.ASK_SPENDING, mapOf("period" to "this_week"))
        assertMatch("spending this month", IntentName.ASK_SPENDING, mapOf("period" to "this_month"))
        assertMatch("my agenda today", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-12"))
        assertMatch("today's schedule", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-12"))
        assertMatch("calendar events for today", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-12"))
        assertNone("plans today and tomorrow", "today")
        assertMatch("agenda tomorrow", IntentName.ASK_EVENTS, mapOf("day" to "2026-11-13"))
        assertMatch("bought new sneakers for 250 shekels", IntentName.ADD_SPENDING, mapOf("amount" to "250", "category" to "Shopping", "note" to "sneakers"))
        assertMatch("I just got a jacket for \u20AA180", IntentName.ADD_SPENDING, mapOf("amount" to "180", "category" to "Shopping", "note" to "jacket"))
        assertMatch("purchased the backpack for 90 nis", IntentName.ADD_SPENDING, mapOf("amount" to "90", "category" to "Shopping", "note" to "backpack"))
        assertNone("I got tickets for 2", "got shoes for 300", "bought pizza for 4", "we got dinner for 6 people", "bought a gift for my mom")
    }

    // ---- Round 1 (R1): day-question shapes, meal verbs, check-in without a lead ----

    @Test
    fun spendingNounWithLeadOrCategoryBeforeIt() {
        assertMatch("what's my food spending yesterday", IntentName.ASK_SPENDING, mapOf("period" to "yesterday", "category" to "Food"))
        assertMatch("what was my total spending last week", IntentName.ASK_SPENDING, mapOf("period" to "last_week"))
        assertMatch("what is my transport spending", IntentName.ASK_SPENDING, mapOf("period" to "this_week", "category" to "Transport"))
        assertMatch("food spending last month", IntentName.ASK_SPENDING, mapOf("period" to "last_month", "category" to "Food"))
        assertMatch("my fun expenses last week", IntentName.ASK_SPENDING, mapOf("period" to "last_week", "category" to "Fun"))
        assertNone(
            "food spending", "what's my whatever spending yesterday", "what's my spending tomorrow",
            "food spending last week and yesterday", "what's my food spending not yesterday",
        )
    }

    @Test
    fun tasksDueWithAHaveDueLead() {
        assertMatch("what do I have due tomorrow", IntentName.ASK_TASKS, mapOf("when" to "tomorrow"))
        assertMatch("what do I have due on friday", IntentName.ASK_TASKS, mapOf("period" to "weekday:friday"))
        assertMatch("anything due next week", IntentName.ASK_TASKS, mapOf("period" to "next_week"))
        assertMatch("do I have anything due today", IntentName.ASK_TASKS, mapOf("when" to "today"))
        assertNone("what do I have due", "what do I have due today and tomorrow", "what do I have due last night", "anything due")
    }

    @Test
    fun examDateForms() {
        assertMatch("what date is the physics exam", IntentName.ASK_EXAM, mapOf("name" to "physics"))
        assertMatch("what day is my chemistry exam", IntentName.ASK_EXAM, mapOf("name" to "chemistry"))
        assertMatch("when do I have the biology exam", IntentName.ASK_EXAM, mapOf("name" to "biology"))
        assertMatch("when does the law exam take place", IntentName.ASK_EXAM, mapOf("name" to "law"))
        assertMatch("when does my next exam start", IntentName.ASK_EXAM, emptyMap())
        assertMatch("when do I have my next exam", IntentName.ASK_EXAM, emptyMap())
        assertMatch("what date is my next exam", IntentName.ASK_EXAM, emptyMap())
        assertNone(
            "when do I have the exam", "when does the law exam take place tomorrow", "when do I have the math exam and the law exam",
            "what date is the physics exam today and friday", "when does my exam start",
        )
    }

    @Test
    fun mealRowAcceptsHadAnOptionalIAndAsAType() {
        assertMatch("had pasta for lunch", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "pasta"))
        assertMatch("I had a bagel as a snack", IntentName.ADD_MEAL, mapOf("type" to "SNACK", "text" to "a bagel"))
        assertMatch("ate soup for dinner", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "soup"))
        assertMatch("I just had a banana as a snack", IntentName.ADD_MEAL, mapOf("type" to "SNACK", "text" to "a banana"))
        assertNone(
            "I had a meeting for lunch", "I had a yoga class for lunch", "I had a call as a snack", "I had guests for dinner",
            "had friends for dinner", "I had to leave for lunch", "I had it for lunch",
        )
    }

    @Test
    fun checkinWithFourNamedScoresNeedsNoLead() {
        val scores = mapOf("mood" to "4", "energy" to "3", "sleepQuality" to "5", "stress" to "2")
        assertMatch("rough day but mood 4, energy 3, sleep 5, stress 2", IntentName.ADD_CHECKIN, scores)
        assertMatch("my numbers: stress 2 sleep 5 energy 3 mood 4", IntentName.ADD_CHECKIN, scores)
        assertNone(
            "mood 4 energy 3 sleep 5", "mood 4 energy 3 sleep 5 stress 2 and also 9", "did my mood 4 energy 3 sleep 5 stress 2 change?",
            "yesterday mood 4 energy 3 sleep 5 stress 2", "not mood 4 energy 3 sleep 5 stress 2",
        )
    }
}
