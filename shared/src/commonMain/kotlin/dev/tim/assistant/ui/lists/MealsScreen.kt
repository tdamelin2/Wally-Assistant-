package dev.tim.assistant.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Meal
import dev.tim.assistant.research.CODEX_LIMIT_ROW_TEXT
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.mealLookupErrorText
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class TodayMeals(val meals: List<Meal>, val lookups: Map<String, Ai_job?>)

/** Meals: today's estimated total, then today's meals and snacks with their estimate state. */
@Composable
fun MealsScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) { HistoryScreen(services, dev.tim.assistant.ui.NavTab.Meals) { historyOpen.value = false }; return }
    val nowMs = rememberMinuteClock()
    val today = jerusalemDay(nowMs).toString()
    val scope = rememberCoroutineScope()
    val data = rememberQuery(services, today) {
        val meals = services.repo.meals().filter { it.localDate == today }.sortedBy { it.eatenAt }
        TodayMeals(meals, meals.associate { it.id to services.repo.latestMealLookup(it.id) })
    }.value
    val hidden by services.undo.hidden.collectAsState()
    val meals = (data?.meals ?: emptyList()).filter { !hidden.hides(UndoTables.MEAL, it.id) }
    val total = mealDayTotal(meals)

    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "total") {
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(TOTAL_LABEL, style = MaterialTheme.typography.labelLarge)
                            Text(totalLine(total), style = MaterialTheme.typography.titleMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${total.protein} g protein", style = MaterialTheme.typography.bodyMedium)
                            Text("${total.carbs} g carbs", style = MaterialTheme.typography.bodyMedium)
                            Text("${total.fat} g fat", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (meals.isEmpty()) item(key = "empty") { Text(MEALS_EMPTY, modifier = Modifier.animateItem()) }
            items(meals, key = { it.id }) { meal ->
                val lookup = data?.lookups?.get(meal.id)
                LogRow(
                    onDone = null,
                    onEdit = { onOpenForm(FormTarget.Meal(meal)) },
                    onDelete = { services.undo.delete(UndoTables.MEAL, meal.id) },
                    modifier = Modifier.animateItem(),
                    endInset = if (mealStatus(meal, lookup, nowMs) == MealStatus.Failed) 96.dp else 0.dp,
                ) {
                    MealRow(meal, lookup, nowMs, { onOpenForm(FormTarget.Meal(meal)) }) {
                        scope.launch {
                            services.repo.queueJob("MEAL_LOOKUP", buildJsonObject { put("mealId", meal.id) }.toString())
                            services.syncInBackground()
                        }
                    }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_MEALS) { historyOpen.value = true } }
        }
    }
}

/** "≈ 1,140 kcal", with " (2 not estimated)" when some meals have no estimate. */
fun totalLine(total: MealTotal): String {
    val base = "≈ ${groupThousands(total.kcal)} kcal"
    return if (total.missing > 0) "$base (${total.missing} not estimated)" else base
}

@Composable
private fun MealRow(meal: Meal, lookup: Ai_job?, nowMs: Long, onEdit: () -> Unit, onLookup: () -> Unit) {
    val status = mealStatus(meal, lookup, nowMs)
    LogCard(Modifier.clickable(onClick = onEdit).heightIn(min = 60.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.widthIn(min = 56.dp)) {
                Text(jerusalemTime(meal.eatenAt), style = MaterialTheme.typography.labelLarge)
                Text(if (meal.type == "SNACK") "Snack" else "Meal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(meal.text, maxLines = 2, style = MaterialTheme.typography.bodyLarge)
                when (status) {
                    MealStatus.Estimated -> {
                        val source = if (meal.estimateModel == "research") " (research)" else ""
                        Text(
                            "≈ ${meal.kcal} kcal$source · P ${meal.proteinG ?: 0}g · C ${meal.carbsG ?: 0}g · F ${meal.fatG ?: 0}g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MealStatus.Estimating -> Text(ESTIMATING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MealStatus.Failed -> {
                        Text(FAILED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (lookup?.status == "FAILED" && lookup.error == CODEX_LIMIT_TEXT) {
                            Text(mealLookupErrorText(lookup.error) ?: CODEX_LIMIT_ROW_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                RejectedNote(meal.syncState)
            }
            if (status == MealStatus.Failed) TextButton(onClick = onLookup) { Text(LOOK_UP) }
        }
    }
}

private const val SEE_ALL_MEALS = "See all meals"
private const val TOTAL_LABEL = "Today, estimated"
private const val MEALS_EMPTY = "No meals or snacks yet"
private const val ESTIMATING = "Estimating nutrition…"
private const val FAILED = "Couldn't estimate this one"
private const val LOOK_UP = "Look up"

/** Texts of the Meals screen, for the label-uniqueness check. */
internal val mealsLabels: List<String> = listOf(TOTAL_LABEL, MEALS_EMPTY, LOOK_UP, SEE_ALL_MEALS)
