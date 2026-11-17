package dev.tim.assistant.ai

import kotlinx.serialization.json.JsonObject

/** The model file the phone brain would use: [modelName] and [sizeBytes] are null when no file is present. */
data class PhoneBrainStatus(val modelName: String?, val sizeBytes: Long?)

/** What one generation produced. [raw] is the model's complete text, or null when it failed or was cancelled. */
data class PhoneBrainResult(val raw: String?, val firstTokenMs: Long?, val tokensPerSecond: Double?, val backend: String? = null)

/** The phone's own language model. [onPartial] receives the text so far (the whole text, growing) while it is written. */
interface PhoneBrain {
    fun status(): PhoneBrainStatus
    suspend fun generate(system: String, user: String, schema: JsonObject, onPartial: (String) -> Unit): PhoneBrainResult
    fun unload()

    /** Loads the engine in the background if it is not loaded (cold-start saver). Never blocks a generation. */
    fun prewarm() {}
}

/** No phone brain (desktop, tests): no model, and every generation fails. */
object NoPhoneBrain : PhoneBrain {
    override fun status(): PhoneBrainStatus = PhoneBrainStatus(null, null)
    override suspend fun generate(system: String, user: String, schema: JsonObject, onPartial: (String) -> Unit): PhoneBrainResult =
        PhoneBrainResult(null, null, null)
    override fun unload() {}
}
