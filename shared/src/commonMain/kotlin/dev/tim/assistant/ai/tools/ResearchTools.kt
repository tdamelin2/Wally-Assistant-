package dev.tim.assistant.ai.tools

import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Research
import dev.tim.assistant.research.CODEX_LIMIT_NOTICE
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the phone says after queueing research: the laptop runs the job. */
const val RESEARCH_QUEUED_TEXT = "Queued research - answer appears when Wally is open on the laptop"

/** The laptop runs the research itself, so it says it is looking the answer up. */
const val RESEARCH_QUEUED_LAPTOP_TEXT = "Looking this up with Codex - the answer will appear here in about 20 seconds"

/** The queued-research text for this device: [runsJobs] is true on the laptop (the desktop app). */
fun researchQueuedText(runsJobs: Boolean): String = if (runsJobs) RESEARCH_QUEUED_LAPTOP_TEXT else RESEARCH_QUEUED_TEXT

/** The stored sources of a research row: a JSON array of URLs, empty when unreadable. */
fun researchSources(row: Research): List<String> =
    try {
        Json.decodeFromString(ListSerializer(String.serializer()), row.sources)
    } catch (e: Exception) {
        emptyList()
    }

/** Answer text followed by its sources, as the model and the screen show it. */
fun answerWithSources(row: Research): String {
    val sources = researchSources(row)
    val text = row.answerText.trim()
    return if (sources.isEmpty()) text else text + "\nSources: " + sources.joinToString(", ")
}

/**
 * What to tell Tim about a research [job]: the answer with sources once it is DONE, the failure once it is FAILED
 * (the Codex-limit failure becomes the plain "out of usage" message), null while it is still queued or running.
 */
fun researchJobReport(repo: Repository, job: Ai_job): String? = when (job.status) {
    "DONE" -> {
        val id = try {
            Json.parseToJsonElement(job.resultJson ?: "{}").jsonObject["researchId"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
        val row = id?.let { repo.researchById(it) }
        if (row != null) answerWithSources(row) else "Research finished, but the answer was deleted."
    }
    "FAILED" -> if (job.error == CODEX_LIMIT_TEXT) CODEX_LIMIT_NOTICE else "Research failed: ${job.error ?: "unknown error"}"
    else -> null
}
