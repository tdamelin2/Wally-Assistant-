package dev.tim.assistant.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.db.Spending
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.lists.shekel
import kotlinx.coroutines.launch

@Composable
fun SpendingForm(services: AppServices, existing: Spending?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(existing?.let { formatAgorot(it.amountAgorot) } ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: SPENDING_CATEGORIES.first()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember { mutableStateOf(existing?.localDate ?: todayLocal()) }

    val agorot = parseAgorot(amount)
    val amountError = amount.isNotEmpty() && agorot == null
    var dateValid by remember { mutableStateOf(true) }
    val dateOk = dateValid && isValidDate(date)

    FormScaffold(
        title = if (existing == null) "New spending" else "Edit spending",
        canSave = agorot != null && dateOk,
        saveLabel = if (agorot != null) "Save ${shekel(agorot)} · $category" else "Save",
        onCancel = onDone,
        onSave = {
            if (agorot != null) {
                scope.launch {
                    services.repo.saveSpending(existing?.id, agorot, category, note, date.trim())
                    onDone()
                    services.syncInBackground()
                }
            }
        },
    ) {
        WallyTextField(
            multiline = false,
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            prefix = { Text("₪", style = MaterialTheme.typography.headlineMedium) },
            textStyle = MaterialTheme.typography.headlineMedium,
            isError = amountError || amount.isEmpty(),
            supportingText = {
                if (amountError || amount.isEmpty()) Text("Enter an amount above 0", color = MaterialTheme.colorScheme.error)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Category", style = MaterialTheme.typography.labelLarge)
        for (rowOfCategories in SPENDING_CATEGORIES.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in rowOfCategories) {
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
        }
        WallyTextField(
            multiline = true,
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Date", date, { date = it }, onValidChange = { dateValid = it })
    }
}
