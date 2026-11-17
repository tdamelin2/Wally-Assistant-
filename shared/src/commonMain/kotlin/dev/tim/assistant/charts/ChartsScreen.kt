package dev.tim.assistant.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.forms.formatAgorot
import dev.tim.assistant.ui.isLaptop
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.rememberQuery

private class ChartsData(
    val checkins: CheckinSeries,
    val categories: List<Pair<String, Long>>,
    val months: List<Pair<String, Long>>,
    val weeks: List<Pair<String, Int>>,
)

private fun shekels(agorot: Long) = "₪" + formatAgorot(agorot)

/** Window width (dp) from which the laptop shows the four charts in two columns. */
internal const val CHARTS_TWO_COLUMN_DP = 760

/** The four charts, built from the local database only (works with the server off) and refreshed on every local change. */
@Composable
fun ChartsScreen(services: AppServices) {
    val data = rememberQuery(services) {
        val today = todayLocal()
        ChartsData(
            checkinSeries(services.repo.checkins(), today),
            spendingByCategory(services.repo.spending(), today),
            monthlyTotals(services.repo.spending(), today),
            tasksDonePerWeek(services.repo.allTasksForCharts(), today),
        )
    }.value

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = isLaptop && maxWidth.value >= CHARTS_TWO_COLUMN_DP
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (data == null) {
                Text("Loading")
                return@Column
            }
            val cards: List<@Composable () -> Unit> = listOf(
                { CheckinCard(data) },
                { CategoryCard(data) },
                { MonthsCard(data) },
                { WeeksCard(data) },
            )
            if (twoColumns) {
                cards.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { card -> Box(Modifier.weight(1f)) { card() } }
                    }
                }
            } else {
                cards.forEach { it() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckinCard(data: ChartsData) {
    LogCard {
        Text("Check-in scores, last 30 days", style = MaterialTheme.typography.titleMedium)
        LineChart(
            series = listOf(data.checkins.mood, data.checkins.energy, data.checkins.sleepQuality, data.checkins.stress),
            labels = data.checkins.labels,
            yRange = 1..5,
            dashed = listOf(false, false, true, true),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Mood", "Energy", "Sleep", "Stress").forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 16.dp, height = 3.dp).background(SERIES_COLORS[i]))
                    Text(name + if (i >= 2) " (dashed)" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (data.checkins.mood.all { it == null }) Text("No check-ins in the last 30 days", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CategoryCard(data: ChartsData) {
    LogCard {
        Text("Spending by category, this month", style = MaterialTheme.typography.titleMedium)
        BarChart(data.categories.map { it.second }, data.categories.map { it.first })
        Text(
            data.categories.joinToString("   ") { "${it.first} ${shekels(it.second)}" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MonthsCard(data: ChartsData) {
    LogCard {
        Text("Spending per month, last 6 months", style = MaterialTheme.typography.titleMedium)
        BarChart(data.months.map { it.second }, data.months.map { it.first.substring(5) })
        Text(
            data.months.joinToString("   ") { "${it.first} ${shekels(it.second)}" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WeeksCard(data: ChartsData) {
    LogCard {
        Text("Tasks done per week (weeks start Sunday), last 8 weeks", style = MaterialTheme.typography.titleMedium)
        BarChart(data.weeks.map { it.second.toLong() }, data.weeks.map { it.first.substring(5) })
        Text(
            data.weeks.joinToString("   ") { "${it.first.substring(5)}: ${it.second}" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Headings of the Charts screen, for the label-uniqueness check. */
internal val chartsLabels: List<String> = listOf(
    "Check-in scores, last 30 days", "Spending by category, this month", "Spending per month, last 6 months",
    "Tasks done per week (weeks start Sunday), last 8 weeks", "Mood", "Energy", "Sleep (dashed)", "Stress (dashed)",
)
