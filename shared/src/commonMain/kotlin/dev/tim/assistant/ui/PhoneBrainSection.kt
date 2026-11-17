package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.tim.assistant.ai.PHONE_BRAIN_ENABLED_KEY
import dev.tim.assistant.ai.PhoneBrainGate
import dev.tim.assistant.ai.PhoneBrainResult
import dev.tim.assistant.ai.PhoneBrainStatus
import dev.tim.assistant.ai.ReplyStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.TimeSource

/** The switch's stored value: on unless it was set to "false" (the default is on). */
fun phoneBrainEnabledFrom(stored: String?): Boolean = stored != "false"

/** The text stored for the switch. */
fun phoneBrainEnabledValue(enabled: Boolean): String = if (enabled) "true" else "false"

/** The model line: name and size in GB with one decimal, or where to find the setup steps. */
fun phoneBrainModelLine(status: PhoneBrainStatus): String {
    val name = status.modelName ?: return "No model file. See ops/phone-model.md"
    val bytes = status.sizeBytes ?: return "Model: $name"
    val tenths = (bytes * 10 + 500_000_000L) / 1_000_000_000L
    return "Model: $name, ${tenths / 10}.${tenths % 10} GB"
}

/** Why the Test button is off, or null when it may run. The switch is ignored on purpose (the test is the way to try it). */
fun phoneBrainTestBlocker(modelPresent: Boolean, batteryPercent: Int, powerSave: Boolean): String? = when {
    !modelPresent -> "Needs the model file."
    !PhoneBrainGate.allowed(enabled = true, modelPresent = true, batteryPercent = batteryPercent, powerSave = false) ->
        "Battery is below ${PhoneBrainGate.MIN_BATTERY_PERCENT}%."
    powerSave -> "Power saving is on."
    else -> null
}

/** The numbers under the Test button: time to the first visible word, tokens per second and the backend when known. */
fun phoneBrainStatsText(firstWordMs: Long?, result: PhoneBrainResult): String {
    val parts = mutableListOf<String>()
    parts += if (firstWordMs != null) "First word: $firstWordMs ms" else "First word: none"
    result.tokensPerSecond?.let { parts += "Speed: ${(it * 10).toLong() / 10}.${(it * 10).toLong() % 10} tokens/s" }
    result.backend?.let { parts += "Backend: $it" }
    return parts.joinToString(" | ")
}

/** The words of the test reply once generation ended: the complete reply, or a failure line. */
fun phoneBrainTestReply(raw: String?): String {
    if (raw == null) return "The phone could not answer."
    val reply = ReplyStream.extractReply(raw).trim()
    return reply.ifEmpty { "The phone gave no reply." }
}

private const val TEST_SYSTEM = "You are Wally."
private const val TEST_USER = "Say hello to Tim in one short sentence."

private val testSchema = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf("reply" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
        "required" to JsonArray(listOf(JsonPrimitive("reply"))),
    ),
)

/** The phone's own-brain settings: the switch, the model file and a Test button that runs on the phone alone. */
@Composable
internal fun PhoneBrainSection(services: AppServices) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(phoneBrainEnabledFrom(services.repo.setting(PHONE_BRAIN_ENABLED_KEY))) }
    var status by remember { mutableStateOf(services.phoneBrain.status()) }
    var battery by remember { mutableIntStateOf(services.deviceState.batteryPercent()) }
    var powerSave by remember { mutableStateOf(services.deviceState.powerSave()) }
    var running by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        status = services.phoneBrain.status()
        battery = services.deviceState.batteryPercent()
        powerSave = services.deviceState.powerSave()
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                services.repo.putSetting(PHONE_BRAIN_ENABLED_KEY, phoneBrainEnabledValue(it))
            },
        )
        Text("Answer on this phone when the laptop is off", modifier = Modifier.weight(1f))
    }
    Text(phoneBrainModelLine(status), style = MaterialTheme.typography.bodyMedium)
    Text("Runs above 20% battery, not in power saving.", style = MaterialTheme.typography.bodySmall)
    val blocker = phoneBrainTestBlocker(status.modelName != null, battery, powerSave)
    OutlinedButton(
        enabled = blocker == null && !running,
        onClick = {
            if (running) return@OutlinedButton
            running = true
            reply = ""
            stats = null
            scope.launch {
                val start = TimeSource.Monotonic.markNow()
                var firstWordMs: Long? = null
                try {
                    val result = services.phoneBrain.generate(TEST_SYSTEM, TEST_USER, testSchema) { partial ->
                        val words = ReplyStream.extractReply(partial)
                        if (words.isNotBlank() && firstWordMs == null) firstWordMs = start.elapsedNow().inWholeMilliseconds
                        reply = words
                    }
                    reply = phoneBrainTestReply(result.raw)
                    stats = phoneBrainStatsText(firstWordMs, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reply = "The phone could not answer."
                } finally {
                    running = false
                }
            }
        },
    ) { Text("Test phone brain") }
    if (blocker != null) Text(blocker, style = MaterialTheme.typography.bodySmall)
    reply?.let { Text(it.ifEmpty { "Thinking…" }, style = MaterialTheme.typography.bodyMedium) }
    stats?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Texts of the Phone brain card (phone Settings only), for the label-uniqueness check. */
internal val phoneBrainLabels: List<String> = listOf(
    "Phone brain", "Answer on this phone when the laptop is off", "No model file. See ops/phone-model.md",
    "Runs above 20% battery, not in power saving.", "Test phone brain", "Needs the model file.",
    "Battery is below 20%.", "Power saving is on.", "Thinking…", "The phone could not answer.",
    "The phone gave no reply.", "First word", "Speed", "Backend",
)
