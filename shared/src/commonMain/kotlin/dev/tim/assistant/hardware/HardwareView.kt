package dev.tim.assistant.hardware

import dev.tim.assistant.data.dateTimeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What a finished check hands to the app: the stored JSON and the one-line summary. */
data class HwCheckOutcome(val resultJson: String, val summary: String)

/** Runs one hardware check. Only the laptop has one (null on Android, where there is no Run check). */
fun interface HardwareCheckRunner {
    suspend fun run(): HwCheckOutcome
}

/** The marker a section carries when it could not be read. */
const val HW_NOT_AVAILABLE = "Not available"

/** Longest summary line stored with a check (the server accepts 300). */
const val HW_SUMMARY_MAX = 300

private fun parseObject(resultJson: String): JsonObject? =
    try {
        Json.parseToJsonElement(resultJson) as? JsonObject
    } catch (e: Exception) {
        null
    }

private fun JsonObject.section(name: String): JsonObject? = (this[name] as? JsonObject)?.takeIf { it["ok"].asBoolean() }

private fun kotlinx.serialization.json.JsonElement?.asBoolean(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun kotlinx.serialization.json.JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun kotlinx.serialization.json.JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** When the check was taken (epoch ms), or null when the JSON does not say. */
fun hwTakenAt(resultJson: String): Long? = parseObject(resultJson)?.get("takenAt").asLong()

/** The answer text of a finished HW_EXPLAIN job (`{"text":".."}`), or null. */
fun hwExplainText(jobResultJson: String?): String? =
    jobResultJson?.let { parseObject(it)?.get("text").asText()?.takeIf { text -> text.isNotBlank() } }

/** "96.6" style percent text without a trailing ".0". */
internal fun percentText(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

/** The one-line summary of a check, for the list: battery health, disk health and the Windows error count. */
fun hardwareSummary(resultJson: String): String {
    val root = parseObject(resultJson) ?: return HW_NOT_AVAILABLE
    val parts = ArrayList<String>()
    val battery = root.section("battery")
    parts += when {
        battery == null -> "battery n/a"
        battery.objects("batteries").isEmpty() -> "no battery"
        else -> battery.objects("batteries").first().let { b ->
            val health = b["healthPercent"].asDouble()
            val cycles = b["cycleCount"].asLong()
            (if (health != null) "battery ${percentText(health)}%" else "battery n/a") + (if (cycles != null) ", $cycles cycles" else "")
        }
    }
    val disks = root.section("disks")
    parts += when {
        disks == null -> "disks n/a"
        disks.objects("disks").isEmpty() -> "no disks found"
        else -> {
            val bad = disks.objects("disks").firstOrNull { it["health"].asText() != "Healthy" }
            if (bad == null) "disks healthy" else "disk ${bad["name"].asText() ?: "?"}: ${bad["health"].asText() ?: "unknown"}"
        }
    }
    val events = root.section("events")
    parts += when {
        events == null -> "errors n/a"
        else -> {
            val total = events["total"].asLong() ?: 0L
            val days = events["days"].asLong() ?: 7L
            if (total == 0L) "no Windows errors in $days days" else "$total Windows error${if (total == 1L) "" else "s"} in $days days"
        }
    }
    return parts.joinToString(" · ").take(HW_SUMMARY_MAX)
}

/** One titled block of the section view. */
data class HwSectionView(val title: String, val lines: List<String>)

private fun gigabytes(bytes: Long): String = "${(bytes + 500_000_000L) / 1_000_000_000L} GB"

/** The check's sections as readable lines for the detail view. A section that could not be read says [HW_NOT_AVAILABLE]. */
fun hardwareSections(resultJson: String): List<HwSectionView> {
    val root = parseObject(resultJson) ?: return listOf(HwSectionView("Check", listOf(HW_NOT_AVAILABLE)))
    fun block(name: String, title: String, build: (JsonObject) -> List<String>): HwSectionView {
        val s = root.section(name) ?: return HwSectionView(title, listOf(HW_NOT_AVAILABLE))
        return HwSectionView(title, build(s))
    }
    return listOf(
        block("computer", "Computer") { s ->
            listOfNotNull(
                listOfNotNull(s["manufacturer"].asText(), s["model"].asText()).joinToString(" ").ifBlank { null },
                s["systemType"].asText(),
            )
        },
        block("bios", "BIOS") { s ->
            listOfNotNull(
                s["version"].asText()?.let { "Version $it" },
                s["releaseDate"].asLong()?.let { "Released ${dateTimeLabel(it).substringBefore(' ')}" },
            )
        },
        block("battery", "Battery") { s ->
            val list = s.objects("batteries")
            if (list.isEmpty()) {
                listOf("No battery found")
            } else {
                list.map { b ->
                    val health = b["healthPercent"].asDouble()
                    listOfNotNull(
                        health?.let { "Health ${percentText(it)}%" },
                        b["fullChargeMwh"].asLong()?.let { full -> b["designMwh"].asLong()?.let { "$full of $it mWh" } },
                        b["cycleCount"].asLong()?.let { "$it cycles" },
                    ).joinToString(", ")
                }
            }
        },
        block("disks", "Disks") { s ->
            val list = s.objects("disks")
            if (list.isEmpty()) {
                listOf("No disks found")
            } else {
                list.map { d ->
                    listOfNotNull(
                        d["name"].asText(),
                        d["mediaType"].asText(),
                        listOfNotNull(d["health"].asText(), d["operational"].asText()).joinToString(" / ").ifBlank { null },
                        d["sizeBytes"].asLong()?.let { gigabytes(it) },
                    ).joinToString(", ")
                }
            }
        },
        block("events", "Windows errors, last 7 days") { s ->
            val total = s["total"].asLong() ?: 0L
            if (total == 0L) {
                listOf("No errors")
            } else {
                val lines = ArrayList<String>()
                lines += "$total in total"
                for (p in s.objects("byProvider")) lines += "${p["provider"].asText() ?: "?"}: ${p["count"].asLong() ?: 0} (id ${p["topId"].asLong() ?: 0})"
                for (e in s.objects("recent")) {
                    val at = e["time"].asLong()?.let { dateTimeLabel(it) } ?: "?"
                    lines += "$at ${e["provider"].asText() ?: "?"} ${e["id"].asLong() ?: 0}: ${e["message"].asText() ?: ""}".trim()
                }
                lines
            }
        },
    )
}
