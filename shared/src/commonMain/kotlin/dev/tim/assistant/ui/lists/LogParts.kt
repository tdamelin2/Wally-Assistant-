package dev.tim.assistant.ui.lists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.REJECTED
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.WallyColors
import dev.tim.assistant.ui.forms.formatAgorot
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** The current time in ms, refreshed every minute while the screen is shown (rolls days over and ages meals). */
@Composable
fun rememberMinuteClock(): Long {
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = Clock.System.now().toEpochMilliseconds()
            delay(60_000)
        }
    }
    return nowMs
}

/** The Jerusalem calendar day of [epochMs]. */
fun jerusalemDay(epochMs: Long): LocalDate = LocalDate.parse(localDateOf(Instant.fromEpochMilliseconds(epochMs)))

/** "HH:mm" of [epochMs] in Jerusalem. */
fun jerusalemTime(epochMs: Long): String =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(JERUSALEM).time.toString().take(5)

/** A shekel amount: "₪412" for whole shekels, "₪412.50" otherwise. */
fun shekel(agorot: Long): String = if (agorot % 100 == 0L) "₪${agorot / 100}" else "₪${formatAgorot(agorot)}"

/** 1140 becomes "1,140". */
fun groupThousands(n: Long): String {
    val digits = kotlin.math.abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** A white rounded card with the thin outline of the canvas. */
@Composable
fun LogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Five dots, [filled] of them green (the check-in and dream mood meters). */
@Composable
fun Dots(filled: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..5) {
            Box(
                Modifier.size(10.dp).background(
                    if (i <= filled) MaterialTheme.colorScheme.primary else WallyColors.emptyDot,
                    CircleShape,
                ),
            )
        }
    }
}

/** The line shown under a row the server turned down. */
@Composable
fun RejectedNote(syncState: String) {
    if (syncState != REJECTED) return
    Text(
        "Rejected by server — edit to retry",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
