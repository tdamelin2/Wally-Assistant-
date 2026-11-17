package dev.tim.assistant.usage

import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Usage_session
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

enum class Label { PRODUCTIVE, UNPRODUCTIVE, NEUTRAL, UNSORTED }

const val MATCH_PACKAGE = "PACKAGE"
const val MATCH_EXE = "EXE"
const val MATCH_TITLE = "TITLE_CONTAINS"

/** Minutes in [Label]-less form: an app and the milliseconds it was in front. */
data class AppTotal(val app: String, val ms: Long)

/** A rule's pattern as stored: trimmed and lowercase. */
fun normalizePattern(pattern: String): String = pattern.trim().lowercase()

fun ruleId(matchType: String, pattern: String): String = "rule:$matchType:$pattern"

/** The match type that fits a device's app names: EXE on the laptop, PACKAGE on the phone. */
fun appMatchType(device: String): String = if (device == DEVICE_LAPTOP) MATCH_EXE else MATCH_PACKAGE

private fun ruleLabel(rule: App_rule): Label? =
    Label.entries.firstOrNull { it != Label.UNSORTED && it.name == rule.label }

/**
 * The label of one session. Only non-deleted rules count. A title rule (the longest matching pattern wins,
 * a tie goes to the lowest rule id) beats the app rule (EXE for the laptop, PACKAGE for the phone).
 */
fun label(session: Usage_session, rules: List<App_rule>): Label {
    val active = rules.filter { it.deleted == 0L && ruleLabel(it) != null }
    val title = session.title?.lowercase()
    if (title != null) {
        val hit = active.filter { it.matchType == MATCH_TITLE && it.pattern.isNotEmpty() && title.contains(it.pattern.lowercase()) }
            .sortedWith(compareByDescending<App_rule> { it.pattern.length }.thenBy { it.id })
            .firstOrNull()
        if (hit != null) return ruleLabel(hit)!!
    }
    val appType = appMatchType(session.device)
    val app = session.app.lowercase()
    val appRule = active.filter { it.matchType == appType && it.pattern.lowercase() == app }.minByOrNull { it.id }
    return appRule?.let { ruleLabel(it) } ?: Label.UNSORTED
}

private fun inScope(s: Usage_session, device: String?, dates: Collection<String>) =
    s.deleted == 0L && (device == null || s.device == device) && s.localDate in dates

/**
 * Minutes per date and label (device null = all devices; floor of the summed milliseconds). A session counts whole on its start date.
 * Every requested date and every label is present.
 */
fun dayTotals(
    sessions: List<Usage_session>,
    rules: List<App_rule>,
    device: String?,
    dates: Collection<String>,
): Map<String, Map<Label, Long>> {
    val ms = HashMap<String, MutableMap<Label, Long>>()
    for (s in sessions) {
        if (!inScope(s, device, dates)) continue
        val byLabel = ms.getOrPut(s.localDate) { HashMap() }
        val l = label(s, rules)
        byLabel[l] = (byLabel[l] ?: 0L) + (s.endAt - s.startAt)
    }
    return dates.associateWith { d -> Label.entries.associateWith { l -> (ms[d]?.get(l) ?: 0L) / 60_000L } }
}

private fun totalsByApp(sessions: List<Usage_session>, device: String?, dates: Collection<String>, keep: (Usage_session) -> Boolean) =
    sessions.filter { inScope(it, device, dates) && keep(it) }
        .groupBy { it.app }
        .map { (app, list) -> AppTotal(app, list.sumOf { it.endAt - it.startAt }) }
        .sortedWith(compareByDescending<AppTotal> { it.ms }.thenBy { it.app })

/** The apps with the most time, longest first, ties by name. */
fun topApps(sessions: List<Usage_session>, device: String?, dates: Collection<String>, limit: Int = 10): List<AppTotal> =
    totalsByApp(sessions, device, dates) { true }.take(limit)

/** Apps with at least one session that no rule labels, with the time of those sessions. */
fun unsortedApps(
    sessions: List<Usage_session>,
    rules: List<App_rule>,
    device: String?,
    dates: Collection<String>,
): List<AppTotal> = totalsByApp(sessions, device, dates) { label(it, rules) == Label.UNSORTED }

/** Today and the six days before it (Jerusalem dates), newest first. */
fun last7Dates(today: String): List<String> {
    val t = LocalDate.parse(today)
    return (0..6).map { t.minus(DatePeriod(days = it)).toString() }
}
