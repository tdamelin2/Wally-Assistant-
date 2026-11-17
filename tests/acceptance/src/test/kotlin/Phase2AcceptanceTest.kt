import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 2 file-level preconditions for AC-4..AC-9. AC-1..AC-3 are covered by
 * :server:test (AuthTest, SyncTest) and :shared:jvmTest (SyncEngineTest), run in the same `check`.
 */
class Phase2AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun f(path: String) = File(root, path).also { assertTrue(it.exists(), "missing $path") }
    private fun text(path: String) = f(path).readText()

    private fun assertNoCr(path: String) = assertFalse(text(path).contains('\r'), "$path contains CR")

    @Test
    fun test_AC5_ops_files_have_no_CR() {
        val files = File(root, "ops").listFiles { x ->
            x.isFile && (x.extension in setOf("sh", "service", "timer", "json"))
        }!!.map { "ops/" + it.name }
        assertTrue("ops/assistant.service" in files)
        assertTrue("ops/assistant-backup.timer" in files)
        assertTrue("ops/assistant-restore-test.sh" in files)
        assertTrue("ops/config.example.json" in files)
        files.forEach { assertNoCr(it) }
    }

    @Test
    fun test_AC5_service_unit_contents() {
        val t = text("ops/assistant.service")
        assertTrue(Regex("(?m)^User=assistant$").containsMatchIn(t))
        assertTrue(Regex("(?m)^ExecStart=.*-Xmx512m.*/opt/assistant/server\\.jar").containsMatchIn(t))
        assertTrue(Regex("(?m)^Restart=on-failure$").containsMatchIn(t))
        assertTrue(Regex("(?m)^UMask=0077$").containsMatchIn(t))
        assertTrue(Regex("(?m)^After=.*tailscaled\\.service").containsMatchIn(t))
    }

    @Test
    fun test_AC9_timer_and_backup_service() {
        val timer = text("ops/assistant-backup.timer")
        assertTrue(timer.contains("OnCalendar=*-*-* 03:00:00 Asia/Jerusalem"))
        assertTrue(Regex("(?m)^Persistent=true$").containsMatchIn(timer))
        assertTrue(timer.contains("WantedBy=timers.target"))
        val svc = text("ops/assistant-backup.service")
        assertTrue(svc.contains("Type=oneshot"))
        assertTrue(svc.contains("User=assistant"))
        assertTrue(svc.contains("UMask=0077"))
        assertTrue(svc.contains("/opt/assistant/assistant-backup.sh"))
    }

    @Test
    fun test_AC4_config_example_is_placeholders_and_has_server_shape() {
        val raw = text("ops/config.example.json")
        val obj = Json.parseToJsonElement(raw).jsonObject
        for (k in listOf("bindAddress", "port", "dbPath", "tokens")) assertTrue(k in obj, "missing $k")
        val tokens = obj["tokens"]!!.jsonObject
        assertTrue(tokens.size >= 2)
        tokens.values.forEach {
            val v = it.jsonPrimitive.content
            assertFalse(Regex("^[0-9a-f]{64}$").matches(v), "token looks real: $v")
        }
        assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(raw), "64-hex string found in example config")
    }

    @Test
    fun test_AC5_deploy_script_checks_exit_codes() {
        val t = text("ops/deploy-server.ps1")
        assertTrue(t.contains("server-all.jar"))
        assertTrue(t.contains("sudo -n"))
        val lines = t.lines()
        fun checkedAfter(cmd: String) {
            val i = lines.indexOfFirst { it.trimStart().startsWith("& $cmd") }
            assertTrue(i >= 0, "no call to $cmd")
            assertTrue(lines.drop(i + 1).take(3).any { it.contains("LASTEXITCODE") }, "$cmd exit code unchecked")
        }
        checkedAfter(".\\gradlew")
        checkedAfter("scp")
        checkedAfter("ssh")
    }

    @Test
    fun test_AC9_restore_script_fails_on_bad_integrity_or_count_mismatch() {
        val t = text("ops/assistant-restore-test.sh")
        assertTrue(t.startsWith("#!/bin/bash"))
        assertTrue(t.contains("umask 077"))
        assertTrue(t.contains("integrity_check"))
        assertTrue(Regex("\"\\\$INTEGRITY\" != \"ok\"").containsMatchIn(t))
        assertTrue(Regex("(?s)INTEGRITY.*exit 1").containsMatchIn(t))
        listOf("checkin", "spending", "journal", "task", "health_day", "workout", "cal_event", "mail_item", "briefing", "meal", "kv")
            .forEach { assertTrue(Regex("\\b$it\\b").containsMatchIn(t), "table $it not counted") }
        assertTrue(t.contains("mktemp -d"))
        assertTrue(t.contains("trap"))
        assertTrue(Regex("(?m)^exit \"?\\\$status\"?$").containsMatchIn(t) || t.contains("exit 1"))
    }

    @Test
    fun test_AC6_jvm_driver_uses_localappdata_assistant_db() {
        val t = File(root, "shared/src/jvmMain").walkTopDown()
            .first { it.name == "DriverFactory.jvm.kt" }.readText()
        assertTrue(t.contains("ASSISTANT_DB"))
        assertTrue(t.contains("LOCALAPPDATA"))
        assertTrue(t.contains("Assistant"))
        assertTrue(t.contains("assistant.db"))
    }

    @Test
    fun test_AC7_android_manifest_flags() {
        val t = text("androidApp/src/main/AndroidManifest.xml")
        assertTrue(t.contains("android.permission.INTERNET"))
        assertTrue(t.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(t.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun test_AC6_msi_config() {
        val t = text("desktopApp/build.gradle.kts")
        assertTrue(t.contains("packageName = \"Wally\""))
        assertTrue(t.contains("packageVersion = \"1.18.0\""))
        assertTrue(t.contains("java.sql"))
        assertTrue(t.contains("upgradeUuid = \"263d9a33-d272-424f-9df3-e5168273ba55\""))
        assertTrue(t.contains("TargetFormat.Msi"))
        val m = Regex("iconFile\\.set\\(project\\.file\\(\"([^\"]+\\.ico)\"\\)\\)").find(t)
        assertTrue(m != null, "iconFile not set to an .ico")
        assertTrue(File(root, "desktopApp/" + m!!.groupValues[1]).exists(), "ico file missing")
    }

    @Test
    fun test_AC6_wally_name_and_icons() {
        assertTrue(Regex("<string name=\"app_name\">Wally</string>").containsMatchIn(text("androidApp/src/main/res/values/strings.xml")))
        val manifest = text("androidApp/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        val res = File(root, "androidApp/src/main/res")
        for (n in listOf("ic_launcher", "ic_launcher_round")) {
            val found = res.listFiles { d -> d.isDirectory && d.name.startsWith("mipmap") }!!
                .any { d -> d.listFiles()!!.any { it.nameWithoutExtension == n } }
            assertTrue(found, "no mipmap resource $n")
        }
        assertTrue(Regex("title\\s*=\\s*\"Wally\"").containsMatchIn(text("desktopApp/src/main/kotlin/main.kt")))
        val ab = text("androidApp/build.gradle.kts")
        assertTrue(ab.contains("applicationId = \"dev.tim.assistant\""))
    }

    @Test
    fun test_AC8_checklists_exist_and_cover_plan_lines() {
        val c1 = text("ops/checklists/phase1.md")
        listOf("Not set up", "Offline", "היום היה טוב", "Edit", "Delete").forEach {
            assertTrue(c1.contains(it), "phase1.md lacks '$it'")
        }
        val e2e = text("ops/checklists/phase1-e2e.md")
        val numbered = Regex("(?m)^\\s*(?:-\\s*\\[ \\]\\s*)?([1-9])[.)]|(?m)^\\s*- \\[ \\]").findAll(e2e).count()
        assertTrue(numbered >= 6, "phase1-e2e.md has fewer than 6 items ($numbered)")
    }

    @Test
    fun test_AC2_server_fat_jar_name_pinned() {
        assertTrue(text("server/build.gradle.kts").contains("server-all.jar"))
        assertTrue(text("server/build.gradle.kts").contains("dev.tim.assistant.server.MainKt"))
    }

    @Test
    fun test_RAM_row_updated_in_server_facts() {
        assertTrue(text("ops/server-facts.md").contains("8 GB")) // upgraded again 2026-11-11 (before Phase 17)
        assertEquals(1, Regex("upgraded 2026-11-05").findAll(text("ops/server-facts.md")).count())
    }
}
