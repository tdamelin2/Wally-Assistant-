package dev.tim.assistant.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.study.QuizQuestion
import dev.tim.assistant.study.quizQuestionsFrom
import dev.tim.assistant.study.quizScore
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch

/** Flash cards of one file: tap the card to flip it, then mark Knew it / Didn't. */
@Composable
fun CardsView(services: AppServices, fileId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var index by remember(fileId) { mutableIntStateOf(0) }
    var flipped by remember(fileId) { mutableStateOf(false) }
    val cards = rememberQuery(services, fileId) { services.repo.cardsForFile(fileId) }.value ?: emptyList()
    val file = rememberQuery(services, fileId) { services.repo.studyFileById(fileId) }.value

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { BackIcon() }
            Text(file?.name ?: "Cards", style = MaterialTheme.typography.titleMedium.merge(ContentText), modifier = Modifier.weight(1f))
        }
        if (cards.isEmpty()) {
            Text("No cards")
            return@Column
        }
        val at = index.coerceIn(0, cards.size - 1)
        val card = cards[at]
        Text("Card ${at + 1} of ${cards.size}", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth().heightIn(min = 180.dp).clickable { flipped = !flipped }) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(if (flipped) "Answer" else "Question", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (flipped) card.back else card.front,
                    style = MaterialTheme.typography.titleLarge.merge(ContentText),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        Text("Tap the card to flip it", style = MaterialTheme.typography.bodySmall)
        card.lastResult?.let { Text(if (it == "KNEW") "Last time: knew it" else "Last time: didn't know", style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { services.repo.markCard(card.id, "KNEW"); services.syncInBackground() }
                flipped = false
                if (at < cards.size - 1) index = at + 1
            }) { Text("Knew it") }
            OutlinedButton(onClick = {
                scope.launch { services.repo.markCard(card.id, "DIDNT"); services.syncInBackground() }
                flipped = false
                if (at < cards.size - 1) index = at + 1
            }) { Text("Didn't") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { flipped = false; index = at - 1 }, enabled = at > 0) { Text("Previous") }
            OutlinedButton(onClick = { flipped = false; index = at + 1 }, enabled = at < cards.size - 1) { Text("Next") }
        }
    }
}

/** One quiz, one question at a time. The quiz is read once per id, so a sync never changes it while Tim answers. */
@Composable
fun QuizView(services: AppServices, quizId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val questions: List<QuizQuestion> = remember(quizId) { services.repo.quizById(quizId)?.let { quizQuestionsFrom(it.questionsJson) } ?: emptyList() }
    val answers = remember(quizId) { mutableStateOf(List<Int?>(questions.size) { null }) }
    var index by remember(quizId) { mutableIntStateOf(0) }
    var finished by remember(quizId) { mutableStateOf<Int?>(null) }
    var saveError by remember(quizId) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { BackIcon() }
            Text("Practice quiz", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (questions.isEmpty()) {
            Text("This quiz is not available")
            return@Column
        }
        val score = finished
        if (score != null) {
            Text("Score: $score / ${questions.size}", style = MaterialTheme.typography.headlineSmall)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            questions.forEachIndexed { i, q ->
                val chosen = answers.value[i]
                Text(
                    "${i + 1}. ${q.q}",
                    style = MaterialTheme.typography.bodyMedium.merge(ContentText),
                )
                Text(
                    (if (chosen == q.answerIndex) "Right: " else "Right answer: ") + q.options[q.answerIndex],
                    style = MaterialTheme.typography.bodySmall.merge(ContentText),
                )
            }
            Button(onClick = onClose) { Text("Done") }
            return@Column
        }
        val at = index.coerceIn(0, questions.size - 1)
        val q = questions[at]
        Text("Question ${at + 1} of ${questions.size}", style = MaterialTheme.typography.bodySmall)
        Text(q.q, style = MaterialTheme.typography.titleMedium.merge(ContentText))
        q.options.forEachIndexed { i, option ->
            Row(
                Modifier.fillMaxWidth().clickable { answers.value = answers.value.toMutableList().also { it[at] = i } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = answers.value[at] == i, onClick = { answers.value = answers.value.toMutableList().also { it[at] = i } })
                Text(option, style = MaterialTheme.typography.bodyMedium.merge(ContentText))
            }
        }
        val last = at == questions.size - 1
        Button(
            enabled = answers.value[at] != null,
            onClick = {
                if (!last) {
                    index = at + 1
                } else {
                    val total = quizScore(questions, answers.value)
                    finished = total
                    scope.launch {
                        try {
                            services.repo.saveQuizResult(quizId, total)
                            services.syncInBackground()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saveError = "Could not save the score"
                        }
                    }
                }
            },
        ) { Text(if (last) "Finish" else "Next") }
    }
}
