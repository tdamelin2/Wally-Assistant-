import androidx.compose.ui.input.key.Key
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.LeaveGuard
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.Shortcut
import dev.tim.assistant.ui.ShortcutContext
import dev.tim.assistant.ui.TextEntryState
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.dockedFormFor
import dev.tim.assistant.ui.pickerChipFor
import dev.tim.assistant.ui.shortcutFor
import dev.tim.assistant.ui.shortcutHandled
import dev.tim.assistant.ui.stacksSidePanel
import dev.tim.assistant.health.laptopDayRows
import dev.tim.assistant.health.laptopHealthTiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 16 acceptance tests, written from the Plan's AC list and Contract (AC-1, AC-2, AC-4..AC-7 automated parts). */
class Phase16AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()
    private val ui = "shared/src/commonMain/kotlin/dev/tim/assistant/ui"

    private fun ctx(
        chip: NavTab = NavTab.Today,
        picker: Boolean = false,
        form: Boolean = false,
        writer: Boolean = false,
        dialog: Boolean = false,
        search: Boolean = false,
    ) = ShortcutContext(chip, picker, form, writer, dialog, search)

    // ------------------------------------------------------------ AC-1 pure functions

    @Test
    fun test_AC1_stacksSidePanel_899_900_901() {
        assertTrue(stacksSidePanel(899f))
        assertFalse(stacksSidePanel(900f))
        assertFalse(stacksSidePanel(901f))
        assertTrue(stacksSidePanel(0f))
        assertFalse(stacksSidePanel(1920f))
    }

    @Test
    fun test_AC1_shortcutFor_table() {
        assertEquals(Shortcut.NewItem, shortcutFor(Key.N, ctrl = false, inTextField = false, multiline = false))
        assertEquals(Shortcut.NewItem, shortcutFor(Key.N, ctrl = false, inTextField = false, multiline = true))
        assertNull(shortcutFor(Key.N, ctrl = false, inTextField = true, multiline = false))
        assertNull(shortcutFor(Key.N, ctrl = false, inTextField = true, multiline = true))
        assertNull(shortcutFor(Key.N, ctrl = true, inTextField = false, multiline = false))
        assertEquals(Shortcut.Cancel, shortcutFor(Key.Escape, false, inTextField = false, multiline = false))
        assertEquals(Shortcut.Cancel, shortcutFor(Key.Escape, false, inTextField = true, multiline = true))
        assertEquals(Shortcut.Save, shortcutFor(Key.Enter, false, inTextField = false, multiline = false))
        assertEquals(Shortcut.Save, shortcutFor(Key.Enter, false, inTextField = true, multiline = false))
        assertNull(shortcutFor(Key.Enter, false, inTextField = true, multiline = true))
        assertEquals(Shortcut.SaveLong, shortcutFor(Key.Enter, true, inTextField = true, multiline = true))
        assertEquals(Shortcut.SaveLong, shortcutFor(Key.Enter, true, inTextField = false, multiline = false))
        assertEquals(Shortcut.FocusSearch, shortcutFor(Key.F, true, inTextField = true, multiline = false))
        assertEquals(Shortcut.FocusSearch, shortcutFor(Key.F, true, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.F, false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.A, false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.Tab, true, inTextField = false, multiline = false))
    }

    @Test
    fun test_AC1_shortcutHandled_NewItem() {
        assertTrue(shortcutHandled(Shortcut.NewItem, ctx(NavTab.Today)))
        assertTrue(shortcutHandled(Shortcut.NewItem, ctx(NavTab.Tasks)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(NavTab.Chat)))
        assertEquals(null, addActionFor(NavTab.Chat))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(NavTab.Tasks, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(NavTab.Today, picker = true)))
        for (chip in NavTab.entries) {
            assertEquals(addActionFor(chip) != null, shortcutHandled(Shortcut.NewItem, ctx(chip)), "$chip")
        }
    }

    @Test
    fun test_AC1_shortcutHandled_Cancel_Save_SaveLong_Focus() {
        assertFalse(shortcutHandled(Shortcut.Cancel, ctx()))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(picker = true)))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(form = true)))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(writer = true)))

        assertFalse(shortcutHandled(Shortcut.Save, ctx()))
        assertTrue(shortcutHandled(Shortcut.Save, ctx(form = true)))
        assertFalse(shortcutHandled(Shortcut.Save, ctx(form = true, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.Save, ctx(writer = true)))

        assertFalse(shortcutHandled(Shortcut.SaveLong, ctx()))
        assertTrue(shortcutHandled(Shortcut.SaveLong, ctx(writer = true)))
        assertFalse(shortcutHandled(Shortcut.SaveLong, ctx(writer = true, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.SaveLong, ctx(form = true)))

        assertTrue(shortcutHandled(Shortcut.FocusSearch, ctx(NavTab.Files, search = true)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(NavTab.Files, search = false)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(NavTab.Mail, search = true)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(NavTab.Files, search = true, dialog = true)))
    }

    @Test
    fun test_AC1_chat_enter_is_not_consumed() {
        val c = ctx(NavTab.Chat)
        assertFalse(shortcutHandled(Shortcut.Save, c))
        assertFalse(shortcutHandled(Shortcut.Cancel, c))
    }

    @Test
    fun test_AC1_dockedFormFor() {
        val logChips = listOf(NavTab.Checkins, NavTab.Spending, NavTab.Journal, NavTab.Meals, NavTab.Tasks, NavTab.Dreams)
        for (chip in logChips) {
            assertNotNull(dockedFormFor(chip, null), "$chip new form")
            assertEquals(pickerChipFor(dockedFormFor(chip, null)!!), chip)
        }
        assertEquals(FormTarget.Checkin(null), dockedFormFor(NavTab.Checkins, null))
        assertEquals(FormTarget.Dream(null), dockedFormFor(NavTab.Dreams, null))
        // a matching open target wins
        assertEquals(FormTarget.Dream("d1"), dockedFormFor(NavTab.Dreams, FormTarget.Dream("d1")))
        assertEquals(FormTarget.DreamRead("d1"), dockedFormFor(NavTab.Dreams, FormTarget.DreamRead("d1")))
        // a target from another chip is ignored
        assertEquals(FormTarget.Task(null), dockedFormFor(NavTab.Tasks, FormTarget.Dream("d1")))
        assertEquals(FormTarget.Meal(null), dockedFormFor(NavTab.Meals, FormTarget.DreamRead("x")))
        // other chips: nothing
        for (chip in listOf(NavTab.Today, NavTab.Goals, NavTab.Files, NavTab.Mail, NavTab.Chat)) {
            assertNull(dockedFormFor(chip, null), "$chip")
            assertNull(dockedFormFor(chip, FormTarget.Task(null)), "$chip with open task")
        }
    }

    @Test
    fun test_AC1_pickerChipFor_all_six_types() {
        assertEquals(NavTab.Checkins, pickerChipFor(FormTarget.Checkin(null)))
        assertEquals(NavTab.Spending, pickerChipFor(FormTarget.Spending(null)))
        assertEquals(NavTab.Journal, pickerChipFor(FormTarget.Journal(null)))
        assertEquals(NavTab.Meals, pickerChipFor(FormTarget.Meal(null)))
        assertEquals(NavTab.Tasks, pickerChipFor(FormTarget.Task(null)))
        assertEquals(NavTab.Dreams, pickerChipFor(FormTarget.Dream(null)))
        assertEquals(NavTab.Dreams, pickerChipFor(FormTarget.DreamRead("x")))
    }

    @Test
    fun test_AC1_TextEntryState_stale_blur_does_not_clear_newer_focus() {
        val s = TextEntryState()
        assertFalse(s.inTextField)
        val a = Any()
        val b = Any()
        s.focus(a, multiline = true)
        assertTrue(s.inTextField)
        assertTrue(s.multiline)
        s.focus(b, multiline = false)
        s.blur(a) // stale
        assertTrue(s.inTextField)
        assertFalse(s.multiline)
        s.blur(b)
        assertFalse(s.inTextField)
        assertFalse(s.multiline)
    }

    @Test
    fun test_AC1_LeaveGuard_token_behaviour() {
        val g = LeaveGuard()
        assertFalse(g.isDirty)
        val a = Any()
        val b = Any()
        g.register(a) { true }
        assertTrue(g.isDirty)
        g.register(b) { false }
        g.unregister(a) // stale
        assertFalse(g.isDirty)
        g.register(a) { true }
        g.unregister(b) // stale again
        assertTrue(g.isDirty)
        g.unregister(a)
        assertFalse(g.isDirty)
    }

    // ------------------------------------------------------------ AC-2 / AC-4 / AC-5 source scans

    @Test
    fun test_AC2_no_OutlinedTextField_outside_FormParts() {
        val hits = File(root, "shared/src/commonMain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FormParts.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (l.contains("OutlinedTextField(")) "${f.name}:${i + 1}" else null } }
            .toList()
        assertTrue(hits.isEmpty(), "raw OutlinedTextField: $hits")
    }

    @Test
    fun test_AC5_main_kt_has_key_hook_and_passes_shortcuts() {
        val m = text("desktopApp/src/main/kotlin/main.kt")
        assertTrue(m.contains("Window("))
        assertTrue(m.contains("onPreviewKeyEvent"))
        assertTrue(Regex("""App\([^)]*shortcuts""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(m), "App( called with shortcuts")
    }

    @Test
    fun test_AC5_no_literal_backslash_n_in_line_comments_of_entry_points() {
        val re = Regex("//.*\\\\n\\s+[A-Za-z_]")
        val files = listOf(File(root, "desktopApp/src/main/kotlin/main.kt")) +
            File(root, "androidApp/src").walkTopDown().filter { it.isFile && it.name == "MainActivity.kt" }.toList()
        val hits = files.flatMap { f -> f.readLines().filter { re.containsMatchIn(it) } }
        assertTrue(hits.isEmpty(), "literal \\n in comment: $hits")
    }

    @Test
    fun test_AC5_settings_reminders_card_arrives_in_phase_17() {
        // Phase 16 guarded "no Reminders card yet"; Phase 17 adds it by design (P17-A-13), so the guard is flipped.
        val s = text("$ui/SettingsScreen.kt") + text("$ui/RemindersSection.kt")
        assertTrue(s.contains("Reminders"), "Reminders card is part of Phase 17")
    }

    @Test
    fun test_AC5_collapse_toggle_and_720_cap_removed() {
        assertFalse(text("$ui/Shell.kt").contains("sidebarCollapsedFrom"))
        val all = File(root, "shared/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(all.contains("fun sidebarCollapsedFrom"))
        assertFalse(all.contains("MaxContentWidth"))
    }

    @Test
    fun test_AC4_dream_wall_test_exists() {
        assertTrue(File(root, "shared/src").walkTopDown().any { it.name == "DreamWallTest.kt" })
    }

    @Test
    fun test_AC6_label_uniqueness_covers_laptop_mail_labels() {
        // laptopMailLabels is internal to :shared; the shared LabelUniquenessTest or Labels.kt must reference it.
        val covered = File(root, "shared/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && (it.name == "LabelUniquenessTest.kt" || it.name == "Labels.kt") }
            .any { it.readText().contains("laptopMailLabels") }
        assertTrue(covered, "laptopMailLabels not in labelStates() or LabelUniquenessTest")
    }

    // ------------------------------------------------------------ Health

    private fun hd(date: String, sleep: Long?, steps: Long?, hr: Long?, updated: Long = 1, deleted: Long = 0, id: String = "health:$date") =
        Health_day(id, updated, deleted, 0, "SYNCED", date, sleep, steps, hr)

    @Test
    fun test_AC1_laptopDayRows_seven_days_today_first() {
        val rows = laptopDayRows(emptyList(), "2026-11-11")
        assertEquals(7, rows.size)
        assertEquals("2026-11-11", rows.first().date)
        assertEquals("2026-11-05", rows.last().date)
        assertTrue(rows.all { it.sleepMinutes == null && it.steps == null && it.restingHr == null })
    }

    @Test
    fun test_AC1_laptopDayRows_missing_is_null_not_zero_and_older_than_7_excluded() {
        val days = listOf(hd("2026-11-10", 400, null, 55), hd("2026-11-01", 100, 100, 50))
        val rows = laptopDayRows(days, "2026-11-11")
        val r = rows.first { it.date == "2026-11-10" }
        assertEquals(400L, r.sleepMinutes)
        assertNull(r.steps)
        assertEquals(55L, r.restingHr)
        assertTrue(rows.none { it.date == "2026-11-01" })
    }

    @Test
    fun test_AC1_laptopDayRows_latest_updatedAt_wins() {
        val days = listOf(
            hd("2026-11-10", 100, 1, 50, updated = 5, id = "a"),
            hd("2026-11-10", 200, 2, 60, updated = 9, id = "b"),
        )
        val r = laptopDayRows(days, "2026-11-11").first { it.date == "2026-11-10" }
        assertEquals(200L, r.sleepMinutes)
        assertEquals(60L, r.restingHr)
    }

    @Test
    fun test_AC1_laptopHealthTiles_values() {
        val days = listOf(
            hd("2026-11-11", 450, 10, null),
            hd("2026-11-10", 300, 8000, 52),
            hd("2026-11-09", 300, 5000, 49),
        )
        val t = laptopHealthTiles(days, "2026-11-11")
        assertEquals(450L, t.sleepMinutes)
        assertEquals(8000L, t.stepsYesterday)
        assertEquals(52L, t.restingHr)
        assertEquals("2026-11-10", t.restingHrDate)
    }

    @Test
    fun test_AC1_laptopHealthTiles_missing_is_null() {
        val t = laptopHealthTiles(emptyList(), "2026-11-11")
        assertNull(t.sleepMinutes)
        assertNull(t.stepsYesterday)
        assertNull(t.restingHr)
        assertNull(t.restingHrDate)
    }

    @Test
    fun test_AC1_laptopHealthTiles_restingHr_today_and_future_ignored() {
        val days = listOf(hd("2026-11-11", null, null, 58), hd("2026-11-12", null, null, 70))
        val t = laptopHealthTiles(days, "2026-11-11")
        assertEquals(58L, t.restingHr)
        assertEquals("2026-11-11", t.restingHrDate)
    }

    @Test
    fun test_AC1_laptopDayRows_deleted_rows_are_missing() {
        val rows = laptopDayRows(listOf(hd("2026-11-10", 400, 1, 50, deleted = 1)), "2026-11-11")
        val r = rows.first { it.date == "2026-11-10" }
        assertNull(r.sleepMinutes)
    }

    // ------------------------------------------------------------ AC-6/7 release and checklist

    @Test
    fun test_AC7_release_1_14_0_versionCode_10() {
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
        assertTrue(a.contains("dev.tim.assistant"))
    }

    @Test
    fun test_AC7_checklists_name_1_14_0() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        assertTrue(text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
    }

    @Test
    fun test_AC7_laptop_checklist_names_all_17_frames_and_SC13_to_15() {
        val c = text("ops/checklists/redesign-v2-laptop.md")
        val frames = File(root, "docs/plan/wally-redesign-v2/canvas").listFiles { f -> f.name.startsWith("Laptop") && f.name.endsWith(".dc.html") }!!
            .map { it.name.removeSuffix(".dc.html") }
            .filter { it != "LaptopChat" }
        assertEquals(17, frames.size)
        for (f in frames) assertTrue(c.contains(f), "checklist missing $f")
        for (k in listOf("SC-13", "SC-14", "SC-15")) assertTrue(c.contains(k), "checklist missing $k")
        assertTrue(c.contains("Enter still sends in Chat") || c.lowercase().contains("enter still sends in chat"))
        assertTrue(c.contains("Esc") && c.lowercase().contains("dialog"))
        assertTrue(c.lowercase().contains("discard"))
    }
}
