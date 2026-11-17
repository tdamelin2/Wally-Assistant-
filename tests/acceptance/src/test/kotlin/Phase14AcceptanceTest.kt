import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SYNC_WORK_MINUTES
import dev.tim.assistant.data.SYNC_WORK_NAME
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.data.WorkOutcome
import dev.tim.assistant.data.launchSyncTimer
import dev.tim.assistant.data.workResultFor
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.db.Task
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.Hub
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.PendingAction
import dev.tim.assistant.ui.UndoBuffer
import dev.tim.assistant.ui.UndoKind
import dev.tim.assistant.ui.AddAction
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.hubOrder
import dev.tim.assistant.ui.formatDate
import dev.tim.assistant.ui.formatTime
import dev.tim.assistant.ui.hubChips
import dev.tim.assistant.ui.hubOf
import dev.tim.assistant.ui.initialChips
import dev.tim.assistant.ui.labelsFor
import dev.tim.assistant.ui.parseLaptopDate
import dev.tim.assistant.ui.pickerMillisToDate
import dev.tim.assistant.ui.selectChip
import dev.tim.assistant.ui.showsChipRow
import dev.tim.assistant.ui.syncBarText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** Phase 14 acceptance tests, written from the Plan contract (AC-1, AC-3, AC-4, AC-6..AC-8, AC-11..AC-16 automated parts). */
class Phase14AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()

    private fun ktFiles(vararg dirs: String): List<File> = dirs.flatMap { d ->
        File(root, d).walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    // ------------------------------------------------------------ AC-3 shell model

    @Test
    fun test_AC3_every_nav_tab_is_in_exactly_one_hub_with_1_6_4_1_5_chips() {
        assertEquals(17, NavTab.entries.size)
        for (tab in NavTab.entries) {
            assertEquals(1, hubChips.values.count { tab in it }, "tab $tab")
        }
        assertEquals(listOf(1, 6, 1, 4, 5), Hub.entries.map { hubChips.getValue(it).size })
        // Phase 15: hub order is Today, Log, Chat, Insights, Browse.
        assertEquals(listOf("Today", "Log", "Chat", "Insights", "Browse"), Hub.entries.map { it.label })
        assertEquals(17, hubChips.values.sumOf { it.size })
    }

    @Test
    fun test_AC3_hub_chip_lists_have_the_contract_order() {
        assertEquals(listOf(NavTab.Today), hubChips.getValue(Hub.Today))
        assertEquals(
            listOf(NavTab.Checkins, NavTab.Spending, NavTab.Journal, NavTab.Meals, NavTab.Tasks, NavTab.Dreams),
            hubChips.getValue(Hub.Log),
        )
        assertEquals(listOf(NavTab.Charts, NavTab.Health, NavTab.Screentime, NavTab.Goals), hubChips.getValue(Hub.Insights))
        assertEquals(listOf(NavTab.Chat), hubChips.getValue(Hub.Chat))
        assertEquals(
            listOf(NavTab.Files, NavTab.Study, NavTab.Mail, NavTab.Hardware, NavTab.Settings),
            hubChips.getValue(Hub.Browse),
        )
        assertEquals("Check-ins", NavTab.Checkins.label)
    }

    @Test
    fun test_AC3_hubOf_agrees_with_hubChips() {
        for ((hub, chips) in hubChips) for (c in chips) assertEquals(hub, hubOf(c), "chip $c")
        assertEquals(Hub.Browse, hubOf(NavTab.Settings))
        assertEquals(Hub.Insights, hubOf(NavTab.Goals))
        assertEquals(Hub.Log, hubOf(NavTab.Dreams))
    }

    @Test
    fun test_AC3_addActionFor_mapping_and_nulls() {
        // Phase 15: addTargetFor became addActionFor (Today opens the picker; Goals/Files/Study/Mail have their own actions).
        assertEquals(AddAction.Picker, addActionFor(NavTab.Today))
        assertEquals(AddAction.Form(FormTarget.Task(null)), addActionFor(NavTab.Tasks))
        assertEquals(AddAction.Form(FormTarget.Checkin(null)), addActionFor(NavTab.Checkins))
        assertEquals(AddAction.Form(FormTarget.Spending(null)), addActionFor(NavTab.Spending))
        assertEquals(AddAction.Form(FormTarget.Journal(null)), addActionFor(NavTab.Journal))
        assertEquals(AddAction.Form(FormTarget.Meal(null)), addActionFor(NavTab.Meals))
        assertEquals(AddAction.Form(FormTarget.Dream(null)), addActionFor(NavTab.Dreams))
        for (t in listOf(NavTab.Charts, NavTab.Settings, NavTab.Chat, NavTab.Health, NavTab.Screentime, NavTab.Hardware)) {
            assertNull(addActionFor(t), "tab $t")
        }
    }
    @Test
    fun test_AC3_selectChip_remembers_the_last_chip_per_hub() {
        var m = initialChips()
        assertEquals(Hub.entries.toSet(), m.keys)
        for (h in Hub.entries) assertEquals(hubChips.getValue(h).first(), m.getValue(h))
        m = selectChip(m, NavTab.Health)
        assertEquals(NavTab.Health, m.getValue(Hub.Insights))
        // switching to the Log hub and back keeps Insights on Health
        m = selectChip(m, NavTab.Meals)
        assertEquals(NavTab.Meals, m.getValue(Hub.Log))
        assertEquals(NavTab.Health, m.getValue(Hub.Insights))
        // the input map is not changed
        val before = initialChips()
        selectChip(before, NavTab.Dreams)
        assertEquals(initialChips(), before)
    }

    @Test
    fun test_AC3_showsChipRow_only_for_hubs_with_two_or_more_chips() {
        assertFalse(showsChipRow(Hub.Today))
        assertFalse(showsChipRow(Hub.Chat))
        assertTrue(showsChipRow(Hub.Log))
        assertTrue(showsChipRow(Hub.Insights))
        assertTrue(showsChipRow(Hub.Browse))
    }

    @Test
    fun test_AC3_layout_mode_follows_device() {
        // Phase 15: the 600 dp width switch is retired; layout follows the device.
        assertEquals(dev.tim.assistant.ui.LayoutMode.Narrow, dev.tim.assistant.ui.layoutModeForDevice(false))
        assertEquals(dev.tim.assistant.ui.LayoutMode.Wide, dev.tim.assistant.ui.layoutModeForDevice(true))
        assertEquals(Hub.entries, hubOrder)
    }
    // ------------------------------------------------------------ AC-8 labels

    @Test
    fun test_AC8_labelsFor_has_no_case_insensitive_duplicates_for_every_hub_and_chip() {
        for ((hub, chips) in hubChips) for (chip in chips) {
            val labels = labelsFor(hub, chip)
            assertTrue(labels.isNotEmpty(), "$hub/$chip has labels")
            val dups = labels.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys
            assertTrue(dups.isEmpty(), "$hub/$chip repeats $dups")
            // the five drawn hub labels are always on screen
            for (h in Hub.entries) assertTrue(labels.any { it.equals(h.label, ignoreCase = true) }, "$hub/$chip lacks hub label ${h.label}")
        }
    }

    @Test
    fun test_AC8_redesign_checklist_exists() {
        val f = File(root, "ops/checklists/redesign.md")
        assertTrue(f.exists())
        assertTrue(f.readText().isNotBlank())
    }

    // ------------------------------------------------------------ AC-1 / AC-4 / AC-6 / AC-12 source searches

    @Test
    fun test_AC1_raw_colours_only_in_Theme_kt_and_chart_series() {
        val pattern = Regex("""Color\.[A-Z]|Color\(""")
        val hits = ktFiles("shared/src", "desktopApp/src", "androidApp/src").flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (pattern.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.filterNot { it.startsWith("Theme.kt:") }
        val unexpected = hits.filterNot { it.startsWith("Charts.kt:") }
        assertTrue(unexpected.isEmpty(), "raw colours outside Theme.kt/Charts.kt: $unexpected")
        assertTrue(hits.size <= 2, "only the chart series colours remain: $hits")
        val theme = text("shared/src/commonMain/kotlin/dev/tim/assistant/ui/Theme.kt")
        // Phase 15: the theme is light only (no dark scheme, no system-dark switch).
        assertTrue(theme.contains("lightColorScheme") && !theme.contains("darkColorScheme"))
        assertFalse(theme.contains("isSystemInDarkTheme"))
    }

    @Test
    fun test_AC4_old_shell_helpers_are_gone() {
        val pattern = Regex("""AddEntry|moreSheetTabs|settingsOpen|NavTab\.Log\b|NavTab\.Dream\b|Text\("More"\)|contentDescription = "More"""")
        val hits = ktFiles("shared/src").flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (pattern.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }
        assertTrue(hits.isEmpty(), "old helpers remain: $hits")
        val hits2 = ktFiles("shared/src", "desktopApp/src", "androidApp/src").flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                if (Regex("""WIDE_TABS|visibleTabs|PRIMARY_TABS|moreTabs|navTabsFor|phoneBarTabs|destinationsFor|showsAddButton""").containsMatchIn(l)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue(hits2.isEmpty(), "old helpers remain: $hits2")
    }

    @Test
    fun test_AC6_today_screen_has_no_mail_or_events() {
        val t = text("shared/src/commonMain/kotlin/dev/tim/assistant/ui/TodayScreen.kt")
        for (bad in listOf("Unread mail", "Next 7 days")) assertFalse(t.contains(bad), bad)
        assertFalse(Regex("""unreadMail\(|calendarEvents\(""").containsMatchIn(t))
    }

    @Test
    fun test_AC12_every_lazy_items_call_has_a_key() {
        val bad = mutableListOf<String>()
        for (f in ktFiles("shared/src/commonMain")) {
            val s = f.readText()
            for (m in Regex("""\bitems\(""").findAll(s)) {
                // ignore declarations such as "fun JsonObject.items("
                val lineStart = s.lastIndexOf('\n', m.range.first) + 1
                if (s.substring(lineStart, m.range.first).contains("fun ")) continue
                var depth = 0
                var end = m.range.last
                while (end < s.length) {
                    when (s[end]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
                    end++
                }
                val call = s.substring(m.range.first, minOf(end + 1, s.length))
                if (!call.contains("key =")) bad += "${f.name}: ${call.take(80)}"
            }
        }
        assertTrue(bad.isEmpty(), "items( without key: $bad")
    }

    // ------------------------------------------------------------ AC-7 chat sender

    @Test
    fun test_AC7_chat_mail_line_has_hebrew_sender_and_subject() {
        val db = memDb()
        val sender = "דנה <dana@example.com>"
        val noon = 1_794_312_000_000L // 2026-11-10T12:00Z
        db.mail_itemQueries.upsert(Mail_item("mail:1", 1, 0, 0, "SYNCED", "g1", sender, "Quarterly plan", "snip", noon))
        val ctx = ChatContext.build(db, "2026-11-10")
        assertTrue(ctx.contains("Unread mail"), ctx)
        val line = ctx.lines().single { "Quarterly plan" in it }
        assertTrue(sender in line, line)
        assertTrue("$sender: Quarterly plan" in line, line)
    }

    // ------------------------------------------------------------ AC-11 undo

    private fun act(token: Long, id: String = "t$token", kind: UndoKind = UndoKind.Delete) =
        PendingAction(token, "task", id, kind, if (kind == UndoKind.Complete) true else null)

    @Test
    fun test_AC11_undo_cancels_and_expire_commits_once() {
        val b = UndoBuffer()
        assertNull(b.hold(act(1)))
        assertEquals(setOf("task" to "t1"), b.hidden)
        assertTrue(b.undo(1))
        assertTrue(b.hidden.isEmpty())
        assertNull(b.expire(1))
        assertNull(b.hold(act(2)))
        assertEquals(act(2), b.expire(2))
        assertNull(b.expire(2))
        assertFalse(b.undo(2))
    }

    @Test
    fun test_AC11_second_hold_returns_the_first_and_stale_tokens_are_ignored() {
        val b = UndoBuffer()
        b.hold(act(1))
        assertEquals(act(1), b.hold(act(2, kind = UndoKind.Complete)))
        assertFalse(b.undo(1))
        assertNull(b.expire(1))
        assertEquals(setOf("task" to "t2"), b.hidden)
        assertEquals(act(2, kind = UndoKind.Complete), b.expire(2))
    }

    @Test
    fun test_AC11_held_delete_leaves_the_row_and_creates_no_pending_row() {
        val db = memDb()
        db.taskQueries.upsert(Task("task-a", 10, 0, 5, "SYNCED", "keep me", "2026-11-20", 0, null, "goal-1"))
        val b = UndoBuffer()
        b.hold(act(1, id = "task-a"))
        val row = db.taskQueries.selectById("task-a").executeAsOne()
        assertEquals(0L, row.deleted)
        assertEquals("SYNCED", row.syncState)
        assertTrue(db.taskQueries.selectPending().executeAsList().isEmpty())
        assertTrue(b.undo(1))
        assertEquals("SYNCED", db.taskQueries.selectById("task-a").executeAsOne().syncState)
    }

    @Test
    fun test_AC11_setTaskDone_changes_only_done_fields_and_noops_when_missing_deleted_or_same(): Unit = runBlocking {
        val db = memDb()
        var t = 1_700_000_000_000L
        val repo = Repository(LocalStore(db), now = { t }, today = { "2026-11-10" })
        val id = repo.saveTask(null, "write report", "2026-11-20", false, "goal-1")
        db.taskQueries.upsert(db.taskQueries.selectById(id).executeAsOne().copy(syncState = "SYNCED"))
        t += 5_000
        assertTrue(repo.setTaskDone(id, true))
        val r = db.taskQueries.selectById(id).executeAsOne()
        assertEquals("write report", r.title)
        assertEquals("2026-11-20", r.dueDate)
        assertEquals("goal-1", r.goalId)
        assertEquals(1L, r.done)
        assertNotNull(r.doneAt)
        assertEquals("PENDING", r.syncState)
        assertEquals(t, r.updatedAt)
        // already in that state: no-op
        t += 5_000
        assertFalse(repo.setTaskDone(id, true))
        assertEquals(t - 5_000, db.taskQueries.selectById(id).executeAsOne().updatedAt)
        // back to not done works
        assertTrue(repo.setTaskDone(id, false))
        assertEquals(0L, db.taskQueries.selectById(id).executeAsOne().done)
        // missing and deleted
        assertFalse(repo.setTaskDone("nope", true))
        repo.deleteTask(id)
        assertFalse(repo.setTaskDone(id, true))
        val d = db.taskQueries.selectById(id).executeAsOne()
        assertEquals(1L, d.deleted)
        assertEquals(0L, d.done)
    }

    // ------------------------------------------------------------ AC-13 sync bar

    @Test
    fun test_AC13_syncBarText() {
        val now = 10_000_000L
        assertEquals("Synced just now", syncBarText(SyncStatus.Synced(now - 30_000), now))
        assertEquals("Synced just now", syncBarText(SyncStatus.Synced(now - 59_999), now))
        assertEquals("Synced 5 min ago", syncBarText(SyncStatus.Synced(now - 5 * 60_000), now))
        assertEquals("Synced 1 min ago", syncBarText(SyncStatus.Synced(now - 60_000), now))
        assertEquals("Offline — 3 waiting", syncBarText(SyncStatus.Offline(3), now))
        assertTrue(syncBarText(SyncStatus.WrongToken, now).startsWith("Wrong token"))
        assertTrue(syncBarText(SyncStatus.NotConfigured, now).startsWith("Not set up"))
    }

    // ------------------------------------------------------------ AC-14 timer and worker

    @Test
    fun test_AC14_timer_syncs_after_each_wait_and_stops_on_cancel(): Unit = runBlocking {
        val waits = mutableListOf<Duration>()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var syncs = 0
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val job = scope.launchSyncTimer(wait = { waits += it; gate.receive() }, sync = { syncs++ })
        assertEquals(0, syncs)
        gate.send(Unit)
        assertEquals(1, syncs)
        gate.send(Unit)
        assertEquals(2, syncs)
        assertEquals(listOf(10.minutes, 10.minutes, 10.minutes), waits)
        job.cancel()
        gate.send(Unit)
        assertEquals(2, syncs)
        assertTrue(job.isCancelled)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun test_AC14_a_throwing_sync_does_not_end_the_loop(): Unit = runBlocking {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var n = 0
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val job = scope.launchSyncTimer(wait = { gate.receive() }, sync = { n++; if (n == 1) error("boom") })
        gate.send(Unit)
        gate.send(Unit)
        assertEquals(2, n)
        assertTrue(job.isActive)
        job.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun test_AC14_workResultFor_and_work_constants() {
        assertEquals(WorkOutcome.Success, workResultFor(SyncStatus.Synced(1)))
        assertEquals(WorkOutcome.Retry, workResultFor(SyncStatus.Offline(2)))
        assertEquals(WorkOutcome.Retry, workResultFor(SyncStatus.Syncing))
        assertEquals(WorkOutcome.Failure, workResultFor(SyncStatus.WrongToken))
        assertEquals(WorkOutcome.Failure, workResultFor(SyncStatus.NotConfigured))
        assertEquals("wally-sync", SYNC_WORK_NAME)
        assertEquals(15L, SYNC_WORK_MINUTES)
    }

    @Test
    fun test_AC14_android_wiring_sources() {
        val catalog = text("gradle/libs.versions.toml")
        assertTrue(catalog.contains("androidx-work = \"2.12.0\""))
        assertTrue(text("androidApp/src/main/AndroidManifest.xml").contains("android:name=\".WallyApp\""))
        assertTrue(text("androidApp/src/main/kotlin/dev/tim/assistant/SyncWorker.kt").contains("sync(readHealth = false)"))
        val wally = text("androidApp/src/main/kotlin/dev/tim/assistant/WallyApp.kt")
        assertTrue(wally.contains("enqueueUniquePeriodicWork"))
        assertTrue(wally.contains("SYNC_WORK_NAME"))
    }

    // ------------------------------------------------------------ AC-15 dates

    @Test
    fun test_AC15_parseLaptopDate_accepts_only_real_MM_dd_yyyy() {
        assertEquals(LocalDate(2026, 11, 10), parseLaptopDate("11-10-2026"))
        assertEquals(LocalDate(2024, 2, 29), parseLaptopDate("02-29-2024"))
        for (bad in listOf("13-01-2026", "02-30-2026", "2026-11-10", "1-10-2026", "", "11-10-26", "11-10-2026x", "02-29-2025")) {
            assertNull(parseLaptopDate(bad), "should reject '$bad'")
        }
    }

    @Test
    fun test_AC15_formatDate_laptop_round_trips_and_phone_differs_from_iso_free_laptop_form() {
        val d = LocalDate(2026, 11, 10)
        assertEquals("11-10-2026", formatDate(d, laptop = true))
        assertEquals(d, parseLaptopDate(formatDate(d, laptop = true)))
        assertEquals("01-05-2027", formatDate(LocalDate(2027, 1, 5), laptop = true))
        // the desktop (jvm) build uses the laptop style by default
        assertEquals("11-10-2026", formatDate(d))
        assertTrue(formatDate(d, laptop = false).isNotBlank())
    }

    @Test
    fun test_AC15_pickerMillis_is_decoded_in_utc_and_time_is_24h() {
        assertEquals(LocalDate(2026, 11, 10), pickerMillisToDate(1_794_268_800_000L)) // 2026-11-10T00:00Z
        assertEquals(LocalDate(2026, 11, 10), pickerMillisToDate(1_794_268_800_000L + 86_399_000L))
        assertEquals(LocalDate(1970, 1, 1), pickerMillisToDate(0))
        assertEquals("07:05", formatTime(7, 5))
        assertEquals("23:59", formatTime(23, 59))
        assertEquals("00:00", formatTime(0, 0))
    }

    // ------------------------------------------------------------ AC-16 release

    @Test
    fun test_AC16_release_1_12_0_versionCode_8_and_same_identity() {
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
        assertTrue(a.contains("dev.tim.assistant"))
    }

    @Test
    fun test_AC16_active_checklists_name_1_12_0() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        // w12 and w13-calendar are history lines and stay at 1.12.0.
        for (f in listOf("w12", "w13-calendar")) assertTrue(text("ops/checklists/$f.md").contains("1.12.0"), "$f.md names 1.12.0")
        assertTrue(text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        val r = text("ops/checklists/redesign.md")
        assertTrue(r.contains("Fluid UX"))
        assertTrue(r.contains("SC-1") && r.contains("SC-2") && r.contains("SC-3") && r.contains("SC-4"))
    }
}
