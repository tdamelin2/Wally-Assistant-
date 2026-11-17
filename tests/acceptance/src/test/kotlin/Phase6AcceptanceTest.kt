import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.RowAction
import dev.tim.assistant.ui.SyncIcon
import dev.tim.assistant.ui.layoutModeForDevice
import dev.tim.assistant.ui.showsErrorBar
import dev.tim.assistant.ui.sidebarSyncText
import dev.tim.assistant.ui.syncBarText
import dev.tim.assistant.ui.syncIconFor
import dev.tim.assistant.ui.AddAction
import dev.tim.assistant.ui.addActionFor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class Phase6AcceptanceTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d ?: error("repo root not found")
    }

    private fun text(rel: String) = File(root(), rel).readText()

    // ---- logic ----

    @Test
    fun test_AC1_layout_mode_follows_device() {
        // Phase 15: the 600 dp width switch is retired; the laptop is Wide, the phone is Narrow.
        assertEquals(LayoutMode.Wide, layoutModeForDevice(true))
        assertEquals(LayoutMode.Narrow, layoutModeForDevice(false))
    }

    @Test
    fun test_AC1_nav_tabs_exact() {
        // Phase 14 (P14-A-02): 17 NavTabs in 5 hubs replace the 12-tab list.
        assertEquals(
            listOf("Today", "Check-ins", "Spending", "Journal", "Meals", "Tasks", "Dreams", "Charts", "Health", "Screentime", "Goals", "Chat", "Files", "Study", "Mail", "Hardware", "Settings"),
            NavTab.entries.map { it.label },
        )
    }

    @Test
    fun test_AC1_add_targets_open_the_new_forms() {
        assertEquals(AddAction.Form(FormTarget.Checkin(null)), addActionFor(NavTab.Checkins))
        assertEquals(AddAction.Form(FormTarget.Spending(null)), addActionFor(NavTab.Spending))
        assertEquals(AddAction.Form(FormTarget.Journal(null)), addActionFor(NavTab.Journal))
        assertEquals(AddAction.Form(FormTarget.Task(null)), addActionFor(NavTab.Tasks))
        assertEquals(AddAction.Form(FormTarget.Meal(null)), addActionFor(NavTab.Meals))
        val t = (addActionFor(NavTab.Tasks) as AddAction.Form).target as FormTarget.Task
        assertNull(t.row)
    }

    // Retired in Phase 16 (P16-A-01): the collapsible sidebar and sidebarCollapsedFrom were removed (fixed 88 dp rail).

    private val allStatuses = listOf(
        SyncStatus.Synced(1_700_000_000_000L),
        SyncStatus.Syncing,
        SyncStatus.Offline(3),
        SyncStatus.WrongToken,
        SyncStatus.NotConfigured,
    )

    @Test
    fun test_AC1_sync_icon_and_error_bar() {
        assertEquals(SyncIcon.Synced, syncIconFor(SyncStatus.Synced(5L)))
        assertEquals(SyncIcon.Syncing, syncIconFor(SyncStatus.Syncing))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.Offline(2)))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.WrongToken))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.NotConfigured))
        assertFalse(showsErrorBar(SyncStatus.Synced(5L)))
        assertFalse(showsErrorBar(SyncStatus.Syncing))
        assertTrue(showsErrorBar(SyncStatus.Offline(0)))
        assertTrue(showsErrorBar(SyncStatus.Offline(2)))
        assertTrue(showsErrorBar(SyncStatus.WrongToken))
        assertTrue(showsErrorBar(SyncStatus.NotConfigured))
    }

    @Test
    fun test_AC1_sidebar_sync_text() {
        val at = 1_700_000_000_000L
        val expected = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Jerusalem")))
        assertEquals("Synced $expected", sidebarSyncText(SyncStatus.Synced(at)))
        val now = at + 600_000L
        for (s in allStatuses.filter { it !is SyncStatus.Synced }) {
            assertEquals(syncBarText(s, now), sidebarSyncText(s))
        }
        assertEquals("Syncing…", sidebarSyncText(SyncStatus.Syncing))
        assertEquals("Offline — 3 waiting", sidebarSyncText(SyncStatus.Offline(3)))
    }

    @Test
    fun test_AC1_sync_bar_text_unchanged() {
        val at = 1_000_000L
        assertEquals("Synced 5 min ago", syncBarText(SyncStatus.Synced(at), at + 5 * 60_000L))
        assertEquals("Offline — 2 waiting", syncBarText(SyncStatus.Offline(2), at))
        assertEquals("Wrong token — check Settings", syncBarText(SyncStatus.WrongToken, at))
        assertEquals("Not set up — open Settings", syncBarText(SyncStatus.NotConfigured, at))
        assertEquals("Syncing…", syncBarText(SyncStatus.Syncing, at))
    }

    @Test
    fun test_AC1_row_actions() {
        assertEquals(listOf("Delete"), RowAction.entries.map { it.label })
        assertEquals(RowAction.Delete, RowAction.entries[0])
    }

    // ---- source facts ----

    @Test
    fun test_AC1_today_has_no_quick_add_or_text_add_as_task() {
        val t = text("shared/src/commonMain/kotlin/dev/tim/assistant/ui/TodayScreen.kt")
        assertFalse(Regex("TextButton\\([^}]*Add as task", RegexOption.DOT_MATCHES_ALL).containsMatchIn(t))
        for (label in listOf("Check-in", "Spending", "Journal", "Meal")) {
            assertFalse(Regex("Button\\([^)]*\\)\\s*\\{\\s*Text\\(\"$label\"").containsMatchIn(t), "quick-add $label")
        }
    }

    @Test
    fun test_AC1_log_has_no_edit_text_button() {
        // Phase 15: LogListsScreen.kt was split into one screen file per Log chip.
        val dir = File(root(), "shared/src/commonMain/kotlin/dev/tim/assistant/ui/lists")
        val t = listOf("Checkins", "Spending", "Journal", "Meals", "Tasks").joinToString("\n") { File(dir, it + "Screen.kt").readText() }
        assertFalse(Regex("TextButton\\([^}]*Text\\(\"Edit\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(t))
        assertFalse(t.contains("Text(\"Edit\")"))
    }

    @Test
    fun test_AC1_desktop_version_1_4_0_and_uuid_kept() {
        val g = text("desktopApp/build.gradle.kts")
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
        assertTrue(g.contains("upgradeUuid"))
        assertTrue(g.contains("packageName"))
    }

    @Test
    fun test_AC1_no_material_icons_dependency_added() {
        for (f in listOf("gradle/libs.versions.toml", "shared/build.gradle.kts", "desktopApp/build.gradle.kts", "androidApp/build.gradle.kts")) {
            val p = File(root(), f)
            if (p.exists()) assertFalse(p.readText().contains("material-icons"), f)
        }
    }

    @Test
    fun test_AC1_drawables_exist() {
        val dir = File(root(), "shared/src/commonMain/composeResources/drawable")
        assertTrue(dir.isDirectory)
        val xml = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
        assertTrue(xml.size >= 5, "vector drawables: ${xml.size}")
    }

    @Test
    fun test_AC2_build_notes_record_icon_source() {
        val n = text("ops/build-notes.md")
        assertTrue(n.contains("material-icons-core"))
        assertTrue(n.contains("1.12.2"))
        assertTrue(n.contains("drawable", ignoreCase = true))
        assertTrue(n.contains("vector", ignoreCase = true))
    }

    @Test
    fun test_AC3_checklist_phase5_precheck() {
        val f = File(root(), "ops/checklists/phase5.md")
        assertTrue(f.exists())
        val c = f.readText().lowercase()
        for (k in listOf("600", "narrow", "phone", "restart", "check-in", "spending", "journal", "task", "meal", "delete", "add as task", "settings", "cut off", "right-to-left")) {
            assertTrue(c.contains(k), "phase5.md missing: $k")
        }
        assertTrue(c.contains("+"), "phase5.md mentions + menu")
        assertTrue(c.contains("sidebar") || c.contains("left side"), "sidebar (left menu) item")
        assertTrue(c.contains("collapsed") || c.contains("shrinks"), "collapse item")
        assertTrue(c.contains("hebrew"))
    }

    @Test
    fun test_AC4_checklist_phase1_e2e_precheck() {
        val e = text("ops/checklists/phase1-e2e.md")
        assertTrue(Regex("(?m)^\\s*(\\d+\\.|- \\[ \\])").findAll(e).count() >= 6)
        val p1 = text("ops/checklists/phase1.md")
        assertTrue(p1.contains("Edit"))
        assertTrue(p1.contains("Delete"))
    }
}
