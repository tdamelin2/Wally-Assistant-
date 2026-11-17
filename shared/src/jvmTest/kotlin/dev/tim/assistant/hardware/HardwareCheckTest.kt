package dev.tim.assistant.hardware

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

private const val BATTERY_XML =
    """<?xml version="1.0" encoding="UTF-8"?>
<BatteryReport xmlns="http://schemas.microsoft.com/battery/2012"><ReportInformation><Version>1.1</Version></ReportInformation>
<Batteries><Battery><Id>DELL x</Id><Manufacturer>BYD</Manufacturer><SerialNumber>SECRET</SerialNumber><ManufactureDate></ManufactureDate>
<Chemistry>LION</Chemistry><LongTerm>1</LongTerm><RelativeCapacity>0</RelativeCapacity><DesignCapacity>68607</DesignCapacity>
<FullChargeCapacity>66274</FullChargeCapacity><CycleCount>79</CycleCount></Battery></Batteries></BatteryReport>"""

private const val NO_BATTERY_XML =
    """<?xml version="1.0" encoding="UTF-8"?><BatteryReport xmlns="http://schemas.microsoft.com/battery/2012"><Batteries/></BatteryReport>"""

private val COMPUTER = """{"Manufacturer":"Dell Inc.","Model":"XPS 16 DA16260","SystemType":"x64-based PC"}"""
private val BIOS = """{"Manufacturer":"Dell Inc.","SMBIOSBIOSVersion":"1.11.0","ReleaseDate":"\/Date(1785801600000)\/"}"""
private val DISKS = """[{"FriendlyName":"Generic Mass-Storage","MediaType":"Unspecified","HealthStatus":"Healthy","OperationalStatus":"OK","Size":3965190144},
{"FriendlyName":"BG7 KIOXIA 1024GB","MediaType":"SSD","HealthStatus":"Healthy","OperationalStatus":"OK","Size":1024209543168}]"""
private val EVENTS = """[{"TimeCreated":"\/Date(1794297082983)\/","Id":24641,"ProviderName":"Microsoft-Windows-BitLocker-Driver","Level":2,"Message":"An unexpected error"},
{"TimeCreated":"\/Date(1794297182983)\/","Id":24641,"ProviderName":"Microsoft-Windows-BitLocker-Driver","Level":2,"Message":"Hebrew \u05e9\u05dc\u05d5\u05dd"},
{"TimeCreated":"\/Date(1794297282983)\/","Id":10,"ProviderName":"Other","Level":1,"Message":null}]"""

/** Answers by looking at the command; records every call. */
private class FakeRunner : CommandRunner {
    val calls = mutableListOf<Pair<String, List<String>>>()
    var batteryXml: String? = BATTERY_XML
    var batteryExit = 0
    val psOutputs = mutableMapOf(
        "Win32_ComputerSystem" to CommandResult(0, COMPUTER),
        "Win32_BIOS" to CommandResult(0, BIOS),
        "Get-PhysicalDisk" to CommandResult(0, DISKS),
        "Get-WinEvent" to CommandResult(0, EVENTS),
    )
    var lastBatteryPath: String? = null

    override suspend fun run(executable: String, args: List<String>): CommandResult {
        calls += executable to args
        if (executable.endsWith("powercfg.exe")) {
            val path = args[args.indexOf("/output") + 1]
            lastBatteryPath = path
            batteryXml?.let { File(path).writeText(it) }
            return CommandResult(batteryExit, "Battery life report saved")
        }
        val command = args.last()
        return psOutputs.entries.first { it.key in command }.value
    }
}

class HardwareCheckTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "wally-hwtest-${System.nanoTime()}").also { it.mkdirs() }
    private val runner = FakeRunner()

    private fun check() = HardwareCheck(runner, { 1_794_300_000_000L }, temp, "C:\\Windows")

    private fun run(): HwCheckOutcome = runBlocking { check().run() }

    private fun section(outcome: HwCheckOutcome, name: String): JsonObject =
        Json.parseToJsonElement(outcome.resultJson).let { it as JsonObject }[name] as JsonObject

    @Test
    fun all_sections_parse_from_the_sample_output() {
        val outcome = run()
        assertEquals("battery 96.6%, 79 cycles · disks healthy · 3 Windows errors in 7 days", outcome.summary)
        assertEquals(1_794_300_000_000L, hwTakenAt(outcome.resultJson))
        val computer = section(outcome, "computer")
        assertEquals("XPS 16 DA16260", computer["model"]!!.jsonPrimitive.content)
        assertEquals(1785801600000L, section(outcome, "bios")["releaseDate"]!!.jsonPrimitive.content.toLong())
        // the battery file is removed and no serial number is kept
        assertFalse(File(runner.lastBatteryPath!!).exists())
        assertFalse("SECRET" in outcome.resultJson)
        val sections = hardwareSections(outcome.resultJson)
        assertEquals(listOf("Computer", "BIOS", "Battery", "Disks", "Windows errors, last 7 days"), sections.map { it.title })
        assertTrue(sections[2].lines.single().startsWith("Health 96.6%"))
    }

    @Test
    fun one_failing_section_keeps_the_others() {
        runner.psOutputs["Get-PhysicalDisk"] = CommandResult(1, "", "denied")
        val outcome = run()
        assertFalse(section(outcome, "disks")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(HW_NOT_AVAILABLE, section(outcome, "disks")["error"]!!.jsonPrimitive.content)
        assertTrue(section(outcome, "battery")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(section(outcome, "events")["ok"]!!.jsonPrimitive.boolean)
        assertEquals("battery 96.6%, 79 cycles · disks n/a · 3 Windows errors in 7 days", outcome.summary)
        assertEquals(listOf(HW_NOT_AVAILABLE), hardwareSections(outcome.resultJson)[3].lines)
    }

    @Test
    fun a_timed_out_command_is_not_available_and_garbage_output_too() {
        runner.psOutputs["Win32_BIOS"] = CommandResult(-1, "", "", timedOut = true)
        runner.psOutputs["Win32_ComputerSystem"] = CommandResult(0, "not json at all")
        val outcome = run()
        assertFalse(section(outcome, "bios")["ok"]!!.jsonPrimitive.boolean)
        assertFalse(section(outcome, "computer")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(section(outcome, "disks")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun no_matching_events_means_zero_errors_not_a_failure() {
        for (empty in listOf("", "[]", "  \r\n")) {
            runner.psOutputs["Get-WinEvent"] = CommandResult(0, empty)
            val outcome = run()
            assertTrue(section(outcome, "events")["ok"]!!.jsonPrimitive.boolean)
            assertTrue(outcome.summary.endsWith("no Windows errors in 7 days"), outcome.summary)
        }
    }

    @Test
    fun a_single_object_is_accepted_like_an_array() {
        runner.psOutputs["Get-PhysicalDisk"] = CommandResult(
            0, """{"FriendlyName":"Disk","MediaType":"SSD","HealthStatus":"Warning","OperationalStatus":"OK","Size":500000000000}""",
        )
        runner.psOutputs["Get-WinEvent"] = CommandResult(0, """{"TimeCreated":"\/Date(1794297082983)\/","Id":7,"ProviderName":"P","Level":2,"Message":"m"}""")
        val outcome = run()
        assertEquals("battery 96.6%, 79 cycles · disk Disk: Warning · 1 Windows error in 7 days", outcome.summary)
    }

    @Test
    fun hebrew_event_text_survives() {
        val outcome = run()
        assertTrue("\u05e9\u05dc\u05d5\u05dd" in hardwareSections(outcome.resultJson).last().lines.joinToString("\n"))
    }

    @Test
    fun a_laptop_without_a_battery_says_so() {
        runner.batteryXml = NO_BATTERY_XML
        assertTrue(run().summary.startsWith("no battery · "))
        runner.batteryXml = null
        runner.batteryExit = 1
        assertTrue(run().summary.startsWith("battery n/a · "))
    }

    @Test
    fun commands_are_fixed_utf8_and_never_ask_for_admin_or_identifiers() {
        run()
        val powershell = runner.calls.filter { it.first.endsWith("powershell.exe") }
        assertEquals(4, powershell.size)
        for ((_, args) in powershell) {
            assertEquals(listOf("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command"), args.take(5))
            val command = args.last()
            assertTrue(command.startsWith("[Console]::OutputEncoding=[Text.Encoding]::UTF8;"))
            assertFalse('"' in command, command)
            assertFalse("SerialNumber" in command)
            assertFalse("StorageReliabilityCounter" in command)
        }
        val powercfg = runner.calls.single { it.first.endsWith("powercfg.exe") }
        assertEquals(listOf("/batteryreport", "/xml", "/output"), powercfg.second.take(3))
    }

    @Test
    fun date_and_record_parsing() {
        assertEquals(1785801600000L, parseMsDate("/Date(1785801600000)/"))
        assertEquals(1785801600000L, parseMsDate("/Date(1785801600000+0300)/"))
        assertNull(parseMsDate("2026-01-01"))
        assertEquals(0, parseJsonRecords("").size)
        assertEquals(0, parseJsonRecords("null").size)
        assertEquals(1, parseJsonRecords("""{"a":1}""").size)
        assertEquals(2, parseJsonRecords("""[{"a":1},{"a":2}]""").size)
    }

    @Test
    fun summary_of_unreadable_json_is_not_available() {
        assertEquals(HW_NOT_AVAILABLE, hardwareSummary("nope"))
        assertEquals(listOf(HW_NOT_AVAILABLE), hardwareSections("nope").single().lines)
        assertNull(hwTakenAt("nope"))
        assertEquals("answer", hwExplainText("""{"text":"answer"}"""))
        assertNull(hwExplainText(null))
    }
}
