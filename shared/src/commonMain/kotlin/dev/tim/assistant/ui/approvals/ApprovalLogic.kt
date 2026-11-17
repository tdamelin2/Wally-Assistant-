package dev.tim.assistant.ui.approvals

import dev.tim.assistant.data.APPROVAL_EXPIRED
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.data.APPROVAL_VALID_MS
import dev.tim.assistant.data.approvalDisplayStatus
import dev.tim.assistant.db.Approval
import dev.tim.assistant.research.RESEARCH_JOB_KIND
import dev.tim.assistant.research.MEAL_LOOKUP_KIND

/** The kinds of job whose failure with the Codex-limit text switches the "out of usage" banner on. */
val CODEX_JOB_KINDS: List<String> = listOf(RESEARCH_JOB_KIND, MEAL_LOOKUP_KIND)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Both buttons are enabled only for a PENDING approval that has not expired at [now]. */
fun approvalCanDecide(row: Approval, now: Long): Boolean = approvalDisplayStatus(row, now) == APPROVAL_PENDING

/** The status word shown on a card (EXPIRED is computed here, never stored). */
fun approvalStatusLabel(row: Approval, now: Long): String = when (val s = approvalDisplayStatus(row, now)) {
    APPROVAL_PENDING -> "Waiting"
    APPROVAL_EXPIRED -> "Expired"
    else -> s.lowercase().replaceFirstChar { it.uppercase() }
}

/** How long a PENDING approval still has: "Expires in 6 d 3 h", "Expires in 5 h 10 min", "Expires in 3 min", or "Expired". */
fun approvalExpiryText(row: Approval, now: Long): String {
    val left = row.createdAt + APPROVAL_VALID_MS - now
    if (left < 0) return "Expired"
    val days = left / DAY_MS
    val hours = (left % DAY_MS) / HOUR_MS
    val minutes = (left % HOUR_MS) / MINUTE_MS
    return "Expires in " + when {
        days > 0 -> "$days d $hours h"
        hours > 0 -> "$hours h $minutes min"
        else -> "${maxOf(minutes, 1)} min"
    }
}

/** Pending approvals that are still valid first (oldest first), then everything else newest first; [rows] is newest first. */
fun orderedApprovals(rows: List<Approval>, now: Long): List<Approval> {
    val (open, rest) = rows.partition { approvalCanDecide(it, now) }
    return open.sortedBy { it.createdAt } + rest
}

/** The banner text for the Research section, or null (see [dev.tim.assistant.research.codexLimitNotice]). */
fun researchBanner(lastLimitFailureAt: Long?, now: Long): String? =
    dev.tim.assistant.research.codexLimitNotice(lastLimitFailureAt, now)
