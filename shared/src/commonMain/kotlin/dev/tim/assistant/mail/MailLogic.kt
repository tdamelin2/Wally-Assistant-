package dev.tim.assistant.mail

import dev.tim.assistant.data.MailActionDraft
import dev.tim.assistant.model.MailLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Longest subject, body and address the server accepts. */
const val MAIL_SUBJECT_MAX = 500
const val MAIL_BODY_MAX = 50_000
const val MAIL_ADDRESS_MAX = 320
const val MAIL_REASON_MAX = 500

/** The bare address of a header value: `Name <a@b.com>` gives `a@b.com`, a plain address is returned trimmed. */
fun extractAddress(header: String): String {
    val open = header.lastIndexOf('<')
    val close = header.indexOf('>', open + 1)
    return if (open >= 0 && close > open) header.substring(open + 1, close).trim() else header.trim().trim('"')
}

private val RE_PREFIX = Regex("^\\s*re\\s*:\\s*", RegexOption.IGNORE_CASE)

/** [subject] without one leading "Re:". */
fun stripRe(subject: String): String = subject.replaceFirst(RE_PREFIX, "").trim()

/** "Re: <original>", without a second "Re:" when the original already starts with one. */
fun replySubject(original: String): String {
    val base = original.trim()
    return if (RE_PREFIX.containsMatchIn(base)) base else "Re: $base"
}

/** True when a reply with [draftSubject] no longer matches [originalSubject], so it goes out as a new conversation. */
fun startsNewConversation(draftSubject: String, originalSubject: String): Boolean =
    stripRe(draftSubject) != stripRe(originalSubject)

private val ADDRESS = Regex("^[^\\s@,;<>\"]+@[^\\s@,;<>\"]+\\.[^\\s@,;<>\"]+$")

/** One plain address: no spaces, commas, angle brackets, a dot after the "@", at most 320 characters. */
fun isValidRecipient(to: String): Boolean = to.trim().let { it.length <= MAIL_ADDRESS_MAX && ADDRESS.matches(it) }

/** Whether the editor's Send button is enabled. */
fun canSendDraft(to: String, subject: String, body: String): Boolean =
    isValidRecipient(to) && subject.length <= MAIL_SUBJECT_MAX && '\n' !in subject && '\r' !in subject && body.length <= MAIL_BODY_MAX

/** What the draft list and the editor show under a draft. */
fun draftStatusLine(status: String, error: String?): String = when (status) {
    "DRAFT" -> "Draft"
    "APPROVED", "SENDING" -> "Sending…"
    "SENT" -> "Sent"
    "FAILED" -> "Failed: ${error?.takeIf { it.isNotBlank() } ?: "unknown error"}"
    "UNKNOWN" -> "Unknown - check Gmail Sent"
    else -> status
}

/** The editor can change a draft only while it is a DRAFT or a FAILED one. */
fun draftIsEditable(status: String): Boolean = status == "DRAFT" || status == "FAILED"

/** Delete is offered for DRAFT, FAILED, SENT and UNKNOWN drafts (not while the server may be sending). */
fun draftCanDelete(status: String): Boolean = status in setOf("DRAFT", "FAILED", "SENT", "UNKNOWN")

/** Delete is offered for suggestions that are handled (REJECTED, DONE, FAILED). */
fun actionCanDelete(status: String): Boolean = status in setOf("REJECTED", "DONE", "FAILED")

/** "Label \"Bills\"" or "Archive". */
fun actionTitle(action: String, labelName: String?): String = when (action) {
    "LABEL" -> "Label \"${labelName ?: "?"}\""
    "ARCHIVE" -> "Archive"
    else -> action
}

/** What the suggestion list shows after the title ("" while it still waits for Approve or Reject). */
fun actionStatusLine(status: String): String = when (status) {
    "APPROVED" -> "Approved - waiting for the server"
    "REJECTED" -> "Rejected"
    "DONE" -> "Done"
    "FAILED" -> "Failed"
    else -> ""
}

private val TRIAGE_ACTIONS = setOf("LABEL", "ARCHIVE")

/**
 * The model's `{"actions":[{"gmailId","action","labelId","reason"}]}` reduced to what is safe to suggest. Dropped:
 * unknown gmailId (not in [mailIds]), unknown labelId (not in [labels]), LABEL without labelId, any other action,
 * and repeats. [labelName] always comes from [labels]. ARCHIVE never carries a label. Returns null when [text] is not the
 * expected JSON at all; an empty list when nothing survived.
 */
fun parseTriage(text: String, mailIds: Set<String>, labels: List<MailLabel>): List<MailActionDraft>? {
    val root = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val array = root["actions"] as? JsonArray ?: return null
    val byId = labels.associateBy { it.id }
    val seen = HashSet<Triple<String, String, String?>>()
    val out = ArrayList<MailActionDraft>()
    for (element in array) {
        val item = element as? JsonObject ?: continue
        val gmailId = item.string("gmailId") ?: continue
        if (gmailId !in mailIds) continue
        val action = item.string("action")?.trim()?.uppercase() ?: continue
        if (action !in TRIAGE_ACTIONS) continue
        val labelId = item.string("labelId")?.takeIf { it.isNotBlank() }
        val label = if (action == "LABEL") byId[labelId ?: continue] ?: continue else null
        val reason = (item.string("reason") ?: "").trim().take(MAIL_REASON_MAX)
        if (seen.add(Triple(gmailId, action, label?.id))) {
            out += MailActionDraft(gmailId, action, label?.id, label?.name, reason)
        }
    }
    return out
}

private fun JsonObject.string(key: String): String? {
    val value = this[key]
    return if (value is JsonPrimitive && value !is JsonNull && value.isString) value.content else null
}
