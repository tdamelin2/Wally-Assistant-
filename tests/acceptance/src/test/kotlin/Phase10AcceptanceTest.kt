import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Listen
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.desktop.LaptopTracker
import dev.tim.assistant.desktop.Sample
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.validateRow
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.hubChips
import dev.tim.assistant.ui.screentime.ScreentimeRange
import dev.tim.assistant.ui.screentime.datesFor
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.UsageEvent
import dev.tim.assistant.usage.UsageEventType
import dev.tim.assistant.usage.buildPhoneSessions
import dev.tim.assistant.usage.dayTotals
import dev.tim.assistant.usage.label
import dev.tim.assistant.usage.last7Dates
import dev.tim.assistant.usage.normalizePattern
import dev.tim.assistant.usage.ruleId
import dev.tim.assistant.usage.topApps
import dev.tim.assistant.usage.unsortedApps
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/** Phase 10 acceptance tests, written from the AC list and the Plan contract (manual/device parts live in ops/checklists/w10-screentime.md). */
class Phase10AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()

    private fun obj(vararg p: Pair<String, Any?>) = JsonObject(
        p.associate { (k, v) ->
            k to when (v) { null -> JsonNull; is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as Number) }
        },
    )

    // ------------------------------------------------------------ AC-2 phone sessions

    private val t0 = 1_762_000_000_000L
    private fun ev(pkg: String, type: UsageEventType, sec: Long) = UsageEvent(pkg, type, t0 + sec * 1000)
    private fun res(pkg: String, sec: Long) = ev(pkg, UsageEventType.RESUMED, sec)
    private fun pau(pkg: String, sec: Long) = ev(pkg, UsageEventType.PAUSED, sec)
    private fun off(sec: Long) = ev("", UsageEventType.SCREEN_OFF, sec)

    @Test
    fun test_AC2_resumed_then_paused_makes_one_session_with_stable_id() {
        val r = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 60)), t0 + 600_000)
        val s = r.sessions.single()
        assertEquals("use:phone:com.a:$t0", s.id)
        assertEquals("PHONE", s.device)
        assertEquals("com.a", s.app)
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 60_000, s.endAt)
        assertNull(r.openStartAt)
    }

    @Test
    fun test_AC2_switching_apps_closes_the_previous_one() {
        val r = buildPhoneSessions(listOf(res("com.a", 0), res("com.b", 60), pau("com.b", 120)), t0 + 600_000)
        assertEquals(listOf("com.a", "com.b"), r.sessions.map { it.app })
        assertEquals(t0 + 60_000, r.sessions[0].endAt)
        assertEquals(t0 + 60_000, r.sessions[1].startAt)
        assertEquals(t0 + 120_000, r.sessions[1].endAt)
    }

    @Test
    fun test_AC2_unclosed_session_ends_at_until_and_reports_open_start() {
        val until = t0 + 300_000
        val r = buildPhoneSessions(listOf(res("com.a", 0)), until)
        val s = r.sessions.single()
        assertEquals(until, s.endAt)
        assertEquals(t0, r.openStartAt)
    }

    @Test
    fun test_AC2_events_at_or_after_until_are_ignored() {
        val until = t0 + 100_000
        val r = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 100), res("com.b", 150)), until)
        assertEquals(listOf("com.a"), r.sessions.map { it.app })
        assertEquals(until, r.sessions.single().endAt.coerceAtMost(until))
    }

    @Test
    fun test_AC2_sessions_under_5_seconds_are_dropped() {
        val r = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 4)), t0 + 600_000)
        assertTrue(r.sessions.isEmpty())
        val ok = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 5)), t0 + 600_000)
        assertEquals(1, ok.sessions.size)
    }

    @Test
    fun test_AC2_short_open_session_is_not_written_but_open_start_is_reported() {
        val r = buildPhoneSessions(listOf(res("com.a", 0)), t0 + 3_000)
        assertTrue(r.sessions.isEmpty())
        assertEquals(t0, r.openStartAt)
    }

    @Test
    fun test_AC2_same_input_twice_gives_same_ids_and_rows() {
        val events = listOf(res("com.a", 0), pau("com.a", 30), res("com.b", 40), res("com.c", 90), off(200))
        val until = t0 + 900_000
        val one = buildPhoneSessions(events, until)
        val two = buildPhoneSessions(events, until)
        assertEquals(one, two)
        assertEquals(one.sessions.map { it.id }, two.sessions.map { it.id })
        assertEquals(3, one.sessions.size)
    }

    @Test
    fun test_AC2_session_seen_across_two_reads_keeps_its_id_and_grows() {
        val early = buildPhoneSessions(listOf(res("com.a", 0)), t0 + 60_000)
        val later = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 300)), t0 + 600_000)
        assertEquals(early.sessions.single().id, later.sessions.single().id)
        assertTrue(later.sessions.single().endAt > early.sessions.single().endAt)
    }

    @Test
    fun test_AC2_orphan_paused_and_paused_of_other_app_do_nothing() {
        val r = buildPhoneSessions(listOf(pau("com.x", 0), res("com.a", 10), pau("com.b", 20), pau("com.a", 70)), t0 + 600_000)
        val s = r.sessions.single()
        assertEquals("com.a", s.app)
        assertEquals(t0 + 10_000, s.startAt)
        assertEquals(t0 + 70_000, s.endAt)
    }

    @Test
    fun test_AC2_screen_off_closes_the_open_session() {
        val r = buildPhoneSessions(listOf(res("com.a", 0), off(40)), t0 + 900_000)
        assertEquals(t0 + 40_000, r.sessions.single().endAt)
        assertNull(r.openStartAt)
    }

    @Test
    fun test_AC2_same_app_sessions_within_2_seconds_merge_and_keep_first_start() {
        val merged = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 30), res("com.a", 32), pau("com.a", 60)), t0 + 900_000)
        val s = merged.sessions.single()
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 60_000, s.endAt)
        val apart = buildPhoneSessions(listOf(res("com.a", 0), pau("com.a", 30), res("com.a", 33), pau("com.a", 60)), t0 + 900_000)
        assertEquals(2, apart.sessions.size)
    }

    @Test
    fun test_AC2_unsorted_input_is_ordered_by_time() {
        val r = buildPhoneSessions(listOf(pau("com.a", 60), res("com.a", 0)), t0 + 900_000)
        assertEquals(t0 + 60_000, r.sessions.single().endAt)
    }

    @Test
    fun test_AC2_local_date_is_the_jerusalem_date_of_the_start() {
        val start = Instant.parse("2026-11-09T22:30:00Z").toEpochMilliseconds() // 00:30 on 10 Nov in Jerusalem
        val r = buildPhoneSessions(
            listOf(UsageEvent("com.a", UsageEventType.RESUMED, start), UsageEvent("com.a", UsageEventType.PAUSED, start + 60_000)),
            start + 600_000,
        )
        assertEquals("2026-11-10", r.sessions.single().localDate)
    }

    // ------------------------------------------------------------ AC-3 laptop tracker

    private fun smp(exe: String, title: String, sec: Long, idleMs: Long = 0) = Sample(exe, title, idleMs, t0 + sec * 1000)

    private fun feed(tr: LaptopTracker, samples: List<Sample>) = samples.flatMap { tr.onSample(it) }

    @Test
    fun test_AC3_steady_samples_make_one_session_closed_by_flush() {
        val tr = LaptopTracker()
        val closed = feed(tr, (0..30 step 5).map { smp("code.exe", "a.kt", it.toLong()) })
        assertTrue(closed.isEmpty())
        assertNotNull(tr.open())
        val out = tr.flush()
        val s = out.single()
        assertEquals("use:laptop:$t0", s.id)
        assertEquals("code.exe", s.exe)
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 30_000, s.endAt)
        assertNull(tr.open())
    }

    @Test
    fun test_AC3_title_change_splits_the_session() {
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("chrome.exe", "A", 0), smp("chrome.exe", "A", 5), smp("chrome.exe", "A", 10), smp("chrome.exe", "B", 15)))
        val s = closed.single()
        assertEquals("A", s.title)
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 15_000, s.endAt)
        assertEquals("B", tr.open()!!.title)
    }

    @Test
    fun test_AC3_program_change_splits_and_exe_is_lowercased() {
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("Code.EXE", "x", 0), smp("Code.EXE", "x", 10), smp("chrome.exe", "y", 20)))
        assertEquals("code.exe", closed.single().exe)
    }

    @Test
    fun test_AC3_long_idle_ends_session_at_last_input_and_opens_nothing() {
        val tr = LaptopTracker()
        val samples = (0..60 step 5).map { smp("code.exe", "a", it.toLong()) } +
            (65..185 step 5).map { smp("code.exe", "a", it.toLong(), idleMs = (it - 60) * 1000L) }
        val closed = feed(tr, samples)
        val s = closed.single()
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 60_000, s.endAt)
        assertNull(tr.open())
        assertTrue(tr.flush().isEmpty())
    }

    @Test
    fun test_AC3_gap_over_30_seconds_closes_at_previous_sample() {
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("code.exe", "a", 0), smp("code.exe", "a", 5), smp("code.exe", "a", 10), smp("code.exe", "a", 100)))
        val s = closed.single()
        assertEquals(t0 + 10_000, s.endAt)
        assertEquals(t0 + 100_000, tr.open()!!.startAt)
    }

    @Test
    fun test_AC3_sessions_under_5_seconds_are_dropped() {
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("code.exe", "a", 0), smp("code.exe", "a", 3), smp("code.exe", "b", 4)))
        assertTrue(closed.isEmpty())
        tr.flush()
        val tr2 = LaptopTracker()
        tr2.onSample(smp("code.exe", "a", 0)); tr2.onSample(smp("code.exe", "a", 3))
        assertTrue(tr2.flush().isEmpty())
    }

    @Test
    fun test_AC3_title_is_compared_in_full_but_stored_at_200_chars() {
        val base = "x".repeat(200)
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("a.exe", base + "1", 0), smp("a.exe", base + "1", 10), smp("a.exe", base + "2", 20)))
        assertEquals(1, closed.size, "titles differing after char 200 are different sessions")
        assertEquals(200, closed.single().title!!.length)
    }

    @Test
    fun test_AC3_unavailable_foreground_closes_at_last_valid_sample() {
        val tr = LaptopTracker()
        val closed = feed(tr, listOf(smp("a.exe", "t", 0), smp("a.exe", "t", 10), smp("unknown", "", 15)))
        assertEquals(t0 + 10_000, closed.single().endAt)
        assertNull(tr.open())
    }

    // ------------------------------------------------------------ AC-4 labels

    private fun sess(device: String, app: String, title: String?, startAt: Long, endAt: Long, localDate: String, id: String = "$device:$app:$startAt") =
        Usage_session(id, 1, 0, 0, "SYNCED", device, app, title, startAt, endAt, localDate)

    private fun rule(matchType: String, pattern: String, label: String, deleted: Long = 0) =
        App_rule(ruleId(matchType, pattern), 1, deleted, 0, "SYNCED", matchType, pattern, label)

    @Test
    fun test_AC4_rule_id_and_pattern_normalising() {
        assertEquals("rule:EXE:code.exe", ruleId("EXE", "code.exe"))
        assertEquals("code.exe", normalizePattern("  Code.EXE "))
    }

    @Test
    fun test_AC4_exe_rule_labels_laptop_and_package_rule_labels_phone() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"), rule("PACKAGE", "com.game", "UNPRODUCTIVE"))
        assertEquals(Label.PRODUCTIVE, label(sess("LAPTOP", "code.exe", "x", 0, 60_000, "2026-11-10"), rules))
        assertEquals(Label.UNPRODUCTIVE, label(sess("PHONE", "com.game", null, 0, 60_000, "2026-11-10"), rules))
        assertEquals(Label.UNSORTED, label(sess("PHONE", "code.exe", null, 0, 60_000, "2026-11-10"), rules), "EXE rule does not match a phone app")
        assertEquals(Label.UNSORTED, label(sess("LAPTOP", "com.game", "x", 0, 60_000, "2026-11-10"), rules), "PACKAGE rule does not match a laptop app")
        assertEquals(Label.UNSORTED, label(sess("LAPTOP", "other.exe", "x", 0, 60_000, "2026-11-10"), rules))
    }

    @Test
    fun test_AC4_title_rule_beats_exe_rule() {
        val rules = listOf(rule("EXE", "chrome.exe", "NEUTRAL"), rule("TITLE_CONTAINS", "youtube", "UNPRODUCTIVE"))
        assertEquals(Label.UNPRODUCTIVE, label(sess("LAPTOP", "chrome.exe", "Cats - YouTube", 0, 60_000, "2026-11-10"), rules))
        assertEquals(Label.NEUTRAL, label(sess("LAPTOP", "chrome.exe", "Docs", 0, 60_000, "2026-11-10"), rules))
    }

    @Test
    fun test_AC4_longest_title_pattern_wins_and_tie_goes_to_lowest_rule_id() {
        val s = sess("LAPTOP", "chrome.exe", "Study Notes Video", 0, 60_000, "2026-11-10")
        val longer = listOf(rule("TITLE_CONTAINS", "study", "PRODUCTIVE"), rule("TITLE_CONTAINS", "notes video", "UNPRODUCTIVE"))
        assertEquals(Label.UNPRODUCTIVE, label(s, longer))
        val tie = listOf(rule("TITLE_CONTAINS", "video", "UNPRODUCTIVE"), rule("TITLE_CONTAINS", "study", "PRODUCTIVE"))
        // equal length (5): rule ids "rule:TITLE_CONTAINS:study" < "rule:TITLE_CONTAINS:video"
        assertEquals(Label.PRODUCTIVE, label(s, tie))
    }

    @Test
    fun test_AC4_deleted_rule_is_ignored() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE", deleted = 1))
        assertEquals(Label.UNSORTED, label(sess("LAPTOP", "code.exe", "x", 0, 60_000, "2026-11-10"), rules))
    }

    @Test
    fun test_AC4_totals_floor_the_summed_milliseconds() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val a = sess("LAPTOP", "code.exe", "a", 0, 90_000, "2026-11-10")
        val b = sess("LAPTOP", "code.exe", "b", 100_000, 190_000, "2026-11-10")
        val totals = dayTotals(listOf(a, b), rules, "LAPTOP", listOf("2026-11-10"))
        assertEquals(3L, totals.getValue("2026-11-10").getValue(Label.PRODUCTIVE), "90s + 90s = 3 min, not 1 + 1")
    }

    @Test
    fun test_AC4_midnight_session_counts_whole_on_its_start_date() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val start = Instant.parse("2026-11-09T21:50:00Z").toEpochMilliseconds() // 23:50 on 9 Nov in Jerusalem
        val s = sess("LAPTOP", "code.exe", "a", start, start + 30 * 60_000, "2026-11-09")
        val totals = dayTotals(listOf(s), rules, "LAPTOP", listOf("2026-11-09", "2026-11-10"))
        assertEquals(30L, totals.getValue("2026-11-09").getValue(Label.PRODUCTIVE))
        assertEquals(0L, totals.getValue("2026-11-10").getValue(Label.PRODUCTIVE))
    }

    @Test
    fun test_AC4_totals_split_by_label_device_and_ignore_deleted_sessions() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"), rule("EXE", "game.exe", "UNPRODUCTIVE"))
        val ss = listOf(
            sess("LAPTOP", "code.exe", null, 0, 600_000, "2026-11-10"),
            sess("LAPTOP", "game.exe", null, 1_000_000, 1_300_000, "2026-11-10"),
            sess("LAPTOP", "misc.exe", null, 2_000_000, 2_120_000, "2026-11-10"),
            sess("PHONE", "com.x", null, 0, 6_000_000, "2026-11-10"),
            sess("LAPTOP", "code.exe", null, 5_000_000, 6_000_000, "2026-11-10").let {
                Usage_session(it.id + "-del", 1, 1, 0, "SYNCED", it.device, it.app, it.title, it.startAt, it.endAt, it.localDate)
            },
        )
        val day = dayTotals(ss, rules, "LAPTOP", listOf("2026-11-10")).getValue("2026-11-10")
        assertEquals(10L, day.getValue(Label.PRODUCTIVE))
        assertEquals(5L, day.getValue(Label.UNPRODUCTIVE))
        assertEquals(2L, day.getValue(Label.UNSORTED))
        assertEquals(0L, day.getValue(Label.NEUTRAL))
    }

    @Test
    fun test_AC4_top_apps_are_limited_to_10_longest_first_ties_by_name() {
        val ss = (1..12).map { n -> sess("LAPTOP", "app%02d.exe".format(n), null, 0, n * 60_000L, "2026-11-10") } +
            sess("LAPTOP", "aaa.exe", null, 0, 12 * 60_000L, "2026-11-10")
        val top = topApps(ss, "LAPTOP", listOf("2026-11-10"))
        assertEquals(10, top.size)
        assertEquals(listOf("aaa.exe", "app12.exe", "app11.exe"), top.take(3).map { it.app })
        assertTrue(top.zipWithNext().all { (x, y) -> x.ms >= y.ms })
        assertEquals(3, topApps(ss, "LAPTOP", listOf("2026-11-10"), limit = 3).size)
    }

    @Test
    fun test_AC4_unsorted_apps_lists_only_apps_without_a_rule() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val ss = listOf(
            sess("LAPTOP", "code.exe", null, 0, 60_000, "2026-11-10"),
            sess("LAPTOP", "new.exe", null, 0, 120_000, "2026-11-10"),
            sess("LAPTOP", "new.exe", null, 200_000, 260_000, "2026-11-10"),
        )
        val u = unsortedApps(ss, rules, "LAPTOP", listOf("2026-11-10"))
        assertEquals(listOf("new.exe"), u.map { it.app })
        assertEquals(180_000L, u.single().ms)
    }

    @Test
    fun test_AC4_last7dates_is_today_and_six_prior_days() {
        val d = last7Dates("2026-11-10")
        assertEquals(7, d.size)
        assertEquals("2026-11-10", d.first())
        assertEquals(setOf("2026-11-10", "2026-11-09", "2026-11-08", "2026-11-07", "2026-11-06", "2026-11-05", "2026-11-04"), d.toSet())
        assertTrue("2026-02-24" in last7Dates("2026-03-02"))
    }

    @Test
    fun test_AC4_screentime_range_dates() {
        assertEquals(listOf("2026-11-10"), datesFor(ScreentimeRange.Today, "2026-11-10"))
        assertEquals(7, datesFor(ScreentimeRange.Week, "2026-11-10").size)
    }

    // ------------------------------------------------------------ navigation

    @Test
    fun test_AC4_navigation_has_screentime_in_the_insights_hub() {
        // Phase 14 (P14-A-02): 12 old tabs became 17 NavTabs in 5 hubs; Screentime is an Insights chip.
        assertEquals(17, NavTab.entries.size)
        assertTrue(NavTab.Screentime in hubChips.getValue(dev.tim.assistant.ui.Hub.Insights))
    }

    @Test
    fun test_AC4_add_target_is_null_on_screentime_and_set_on_today() {
        assertEquals(null, addActionFor(NavTab.Screentime))
        assertTrue(addActionFor(NavTab.Today) != null)
    }

    // ------------------------------------------------------------ AC-1 server: validation, sync ownership, health

    private fun verdict(table: String, id: String, data: JsonObject) = validateRow(SyncRow(table, id, 1, false, data))

    private fun use(device: String = "PHONE", app: String = "com.a", title: String? = null, startAt: Long = 1000, endAt: Long = 61_000, localDate: String = "2026-11-10") =
        obj("device" to device, "app" to app, "title" to title, "startAt" to startAt, "endAt" to endAt, "localDate" to localDate)

    @Test
    fun test_AC1_usage_session_validation() {
        assertNull(verdict("usage_session", "use:phone:com.a:1000", use()))
        assertNull(verdict("usage_session", "use:laptop:1000", use(device = "LAPTOP", app = "code.exe", title = "t")))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(device = "TABLET")))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(app = "")))
        assertNull(verdict("usage_session", "use:phone:a:1000", use(app = "a".repeat(300))))
        assertNotNull(verdict("usage_session", "use:phone:a:1000", use(app = "a".repeat(301))))
        assertNull(verdict("usage_session", "use:phone:com.a:1000", use(title = "t".repeat(200))))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(title = "t".repeat(201))))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(startAt = 5000, endAt = 4000)))
        assertNull(verdict("usage_session", "use:phone:com.a:0", use(startAt = 0, endAt = 172_800_000)))
        assertNotNull(verdict("usage_session", "use:phone:com.a:0", use(startAt = 0, endAt = 172_800_001)))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(localDate = "2026-13-01")))
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(localDate = "2027-02-29")))
        assertNotNull(verdict("usage_session", "use:laptop:1000", use(device = "PHONE")), "PHONE needs a use:phone: id")
        assertNotNull(verdict("usage_session", "use:phone:com.a:1000", use(device = "LAPTOP")), "LAPTOP needs a use:laptop: id")
        assertNotNull(verdict("usage_session", "random", use()))
    }

    private fun ruleData(matchType: String = "EXE", pattern: String = "code.exe", label: String = "PRODUCTIVE") =
        obj("matchType" to matchType, "pattern" to pattern, "label" to label)

    @Test
    fun test_AC1_app_rule_validation() {
        assertNull(verdict("app_rule", "rule:EXE:code.exe", ruleData()))
        assertNull(verdict("app_rule", "rule:PACKAGE:com.a", ruleData("PACKAGE", "com.a", "NEUTRAL")))
        assertNull(verdict("app_rule", "rule:TITLE_CONTAINS:youtube", ruleData("TITLE_CONTAINS", "youtube", "UNPRODUCTIVE")))
        assertNotNull(verdict("app_rule", "rule:REGEX:x", ruleData("REGEX", "x")))
        assertNotNull(verdict("app_rule", "rule:EXE:code.exe", ruleData(label = "GOOD")))
        assertNotNull(verdict("app_rule", "rule:EXE:code.exe", ruleData(label = "UNSORTED")))
        assertNotNull(verdict("app_rule", "rule:EXE:", ruleData(pattern = "")))
        assertNotNull(verdict("app_rule", "rule:EXE:Code.exe", ruleData(pattern = "Code.exe")), "pattern must be lowercase")
        assertNotNull(verdict("app_rule", "rule:EXE: code.exe", ruleData(pattern = " code.exe")), "pattern must be trimmed")
        assertNotNull(verdict("app_rule", "rule:EXE:other.exe", ruleData()), "id must match matchType and pattern")
        val long = "a".repeat(200)
        assertNull(verdict("app_rule", "rule:EXE:$long", ruleData(pattern = long)))
        assertNotNull(verdict("app_rule", "rule:EXE:${long}a", ruleData(pattern = long + "a")))
    }

    private fun newSdb() = openDb(Files.createTempDirectory("p10acc").resolve("t.db").toString())

    @Test
    fun test_AC1_usage_session_and_app_rule_round_trip_through_push_and_pull() = runBlocking<Unit> {
        val sdb = newSdb()
        try {
            val store = SyncStore(sdb)
            val r = store.push(
                listOf(
                    SyncRow("usage_session", "use:phone:com.a:1000", 1000, false, use()),
                    SyncRow("app_rule", "rule:EXE:code.exe", 1000, false, ruleData()),
                ),
            )
            assertEquals(2, r.accepted.size, "rejected: ${r.rejected}")
            val pulled = store.pull(0).rows
            assertEquals("com.a", pulled.single { it.table == "usage_session" }.data["app"]!!.let { (it as JsonPrimitive).content })
            assertEquals("PRODUCTIVE", pulled.single { it.table == "app_rule" }.data["label"]!!.let { (it as JsonPrimitive).content })
            // tombstone for the rule syncs
            val d = store.push(listOf(SyncRow("app_rule", "rule:EXE:code.exe", 2000, true, ruleData())))
            assertEquals(listOf("rule:EXE:code.exe"), d.accepted)
            assertTrue(store.pull(0).rows.single { it.table == "app_rule" }.deleted)
        } finally { sdb.close() }
    }

    @Test
    fun test_AC1_listen_is_server_written_forged_push_is_refused_and_server_rows_are_pulled() = runBlocking<Unit> {
        val sdb = newSdb()
        try {
            val store = SyncStore(sdb)
            val forged = SyncRow("listen", "listen:1000:abc", 1000, false, obj("trackName" to "S", "artist" to "A", "durationMs" to 1000, "playedAt" to 1000, "localDate" to "2026-11-10"))
            val r = store.push(listOf(forged))
            assertTrue(r.accepted.isEmpty())
            assertEquals(1, r.rejected.size)
            assertTrue(store.pull(0).rows.none { it.table == "listen" })
            sdb.db.listenQueries.upsert(Listen("listen:2000:xyz", 2000, 0, 7, "SYNCED", "Song", "Artist", 180_000, 2000, "2026-11-10"))
            val got = store.pull(0).rows.single { it.table == "listen" }
            assertEquals("listen:2000:xyz", got.id)
            assertEquals("Song", (got.data["trackName"] as JsonPrimitive).content)
        } finally { sdb.close() }
    }

    @Test
    fun test_AC1_health_spotifyOk_is_null_without_a_spotify_block() = testApplication {
        val sdb = newSdb()
        try {
            val cfg = Config(bindAddress = "127.0.0.1", dbPath = "x", tokens = mapOf("laptop" to "p10-token"))
            application { module(cfg, SyncStore(sdb)) }
            val j = Json.parseToJsonElement(
                client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer p10-token") }.bodyAsText(),
            ).jsonObject
            assertEquals("null", j.getValue("spotifyOk").toString())
        } finally { sdb.close() }
    }

    // ------------------------------------------------------------ release / ops files

    @Test
    fun test_AC7_release_versions_1_9_0_and_versionCode_5_current() {
        val g = text("desktopApp/build.gradle.kts")
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
        assertTrue(g.contains("upgradeUuid"))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC6_checklists_name_1_9_0_and_w9_stays_1_7_0() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            val t = text("ops/checklists/$f.md")
            assertTrue(t.contains("1.18.0"), "$f.md names 1.18.0")
            assertFalse(t.contains("Wally-1.8.0") || t.contains("Wally-1.7.0"), "$f.md still names an old installer")
        }
        assertTrue(text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        assertTrue(text("ops/checklists/w9-study.md").contains("1.7.0"))
    }

    @Test
    fun test_AC6_w10_checklist_has_the_manual_lines() {
        val t = text("ops/checklists/w10-screentime.md").lowercase()
        for (k in listOf("tray", "quit", "autostart", "idle", "suggest labels", "accept", "listening", "offline", "restore", "deploy")) {
            if (k == "autostart") assertTrue(t.contains("start with windows") || t.contains("autostart"), "mentions autostart")
            else assertTrue(t.contains(k), "w10-screentime.md should mention '$k'")
        }
    }

    @Test
    fun test_AC6_restore_helper_lists_28_tables_with_the_three_new_ones() {
        val t = text("ops/assistant-restore-test.sh")
        val tables = Regex("^TABLES=\"([^\"]*)\"", RegexOption.MULTILINE).find(t)!!.groupValues[1].trim().split(Regex("\\s+"))
        assertEquals(34, tables.size, "restore helper TABLES: $tables")
        for (n in listOf("usage_session", "app_rule", "listen")) assertTrue(n in tables, "restore helper lists $n")
        assertFalse(t.contains('\r'))
    }

    @Test
    fun test_AC5_spotify_guide_has_redirect_uri_and_six_month_reauth_note() {
        val t = text("ops/spotify.md")
        assertTrue(t.contains("http://127.0.0.1:8765/callback"))
        assertTrue(t.contains("user-read-recently-played"))
        assertTrue(Regex("6[ -]months?|six months", RegexOption.IGNORE_CASE).containsMatchIn(t))
        assertTrue(t.contains("spotify-auth"))
    }

    @Test
    fun test_AC5_config_example_has_a_spotify_block_without_real_secrets() {
        val t = text("ops/config.example.json")
        val j = Json.parseToJsonElement(t).jsonObject
        assertTrue("spotify" in j, "config.example.json has a spotify block")
        val sp = j.getValue("spotify").jsonObject
        assertTrue("clientId" in sp && "clientSecret" in sp)
    }

    @Test
    fun test_AC3_screentime_ops_note_records_the_no_admin_probe() {
        val t = text("ops/screentime.md")
        assertTrue(t.contains("admin", ignoreCase = true))
    }

    @Test
    fun test_AC4_android_manifest_declares_usage_stats_permission() {
        val m = text("androidApp/src/main/AndroidManifest.xml")
        assertTrue(m.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(m.contains("ProtectedPermissions"))
    }
}
