package dev.tim.assistant.hardware

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element

/** What a command printed and how it ended. [timedOut] means the runner killed it. */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String = "", val timedOut: Boolean = false)

/**
 * Runs one fixed program with a fixed argument list (no shell, no user or model text), with a 30 second limit,
 * stdout and stderr drained at the same time and bounded, the process killed on timeout. Stdout is decoded as UTF-8.
 */
fun interface CommandRunner {
    suspend fun run(executable: String, args: List<String>): CommandResult
}

/** Prefix of every PowerShell command: without it piped output turns non-ASCII text (Hebrew) into question marks. */
const val POWERSHELL_UTF8 = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "

private const val EVENT_DAYS = 7
private const val TOP_PROVIDERS = 5
private const val RECENT_EVENTS = 10
private const val MESSAGE_MAX = 200

/**
 * The read-only laptop health check (SPEC 11.4): computer, BIOS, battery, disks and recent Windows errors. Every
 * section runs on its own: one that fails or is denied becomes `{"ok":false,"error":"Not available"}` and the others are kept.
 * Nothing is changed on the machine, serial numbers are never selected, and the commands never contain double quotes.
 */
class HardwareCheck(
    private val runner: CommandRunner,
    private val clock: () -> Long,
    private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
    systemRoot: String = System.getenv("SystemRoot") ?: "C:\\Windows",
) : HardwareCheckRunner {
    private val powershell = "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    private val powercfg = "$systemRoot\\System32\\powercfg.exe"

    override suspend fun run(): HwCheckOutcome {
        val takenAt = clock()
        val result = buildJsonObject {
            put("takenAt", takenAt)
            put("computer", section { computer() })
            put("bios", section { bios() })
            put("battery", section { battery(takenAt) })
            put("disks", section { disks() })
            put("events", section { events() })
        }
        val json = result.toString()
        return HwCheckOutcome(json, hardwareSummary(json))
    }

    private inline fun section(block: () -> JsonObject): JsonObject =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notAvailable()
        }

    private fun notAvailable(): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", HW_NOT_AVAILABLE)
    }

    private suspend fun ps(command: String): CommandResult =
        runner.run(powershell, listOf("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", POWERSHELL_UTF8 + command))

    private fun CommandResult.okOrNull(): String? = if (timedOut || exitCode != 0) null else stdout

    private suspend fun computer(): JsonObject {
        val out = ps("Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer,Model,SystemType | ConvertTo-Json -Compress").okOrNull()
            ?: return notAvailable()
        val o = parseJsonRecords(out).firstOrNull() ?: return notAvailable()
        return buildJsonObject {
            put("ok", true)
            put("manufacturer", o.text("Manufacturer"))
            put("model", o.text("Model"))
            put("systemType", o.text("SystemType"))
        }
    }

    private suspend fun bios(): JsonObject {
        val out = ps("Get-CimInstance Win32_BIOS | Select-Object Manufacturer,SMBIOSBIOSVersion,ReleaseDate | ConvertTo-Json -Compress").okOrNull()
            ?: return notAvailable()
        val o = parseJsonRecords(out).firstOrNull() ?: return notAvailable()
        return buildJsonObject {
            put("ok", true)
            put("manufacturer", o.text("Manufacturer"))
            put("version", o.text("SMBIOSBIOSVersion"))
            val release = parseMsDate(o.text("ReleaseDate"))
            if (release != null) put("releaseDate", release) else put("releaseDate", JsonNull)
        }
    }

    private suspend fun battery(takenAt: Long): JsonObject {
        val file = File(tempDir, "wally-battery-$takenAt.xml")
        try {
            val result = runner.run(powercfg, listOf("/batteryreport", "/xml", "/output", file.absolutePath))
            if (result.timedOut || result.exitCode != 0 || !file.isFile) return notAvailable()
            val batteries = parseBatteryXml(file)
            return buildJsonObject {
                put("ok", true)
                put(
                    "batteries",
                    buildJsonArray {
                        for (b in batteries) add(
                            buildJsonObject {
                                put("designMwh", b.designMwh)
                                put("fullChargeMwh", b.fullChargeMwh)
                                put("cycleCount", b.cycleCount)
                                if (b.healthPercent != null) put("healthPercent", b.healthPercent) else put("healthPercent", JsonNull)
                            },
                        )
                    },
                )
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun disks(): JsonObject {
        val out = ps(
            "ConvertTo-Json -Compress -InputObject @(Get-PhysicalDisk | " +
                "Select-Object FriendlyName,MediaType,HealthStatus,OperationalStatus,Size)",
        ).okOrNull() ?: return notAvailable()
        val records = parseJsonRecords(out)
        return buildJsonObject {
            put("ok", true)
            put(
                "disks",
                buildJsonArray {
                    for (d in records) add(
                        buildJsonObject {
                            put("name", d.text("FriendlyName"))
                            put("mediaType", d.text("MediaType"))
                            put("health", d.text("HealthStatus"))
                            put("operational", d.text("OperationalStatus"))
                            val size = (d["Size"] as? JsonPrimitive)?.content?.toLongOrNull()
                            if (size != null) put("sizeBytes", size) else put("sizeBytes", JsonNull)
                        },
                    )
                },
            )
        }
    }

    private suspend fun events(): JsonObject {
        val out = ps(
            "try { ConvertTo-Json -Compress -InputObject @(Get-WinEvent -FilterHashtable " +
                "@{LogName='System';Level=1,2;StartTime=(Get-Date).AddDays(-$EVENT_DAYS)} -ErrorAction Stop | " +
                "Select-Object TimeCreated,Id,ProviderName,Level,Message) } catch { " +
                "if (\$_.FullyQualifiedErrorId -like 'NoMatchingEventsFound*') { '[]' } else { exit 3 } }",
        ).okOrNull() ?: return notAvailable()
        val records = parseJsonRecords(out)
        return summariseEvents(records)
    }

    private fun summariseEvents(records: List<JsonObject>): JsonObject {
        val byProvider = records.groupBy { it.text("ProviderName").ifBlank { "?" } }
        val top = byProvider.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<JsonObject>>> { it.value.size }.thenBy { it.key })
            .take(TOP_PROVIDERS)
        val recent = records
            .sortedByDescending { parseMsDate(it.text("TimeCreated")) ?: 0L }
            .take(RECENT_EVENTS)
        return buildJsonObject {
            put("ok", true)
            put("days", EVENT_DAYS)
            put("total", records.size)
            put(
                "byProvider",
                buildJsonArray {
                    for ((provider, list) in top) add(
                        buildJsonObject {
                            put("provider", provider)
                            put("count", list.size)
                            val topId = list.groupBy { it.text("Id") }.maxByOrNull { it.value.size }?.key?.toLongOrNull() ?: 0L
                            put("topId", topId)
                        },
                    )
                },
            )
            put(
                "recent",
                buildJsonArray {
                    for (e in recent) add(
                        buildJsonObject {
                            val time = parseMsDate(e.text("TimeCreated"))
                            if (time != null) put("time", time) else put("time", JsonNull)
                            put("id", e.text("Id").toLongOrNull() ?: 0L)
                            put("provider", e.text("ProviderName"))
                            put("level", e.text("Level").toLongOrNull() ?: 0L)
                            put("message", e.text("Message").replace(Regex("\\s+"), " ").trim().take(MESSAGE_MAX))
                        },
                    )
                },
            )
        }
    }
}

private fun JsonObject.text(key: String): String {
    val v = this[key]
    return if (v is JsonPrimitive && v !is JsonNull) v.content else ""
}

/**
 * PowerShell 5.1 `ConvertTo-Json` output as a list of objects: empty output (no results) is an empty list, one result is
 * a plain object, several are an array. Anything else throws.
 */
fun parseJsonRecords(text: String): List<JsonObject> {
    val trimmed = text.trim().removePrefix("\uFEFF")
    if (trimmed.isEmpty()) return emptyList()
    return when (val element: JsonElement = Json.parseToJsonElement(trimmed)) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.map { it as? JsonObject ?: throw IllegalArgumentException("not an object") }
        is JsonNull -> emptyList()
        else -> throw IllegalArgumentException("unexpected JSON")
    }
}

private val DATE_PATTERN = Regex("""/Date\((-?\d+)""")

/** Epoch ms from a PowerShell 5.1 `/Date(1785801600000)/` value (an offset after the digits is ignored); null otherwise. */
fun parseMsDate(value: String): Long? = DATE_PATTERN.find(value)?.groupValues?.get(1)?.toLongOrNull()

/** One battery of the `powercfg /batteryreport /xml` file, capacities in mWh. */
data class BatteryInfo(val designMwh: Long, val fullChargeMwh: Long, val cycleCount: Long, val healthPercent: Double?)

/** Reads every `Battery` element (namespace `http://schemas.microsoft.com/battery/2012`, matched by local name). */
fun parseBatteryXml(file: File): List<BatteryInfo> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.isXIncludeAware = false
    val doc = file.inputStream().use { factory.newDocumentBuilder().parse(it) }
    val nodes = doc.getElementsByTagNameNS("*", "Battery")
    val out = ArrayList<BatteryInfo>()
    for (i in 0 until nodes.length) {
        val battery = nodes.item(i) as? Element ?: continue
        fun number(name: String): Long =
            battery.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
        val design = number("DesignCapacity")
        val full = number("FullChargeCapacity")
        val health = if (design > 0) kotlin.math.round(full * 1000.0 / design) / 10.0 else null
        out += BatteryInfo(design, full, number("CycleCount"), health)
    }
    return out
}
