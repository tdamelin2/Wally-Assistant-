import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.ui.AddAction
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.Hub
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.forms.DraftStore
import dev.tim.assistant.ui.forms.DueChip
import dev.tim.assistant.ui.forms.dueFromChip
import dev.tim.assistant.ui.forms.initialWriterText
import dev.tim.assistant.ui.hubOrder
import dev.tim.assistant.ui.layoutModeForDevice
import dev.tim.assistant.ui.lists.HistoryMonth
import dev.tim.assistant.ui.lists.MealStatus
import dev.tim.assistant.ui.lists.TaskGroup
import dev.tim.assistant.ui.lists.checkinDots
import dev.tim.assistant.ui.lists.earlyOrLate
import dev.tim.assistant.ui.lists.mealDayTotal
import dev.tim.assistant.ui.lists.mealStatus
import dev.tim.assistant.ui.lists.monthRange
import dev.tim.assistant.ui.lists.previousMonths
import dev.tim.assistant.ui.lists.taskGroups
import dev.tim.assistant.ui.lists.weekByCategory
import dev.tim.assistant.ui.pendingRequest
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.DEVICE_PHONE
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.dayTotals
import dev.tim.assistant.usage.topApps
import dev.tim.assistant.usage.unsortedApps
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** Phase 15 acceptance tests, written from the Plan's AC list and Contract (AC-1..AC-10 automated parts). */
class Phase15AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()
    private val ui = "shared/src/commonMain/kotlin/dev/tim/assistant/ui"

    private fun ktFiles(vararg dirs: String): List<File> = dirs.flatMap { d ->
        File(root, d).walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun d(s: String) = LocalDate.parse(s)

    // ------------------------------------------------------------ AC-1 / AC-2 shell

    @Test
    fun test_AC1_layoutModeForDevice_laptop_wide_phone_narrow() {
        assertEquals(LayoutMode.Wide, layoutModeForDevice(true))
        assertEquals(LayoutMode.Narrow, layoutModeForDevice(false))
    }

    @Test
    fun test_AC1_raw_colours_only_in_Theme_kt() {
        val pattern = Regex("""Color\.[A-Z]|Color\(0x""")
        val hits = ktFiles("shared/src", "desktopApp/src", "androidApp/src").filter { it.name != "Theme.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (pattern.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }
        assertTrue(hits.isEmpty(), "raw colours outside Theme.kt: $hits")
    }

    @Test
    fun test_AC1_theme_is_light_only() {
        val theme = text("$ui/Theme.kt")
        assertTrue(theme.contains("lightColorScheme"))
        assertFalse(theme.contains("darkColorScheme"))
        assertFalse(theme.contains("isSystemInDarkTheme"))
    }

    @Test
    fun test_AC2_hub_order_is_today_log_chat_insights_browse() {
        assertEquals(listOf("Today", "Log", "Chat", "Insights", "Browse"), hubOrder.map { it.label })
        assertEquals(listOf(Hub.Today, Hub.Log, Hub.Chat, Hub.Insights, Hub.Browse), hubOrder)
        assertEquals(Hub.entries, hubOrder)
    }

    @Test
    fun test_AC2_addActionFor_all_17_tabs() {
        assertEquals(17, NavTab.entries.size)
        assertEquals(AddAction.Picker, addActionFor(NavTab.Today))
        assertEquals(AddAction.Form(FormTarget.Checkin(null)), addActionFor(NavTab.Checkins))
        assertEquals(AddAction.Form(FormTarget.Spending(null)), addActionFor(NavTab.Spending))
        assertEquals(AddAction.Form(FormTarget.Journal(null)), addActionFor(NavTab.Journal))
        assertEquals(AddAction.Form(FormTarget.Meal(null)), addActionFor(NavTab.Meals))
        assertEquals(AddAction.Form(FormTarget.Task(null)), addActionFor(NavTab.Tasks))
        assertEquals(AddAction.Form(FormTarget.Dream(null)), addActionFor(NavTab.Dreams))
        assertEquals(AddAction.NewGoal, addActionFor(NavTab.Goals))
        assertEquals(AddAction.NewDoc, addActionFor(NavTab.Files))
        assertEquals(AddAction.StudyAdd, addActionFor(NavTab.Study))
        assertEquals(AddAction.NewEmail, addActionFor(NavTab.Mail))
        for (t in listOf(NavTab.Charts, NavTab.Health, NavTab.Screentime, NavTab.Chat, NavTab.Hardware, NavTab.Settings)) {
            assertNull(addActionFor(t), "tab $t")
        }
    }

    @Test
    fun test_AC2_pendingRequest_only_when_request_is_newer() {
        assertTrue(pendingRequest(1, 0))
        assertTrue(pendingRequest(5, 4))
        assertFalse(pendingRequest(0, 0))
        assertFalse(pendingRequest(3, 3))
        assertFalse(pendingRequest(2, 3))
    }

    // ------------------------------------------------------------ AC-3 Today / log logic

    @Test
    fun test_AC3_today_screen_has_no_Checkbox_and_no_CheckOffBox() {
        val t = text("$ui/TodayScreen.kt")
        assertFalse(t.contains("Checkbox("))
        assertFalse(t.contains("CheckOffBox"))
    }

    @Test
    fun test_AC4_checkinDots_clamps_1_to_5() {
        assertEquals(1, checkinDots(1))
        assertEquals(3, checkinDots(3))
        assertEquals(5, checkinDots(5))
        assertEquals(1, checkinDots(0))
        assertEquals(1, checkinDots(-4))
        assertEquals(5, checkinDots(9))
    }

    private fun spend(id: String, cat: String, agorot: Long, date: String) =
        Spending(id, 1, 0, 0, "SYNCED", agorot, cat, null, date)

    @Test
    fun test_AC4_weekByCategory_sunday_start_largest_first() {
        // 2026-11-11 is a Wednesday; its Sunday-start week is Nov 8 .. Nov 14.
        val rows = listOf(
            spend("a", "Food", 1000, "2026-11-08"), // Sunday: in
            spend("b", "Food", 500, "2026-11-11"),
            spend("c", "Fun", 3000, "2026-11-14"), // Saturday: in
            spend("d", "Food", 9999, "2026-11-07"), // previous Saturday: out
            spend("e", "Fun", 9999, "2026-11-15"), // next Sunday: out
            spend("f", "Bus", 200, "2026-11-10"),
        )
        assertEquals(listOf("Fun" to 3000L, "Food" to 1500L, "Bus" to 200L), weekByCategory(rows, d("2026-11-11")))
        // on a Sunday the week starts that day
        assertEquals(listOf("Food" to 1000L), weekByCategory(rows.filter { it.id == "a" || it.id == "d" }, d("2026-11-08")))
        assertTrue(weekByCategory(emptyList(), d("2026-11-11")).isEmpty())
    }

    private fun meal(id: String, kcal: Long?, p: Long? = null, c: Long? = null, f: Long? = null, updatedAt: Long = 0) =
        Meal(id, updatedAt, 0, 0, "SYNCED", "Meal", "x", 0, "2026-11-11", kcal, p, c, f, null)

    @Test
    fun test_AC4_mealDayTotal_sums_estimated_and_counts_missing() {
        val t = mealDayTotal(listOf(meal("1", 500, 30, 50, 10), meal("2", 640, 20, 70, 20), meal("3", null), meal("4", null)))
        assertEquals(1140L, t.kcal)
        assertEquals(50L, t.protein)
        assertEquals(120L, t.carbs)
        assertEquals(30L, t.fat)
        assertEquals(2, t.missing)
        val none = mealDayTotal(emptyList())
        assertEquals(0L, none.kcal)
        assertEquals(0, none.missing)
    }

    private fun job(status: String) =
        Ai_job("job:1", 0, 0, 0, "SYNCED", "MEAL_LOOKUP", """{"mealId":"m"}""", status, null, null, 0, 0, null)

    private val h = 60L * 60 * 1000

    @Test
    fun test_AC4_mealStatus_estimated_when_kcal_present() {
        assertEquals(MealStatus.Estimated, mealStatus(meal("m", 300, updatedAt = 0), null, 10 * h))
        assertEquals(MealStatus.Estimated, mealStatus(meal("m", 300, updatedAt = 0), job("FAILED"), 10 * h))
    }

    @Test
    fun test_AC4_mealStatus_running_or_queued_job_is_estimating_even_after_3_hours() {
        val m = meal("m", null, updatedAt = 0)
        assertEquals(MealStatus.Estimating, mealStatus(m, job("RUNNING"), 3 * h))
        assertEquals(MealStatus.Estimating, mealStatus(m, job("QUEUED"), 3 * h))
    }

    @Test
    fun test_AC4_mealStatus_failed_job_is_failed() {
        assertEquals(MealStatus.Failed, mealStatus(meal("m", null, updatedAt = 0), job("FAILED"), 1000))
    }

    @Test
    fun test_AC4_mealStatus_two_hour_rule_without_lookup() {
        val m = meal("m", null, updatedAt = 0)
        assertEquals(MealStatus.Estimating, mealStatus(m, null, 0))
        assertEquals(MealStatus.Estimating, mealStatus(m, null, 2 * h)) // exactly 2 h is not more than 2 h
        assertEquals(MealStatus.Failed, mealStatus(m, null, 2 * h + 1))
        assertEquals(MealStatus.Estimating, mealStatus(meal("m", null, updatedAt = 10 * h), null, 2 * h)) // future updatedAt
    }

    private fun task(id: String, due: String?) = Task(id, 1, 0, 0, "SYNCED", id, due, 0, null, null)

    @Test
    fun test_AC4_taskGroups_overdue_today_week_later_and_no_due_never_overdue() {
        val today = d("2026-11-11")
        val tasks = listOf(
            task("old", "2026-11-10"), task("now", "2026-11-11"), task("tmrw", "2026-11-12"),
            task("wk", "2026-11-17"), task("later", "2026-11-18"), task("none", null),
        )
        val g = taskGroups(tasks, today)
        assertEquals(listOf("old"), g.getValue(TaskGroup.Overdue).map { it.id })
        assertEquals(listOf("now"), g.getValue(TaskGroup.Today).map { it.id })
        assertEquals(listOf("tmrw", "wk"), g.getValue(TaskGroup.ThisWeek).map { it.id })
        assertEquals(listOf("later"), g.getValue(TaskGroup.Later).map { it.id })
        assertEquals(listOf("none"), g.getValue(TaskGroup.NoDate).map { it.id })
        assertTrue(g.getValue(TaskGroup.Overdue).none { it.dueDate == null })
        assertTrue(taskGroups(listOf(task("n", null)), today).getValue(TaskGroup.Overdue).isEmpty())
    }

    // ------------------------------------------------------------ AC-5 history logic

    @Test
    fun test_AC5_monthRange_leap_and_normal_february_and_31_day_month() {
        assertEquals(d("2028-02-01") to d("2028-02-29"), monthRange(HistoryMonth(2028, 2)))
        assertEquals(d("2027-02-01") to d("2027-02-28"), monthRange(HistoryMonth(2027, 2)))
        assertEquals(d("2026-12-01") to d("2026-12-31"), monthRange(HistoryMonth(2026, 12)))
        assertEquals(d("2026-11-01") to d("2026-11-30"), monthRange(HistoryMonth(2026, 11)))
    }

    @Test
    fun test_AC5_previousMonths_newest_first_across_year_rollover() {
        val m = previousMonths(d("2026-02-15"), 4)
        assertEquals(listOf(HistoryMonth(2026, 2), HistoryMonth(2026, 1), HistoryMonth(2025, 12), HistoryMonth(2025, 11)), m)
        val twelve = previousMonths(d("2026-11-11"), 12)
        assertEquals(12, twelve.size)
        assertEquals(HistoryMonth(2026, 11), twelve.first())
        assertEquals(HistoryMonth(2025, 12), twelve.last())
        assertEquals(12, twelve.toSet().size)
    }

    @Test
    fun test_AC5_earlyOrLate() {
        assertEquals("early", earlyOrLate(d("2026-11-12"), d("2026-11-10")))
        assertEquals("late", earlyOrLate(d("2026-11-10"), d("2026-11-12")))
        assertNull(earlyOrLate(d("2026-11-10"), d("2026-11-10")))
        assertNull(earlyOrLate(null, d("2026-11-10")))
    }

    // ------------------------------------------------------------ AC-6 drafts and due chips

    @Test
    fun test_AC6_dueFromChip_all_four_chips() {
        val today = d("2026-11-30")
        assertNull(dueFromChip(DueChip.None, today, d("2026-12-25")))
        assertEquals(today, dueFromChip(DueChip.Today, today, null))
        assertEquals(d("2026-12-01"), dueFromChip(DueChip.Tomorrow, today, null))
        assertEquals(d("2026-12-25"), dueFromChip(DueChip.Pick, today, d("2026-12-25")))
        assertNull(dueFromChip(DueChip.Pick, today, null))
    }

    @Test
    fun test_AC6_DraftStore_save_load_clear_and_empty_is_null() {
        val mem = HashMap<String, String>()
        val s = DraftStore({ mem[it] }, { k, v -> mem[k] = v })
        assertNull(s.load("draft.journal"))
        s.save("draft.journal", """{"text":"hi"}""")
        assertEquals("""{"text":"hi"}""", s.load("draft.journal"))
        assertNull(s.load("draft.dream"))
        s.clear("draft.journal")
        assertNull(s.load("draft.journal"))
        mem["draft.dream"] = ""
        assertNull(s.load("draft.dream"))
    }

    @Test
    fun test_AC6_initialWriterText_existing_beats_draft_beats_empty() {
        assertEquals("old", initialWriterText("old", "draft"))
        assertEquals("draft", initialWriterText(null, "draft"))
        assertEquals("", initialWriterText(null, null))
        assertEquals("", initialWriterText("", "draft"))
    }

    @Test
    fun test_AC6_task_form_has_no_goal_text() {
        assertFalse(text("$ui/forms/TaskForm.kt").contains("goal"))
    }

    // ------------------------------------------------------------ AC-7 dream patterns removed

    @Test
    fun test_AC7_no_DREAM_PATTERNS_or_Find_patterns_in_commonMain() {
        val hits = ktFiles("shared/src/commonMain").filter { f ->
            val s = f.readText(); s.contains("DREAM_PATTERNS") || s.contains("Find patterns")
        }.map { it.name }
        assertTrue(hits.isEmpty(), "still present in $hits")
    }

    @Test
    fun test_AC7_DreamWallTest_allows_DreamDetail_and_DreamHistory() {
        val f = ktFiles("shared/src/jvmTest").single { it.name == "DreamWallTest.kt" }.readText()
        assertTrue(f.contains("ui/dream/DreamDetail.kt"))
        assertTrue(f.contains("ui/dream/DreamHistory.kt"))
    }

    // ------------------------------------------------------------ AC-8 screentime labels, device null = all

    private fun sess(id: String, device: String, app: String, startMs: Long, lenMs: Long) =
        Usage_session(id, 1, 0, 0, "SYNCED", device, app, null, startMs, startMs + lenMs, "2026-11-11")

    @Test
    fun test_AC8_dayTotals_null_device_is_all_devices_sum_then_floor() {
        val rule = App_rule("rule:EXE:code.exe", 1, 0, 0, "SYNCED", "EXE", "code.exe", "PRODUCTIVE")
        val rule2 = App_rule("rule:PACKAGE:com.code", 1, 0, 0, "SYNCED", "PACKAGE", "com.code", "PRODUCTIVE")
        val sessions = listOf(
            sess("a", DEVICE_LAPTOP, "code.exe", 0, 30_000),
            sess("b", DEVICE_PHONE, "com.code", 100_000, 30_000),
        )
        val dates = listOf("2026-11-11")
        val all = dayTotals(sessions, listOf(rule, rule2), null, dates).getValue("2026-11-11")
        val laptop = dayTotals(sessions, listOf(rule, rule2), DEVICE_LAPTOP, dates).getValue("2026-11-11")
        val phone = dayTotals(sessions, listOf(rule, rule2), DEVICE_PHONE, dates).getValue("2026-11-11")
        assertEquals(1L, all.getValue(Label.PRODUCTIVE)) // 30 s + 30 s = 1 min, not 0 + 0
        assertEquals(0L, laptop.getValue(Label.PRODUCTIVE))
        assertEquals(0L, phone.getValue(Label.PRODUCTIVE))
    }

    @Test
    fun test_AC8_topApps_and_unsortedApps_null_device_cover_both_devices() {
        val sessions = listOf(
            sess("a", DEVICE_LAPTOP, "code.exe", 0, 90_000),
            sess("b", DEVICE_PHONE, "com.chat", 100_000, 120_000),
        )
        val dates = listOf("2026-11-11")
        val top = topApps(sessions, null, dates)
        assertEquals(listOf("com.chat", "code.exe"), top.map { it.app })
        assertEquals(listOf("code.exe"), topApps(sessions, DEVICE_LAPTOP, dates).map { it.app })
        val unsorted = unsortedApps(sessions, emptyList(), null, dates)
        assertEquals(setOf("code.exe", "com.chat"), unsorted.map { it.app }.toSet())
        assertEquals(1, unsortedApps(sessions, emptyList(), DEVICE_PHONE, dates).size)
    }

    // ------------------------------------------------------------ date-aware check-in save (P15-A-10)

    @Test
    fun test_P15A10_saveCheckin_for_another_date_does_not_touch_todays_row(): Unit = runBlocking {
        val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
        val repo = Repository(LocalStore(db), now = { 1_794_312_000_000L }, today = { "2026-11-10" })
        val todayId = repo.saveCheckin(4, 4, 4, 4, "today")
        assertEquals("checkin:2026-11-10", todayId)
        val oldId = repo.saveCheckin("2026-11-05", 1, 2, 3, 2, "older")
        assertEquals("checkin:2026-11-05", oldId)
        val t = repo.checkins().single { it.id == "checkin:2026-11-10" }
        assertEquals("today", t.note)
        assertEquals(4L, t.mood)
        val o = repo.checkins().single { it.id == "checkin:2026-11-05" }
        assertEquals("older", o.note)
        assertEquals(1L, o.mood)
        assertNotNull(repo.checkinForDate("2026-11-05"))
        assertEquals(2, repo.checkinsIn(d("2026-11-01"), d("2026-11-30")).size)
        assertEquals(1, repo.checkinsIn(d("2026-11-10"), d("2026-11-10")).size)
    }

    @Test
    fun test_P15A09_completedTasksIn_covers_more_than_the_last_7_days(): Unit = runBlocking {
        val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
        // 2026-10-05 12:00 UTC is a Jerusalem Monday; "now" is a month later.
        val doneMs = 1_791_201_600_000L
        db.taskQueries.upsert(Task("t1", 1, 0, 0, "SYNCED", "old done", null, 1, doneMs, null))
        val repo = Repository(LocalStore(db), now = { 1_794_312_000_000L }, today = { "2026-11-10" })
        assertEquals(listOf("t1"), repo.completedTasksIn(d("2026-10-01"), d("2026-10-31")).map { it.id })
        assertTrue(repo.completedTasksIn(d("2026-11-01"), d("2026-11-30")).isEmpty())
    }

    // ------------------------------------------------------------ AC-10 release and checklists

    @Test
    fun test_AC10_release_1_13_0_versionCode_9() {
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC10_checklists_name_1_13_0_and_phone_checklist_exists() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        assertTrue(text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        val c = File(root, "ops/checklists/redesign-v2-phone.md")
        assertTrue(c.exists())
        assertTrue(c.readText().contains("SC-1"))
        assertTrue(c.readText().contains("SC-11"))
    }

    @Test
    fun test_AC10_no_stale_ported_identifiers_in_acceptance_tests() {
        val me = "Phase15AcceptanceTest.kt"
        val stale = Regex("""layoutModeFor\b|WIDE_MIN_DP|addTargetFor\(|usesLaptopDates|LogListsScreen\.kt""")
        val hits = ktFiles("tests/acceptance/src").filter { it.name != me }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                if (stale.containsMatchIn(l) && !l.trimStart().startsWith("//")) "${f.name}:${i + 1}: ${l.trim()}" else null
            }
        }
        assertTrue(hits.isEmpty(), "stale identifiers: $hits")
    }

    private fun stripComments(s: String): String =
        s.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("//.*$"), "") }

    @Test
    fun test_P15_A_18_R1_mainactivity_setContent_calls_App_and_BackHandler() {
        val src = text("androidApp/src/main/kotlin/dev/tim/assistant/MainActivity.kt")
        val start = src.indexOf("setContent {")
        assertTrue(start >= 0, "no setContent block")
        var i = src.indexOf('{', start); var depth = 0; var end = -1
        while (i < src.length) {
            if (src[i] == '{') depth++
            if (src[i] == '}') { depth--; if (depth == 0) { end = i; break } }
            i++
        }
        assertTrue(end > 0, "unbalanced setContent")
        val code = stripComments(src.substring(start, end + 1))
        assertTrue(code.contains("App("), "setContent must call App( outside comments")
        assertTrue(code.contains("BackHandler("), "setContent must call BackHandler( outside comments")
    }

    @Test
    fun test_P15_A_18_R1_no_literal_backslash_n_inside_line_comments() {
        val re = Regex("//.*\\\\n\\s+[A-Za-z_]")
        val hits = ktFiles("shared/src", "androidApp/src", "desktopApp/src").flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (re.containsMatchIn(l)) "${f.name}:${i + 1}" else null }
        }
        assertTrue(hits.isEmpty(), "literal \\n in comment: $hits")
    }

    @Test
    fun test_P15_A_18_R1_desktop_main_calls_App() {
        val code = stripComments(text("desktopApp/src/main/kotlin/main.kt"))
        assertTrue(code.contains("App("), "desktop main.kt must call App( outside comments")
    }
}
