package dev.tim.assistant.server

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GoogleConfig(
    val clientId: String,
    val clientSecret: String,
)

@Serializable
data class OllamaConfig(
    val url: String,
    val briefingModel: String,
    /** The XPS Ollama address (Tailscale, port 11434); chat prefers it when it answers, see Brain. */
    val xpsUrl: String? = null,
    /** The embedding model for the sorter (rung 2), served by [url]; null turns the sorter off. */
    val embedModel: String? = null,
)

@Serializable
data class MoodleConfig(val baseUrl: String) {
    /** The base URL without surrounding blanks or a trailing slash; it may contain a path. */
    val root: String get() = baseUrl.trim().trimEnd('/')
}

@Serializable
data class SpotifyConfig(val clientId: String, val clientSecret: String)

/** WallyCalendar: [token] is its "wally" token (outbound); [url] may contain a path. */
@Serializable
data class WallyCalConfig(val url: String, val token: String) {
    /** The URL without surrounding blanks or a trailing slash. */
    val root: String get() = url.trim().trimEnd('/')
}

@Serializable
data class Config(
    val bindAddress: String,
    val port: Int = 8080,
    val dbPath: String,
    val tokens: Map<String, String>,
    val google: GoogleConfig? = null,
    val ollama: OllamaConfig? = null,
    val moodle: MoodleConfig? = null,
    val spotify: SpotifyConfig? = null,
    val wallycal: WallyCalConfig? = null,
    /** Full path of the Codex CLI on the HP (`which codex`); null means /usr/bin/codex. */
    val codexPath: String? = null,
)

const val DEFAULT_CONFIG_PATH = "/etc/assistant/config.json"

private val configJson = Json { ignoreUnknownKeys = true }

fun parseConfig(text: String): Config {
    val c = configJson.decodeFromString(Config.serializer(), text)
    return c.copy(
        moodle = c.moodle?.let { MoodleConfig(it.root) },
        wallycal = c.wallycal?.let { WallyCalConfig(it.root, it.token) },
    )
}

/** Loads the config from ASSISTANT_CONFIG when set, else /etc/assistant/config.json. */
fun loadConfig(env: Map<String, String> = System.getenv()): Config {
    val path = env["ASSISTANT_CONFIG"]?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG_PATH
    return parseConfig(File(path).readText())
}
