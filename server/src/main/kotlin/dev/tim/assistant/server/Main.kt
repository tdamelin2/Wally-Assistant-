package dev.tim.assistant.server

import dev.tim.assistant.model.HealthResponse
import dev.tim.assistant.server.google.GoogleApi
import dev.tim.assistant.server.google.GmailWrite
import dev.tim.assistant.server.google.GoogleAuth
import dev.tim.assistant.server.google.mailRoutes
import kotlinx.coroutines.launch
import dev.tim.assistant.server.google.GooglePoller
import dev.tim.assistant.server.google.GoogleStatus
import dev.tim.assistant.server.google.Scheduler
import dev.tim.assistant.server.google.runGoogleAuth
import dev.tim.assistant.server.google.tokenFilePath
import dev.tim.assistant.server.ai.BRIEFING_TICK_MS
import dev.tim.assistant.server.ai.BriefingOutcome
import dev.tim.assistant.server.ai.BriefingService
import dev.tim.assistant.server.ai.NUTRITION_INTERVAL_MS
import dev.tim.assistant.server.ai.NutritionEstimator
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.tools.ApprovalSink
import dev.tim.assistant.research.CodexResearch
import dev.tim.assistant.research.ProcessCodexProcess
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.notify.NOTIFY_TICK_MS
import dev.tim.assistant.server.notify.NotifyScheduler
import dev.tim.assistant.server.codex.CODEX_TICK_MS
import dev.tim.assistant.server.codex.DEFAULT_CODEX_PATH
import dev.tim.assistant.server.codex.ServerCodex
import dev.tim.assistant.server.codex.ServerMealLookup
import dev.tim.assistant.server.chat.ChatRunner
import dev.tim.assistant.server.chat.ChatService
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.ChatSummarizer
import dev.tim.assistant.server.chat.SUMMARIZE_INTERVAL_MS
import dev.tim.assistant.server.chat.ServerApprovalSink
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.UndoService
import dev.tim.assistant.server.chat.chatRoutes
import dev.tim.assistant.server.chat.ladder.Embedder
import dev.tim.assistant.server.chat.ladder.LADDER_TICK_MS
import dev.tim.assistant.server.chat.ladder.SeedStore
import dev.tim.assistant.server.chat.ladder.Sorter
import dev.tim.assistant.server.notify.NotifyService
import dev.tim.assistant.server.notify.notifyRoutes
import dev.tim.assistant.server.spotify.SPOTIFY_INTERVAL_MS
import dev.tim.assistant.server.spotify.SpotifyAuth
import dev.tim.assistant.server.spotify.SpotifyClient
import dev.tim.assistant.server.spotify.SpotifyPoller
import dev.tim.assistant.server.spotify.SpotifyStatus
import dev.tim.assistant.server.spotify.runSpotifyAuth
import dev.tim.assistant.server.spotify.spotifyTokenFilePath
import dev.tim.assistant.server.study.MoodleClient
import dev.tim.assistant.server.study.MoodleStatus
import dev.tim.assistant.server.study.MoodleTokenStore
import dev.tim.assistant.server.study.STUDY_CHECK_INTERVAL_MS
import dev.tim.assistant.server.study.StudyFiles
import dev.tim.assistant.server.study.StudyService
import dev.tim.assistant.server.study.moodleTokenFilePath
import dev.tim.assistant.server.wallycal.WALLYCAL_INTERVAL_MS
import dev.tim.assistant.server.wallycal.WallyCalClient
import dev.tim.assistant.server.wallycal.WallyCalPoller
import dev.tim.assistant.server.wallycal.WallyCalService
import dev.tim.assistant.server.wallycal.WallyCalStatus
import dev.tim.assistant.server.wallycal.newWallyCalHttpClient
import dev.tim.assistant.server.wallycal.removeNonWallyCalEvents
import dev.tim.assistant.server.wallycal.wallyCalRoutes
import dev.tim.assistant.server.study.studyDirPath
import dev.tim.assistant.server.study.studyRoutes
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

private val GOOGLE_COMMANDS = setOf("google-auth")
private const val SPOTIFY_COMMAND = "spotify-auth"

fun main(args: Array<String>) {
    if (args.size > 1 || (args.isNotEmpty() && args[0] !in GOOGLE_COMMANDS && args[0] != SPOTIFY_COMMAND)) {
        System.err.println("usage: server [google-auth | spotify-auth]   (reads ASSISTANT_CONFIG or $DEFAULT_CONFIG_PATH)")
        exitProcess(2)
    }
    val config = try {
        loadConfig()
    } catch (e: Exception) {
        System.err.println("cannot load config: ${e::class.simpleName}")
        exitProcess(1)
    }
    if (args.isNotEmpty() && args[0] == SPOTIFY_COMMAND) exitProcess(runSpotifyCommand(config))
    if (args.isNotEmpty()) exitProcess(runGoogleCommand(args[0], config))

    val db = openDb(config.dbPath)
    val status = GoogleStatus()
    val client = newGoogleClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = SyncStore(db)
    // the old Google calendar copies go before any scheduler starts; only wcal: rows stay
    runBlocking { removeNonWallyCalEvents(db, store, System.currentTimeMillis()) }
    val google = config.google
    var googleApi: GoogleApi? = null
    if (google != null) {
        // one GoogleAuth and one HttpClient for polling, mail reads and the mail write worker
        val auth = GoogleAuth(google, tokenFilePath(), client, System::currentTimeMillis)
        val api = GoogleApi(client, auth)
        val poller = GooglePoller(db, store, api, status, System::currentTimeMillis)
        Scheduler { poller.run() }.start(scope)
        googleApi = api
        // sends APPROVED drafts and runs APPROVED label/archive actions; recovers SENDING drafts at start
        val gmailWrite = GmailWrite(db, store, client, auth, System::currentTimeMillis)
        store.gmailWake = gmailWrite::signal
        scope.launch { gmailWrite.run() }
    }
    // one model run at a time on the small HP: the briefing, the admin route and nutrition share this lock
    val ollamaConfig = config.ollama
    val ollama = ollamaConfig?.let { OllamaClient(it.url) }
    // hoisted: briefing, nutrition and (from step 2a) chat share this one lock
    val generationLock = Mutex()
    val brain = ollamaConfig?.let { Brain(it.xpsUrl, it.url, it.briefingModel) }
    var briefing: BriefingService? = null
    if (ollamaConfig != null && ollama != null) {
        val service = BriefingService(db, store, ollama, ollamaConfig.briefingModel, generationLock)
        val nutrition = NutritionEstimator(db, store, ollama, ollamaConfig.briefingModel, generationLock, shouldYield = service::isDue)
        Scheduler("briefing", BRIEFING_TICK_MS) { service.tick() }.start(scope)
        Scheduler("nutrition", NUTRITION_INTERVAL_MS) { nutrition.run() }.start(scope)
        briefing = service
    }
    // Moodle study files: one process-owned service (check gate, extraction worker); works without a moodle block
    val moodleClient = newMoodleClient()
    val moodleStatus = MoodleStatus()
    val study = StudyService(
        db, store, StudyFiles(studyDirPath()),
        config.moodle?.let { MoodleClient(it.root, moodleClient) },
        MoodleTokenStore(moodleTokenFilePath()), moodleStatus, scope,
    )
    study.start()
    Scheduler("study", STUDY_CHECK_INTERVAL_MS) { study.tick() }.start(scope)
    // Spotify listening history: its own 30-minute loop; without a spotify block nothing runs and health says null
    val spotifyStatus = SpotifyStatus()
    config.spotify?.let { spotify ->
        val auth = SpotifyAuth(spotify, spotifyTokenFilePath(), client, System::currentTimeMillis)
        val poller = SpotifyPoller(db, store, SpotifyClient(client, auth), auth, spotifyStatus, System::currentTimeMillis)
        Scheduler("spotify", SPOTIFY_INTERVAL_MS) { poller.run() }.start(scope)
    }
    // WallyCalendar mirror: its own 15-minute loop; without a wallycal block nothing runs and health says null
    val wallyCalStatus = WallyCalStatus()
    val wallyCalHttp = config.wallycal?.let { newWallyCalHttpClient() }
    var wallyCal: WallyCalService? = null
    config.wallycal?.let { cfg ->
        val wallyClient = WallyCalClient(wallyCalHttp!!, cfg)
        val poller = WallyCalPoller(db, store, wallyClient, wallyCalStatus, System::currentTimeMillis)
        Scheduler("wallycal", WALLYCAL_INTERVAL_MS) { poller.run() }.start(scope)
        wallyCal = WallyCalService(db, wallyClient, poller)
    }
    // chat on the HP: private chat tables, the approval sink (the server decides approvals) and the chat runner
    val writer = ServerWriter(db, store)
    val chatStore = ChatStore(db, writer)
    // Codex research and meal lookup run here on the HP (the executable is config.codexPath, else /usr/bin/codex)
    val codexResearch = CodexResearch(ProcessCodexProcess(), findExecutable = { config.codexPath ?: DEFAULT_CODEX_PATH })
    val serverCodex = ServerCodex(db, writer, codexResearch)
    Scheduler("codex jobs", CODEX_TICK_MS) { serverCodex.tick() }.start(scope)
    if (brain != null) {
        val mealLookup = ServerMealLookup(db, writer, serverCodex.runner, brain, generationLock, { OllamaClient(it) })
        Scheduler("meal lookup", CODEX_TICK_MS) { mealLookup.tick() }.start(scope)
    }
    val approvalSink = ServerApprovalSink(db, store, writer, wallyCal, googleApi, serverCodex)
    val chatClient = brain?.let { ChatClient(CIO.create()) }
    // sorter seeds (Phase 18): embedded in the background, never delaying start; its own 30 s loop retries while not ready
    val embedder = ollamaConfig?.embedModel?.let { Embedder(ollamaConfig.url, it) }
    val seedStore = embedder?.let { SeedStore(it, scope) }
    seedStore?.let { store0 ->
        store0.startSeeding()
        Scheduler("ladder", LADDER_TICK_MS) { store0.startSeeding() }.start(scope)
    }
    val sorter = if (embedder != null && seedStore != null) Sorter(seedStore, embedder) else null
    val chatRunner = if (brain != null && chatClient != null) ChatRunner(db, store, writer, chatStore, brain, generationLock, chatClient, sorter = sorter, hpOllama = ollama, researchApprover = serverCodex) else null
    val chatService = ChatService(chatStore, UndoService(db, store, writer, chatStore), chatRunner)
    runBlocking { chatStore.ensurePinned() }
    if (brain != null) {
        val summarizer = ChatSummarizer(db, writer, chatStore, brain, generationLock) { OllamaClient(it) }
        summarizer.busy = chatService::chatBusy
        Scheduler("chat summaries", SUMMARIZE_INTERVAL_MS) { summarizer.tick() }.start(scope)
    }
    // phone turns (Phase 19): closes a turn the phone never answered; its own loop, independent of the embed model and the sorter
    Scheduler("phone turns", LADDER_TICK_MS) { chatService.closeExpiredPhoneTurns() }.start(scope)
    // phone reminders: queued once a minute in the private outbox (the routes that serve them come with the settings routes)
    val notifyScheduler = NotifyScheduler(db, writer, brain, generationLock, ollama = { OllamaClient(it) })
    Scheduler("notify", NOTIFY_TICK_MS) { notifyScheduler.tick() }.start(scope)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            client.close()
            moodleClient.close()
            wallyCalHttp?.close()
            ollama?.close()
            embedder?.close()
            brain?.close()
            chatClient?.close()
        },
    )
    embeddedServer(Netty, port = config.port, host = config.bindAddress) {
        module(config, store, googleStatus = status, briefing = briefing, moodleStatus = moodleStatus, study = study, spotifyStatus = spotifyStatus, wallyCalStatus = wallyCalStatus, wallyCal = wallyCal, googleApi = googleApi, chat = chatService, approvalSink = approvalSink, notify = NotifyService(db, writer))
    }.start(wait = true)
}

/** The HttpClient for Moodle calls and file downloads; the caller closes it on shutdown. */
fun newMoodleClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 15 * 60_000L
    }
}

/** The one HttpClient for all Google calls; the caller closes it on shutdown. */
fun newGoogleClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000
    }
}

private fun runGoogleCommand(command: String, config: Config): Int {
    val google = config.google
    if (google == null) {
        System.err.println("the config has no \"google\" section (clientId, clientSecret)")
        return 1
    }
    val client = newGoogleClient()
    try {
        val tokenFile = tokenFilePath()
        return runBlocking {
            runGoogleAuth(
                google, tokenFile, client, System::currentTimeMillis,
                announce = { url -> println("Open this address in the browser on your laptop:\n$url") },
                out = { println(it) },
            )
        }
    } finally {
        client.close()
    }
}

private fun runSpotifyCommand(config: Config): Int {
    val spotify = config.spotify
    if (spotify == null) {
        System.err.println("the config has no \"spotify\" section (clientId, clientSecret)")
        return 1
    }
    val client = newGoogleClient()
    try {
        return runBlocking {
            runSpotifyAuth(
                spotify, spotifyTokenFilePath(), client, System::currentTimeMillis,
                announce = { url -> println("Open this address in the browser on your laptop:\n$url") },
                out = { println(it) },
            )
        }
    } finally {
        client.close()
    }
}

@Serializable
data class BriefingResponse(val localDate: String, val text: String)

@Serializable
data class ErrorResponse(val error: String)

/** Injectable application wiring: tests pass their own config and store. */
fun Application.module(
    config: Config,
    store: SyncStore,
    clock: () -> Long = System::currentTimeMillis,
    googleStatus: GoogleStatus = GoogleStatus(),
    briefing: BriefingService? = null,
    moodleStatus: MoodleStatus = MoodleStatus(),
    study: StudyService? = null,
    spotifyStatus: SpotifyStatus = SpotifyStatus(),
    wallyCalStatus: WallyCalStatus = WallyCalStatus(),
    wallyCal: WallyCalService? = null,
    googleApi: GoogleApi? = null,
    chat: ChatService? = null,
    approvalSink: ApprovalSink? = null,
    notify: NotifyService? = null,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true })
    }
    routing {
        route("/v1") {
            requireBearerToken(config.tokens.values)
            get("/health") {
                val (ok, last) = googleStatus.snapshot()
                val moodle = config.moodle
                call.respond(
                    HealthResponse(
                        "ok", clock(), googleOk = ok, googleLastFetchAt = last,
                        moodleOk = if (moodle != null) moodleStatus.get() else null,
                        moodleBaseUrl = moodle?.root,
                        spotifyOk = if (config.spotify != null) spotifyStatus.get() else null,
                        calendarOk = if (config.wallycal != null) wallyCalStatus.get() else null,
                    ),
                )
            }
            syncRoutes(store, config.tokens["calendar"])
            mailRoutes(config, googleApi)
            if (study != null) studyRoutes(study)
            wallyCalRoutes(config, wallyCal)
            if (chat != null && approvalSink != null) chatRoutes(config.tokens["calendar"], chat, approvalSink, clock, config.tokens)
            if (notify != null) notifyRoutes(config.tokens["calendar"], notify)
            post("/admin/briefing") {
                if (briefing == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("ollama not configured"))
                    return@post
                }
                when (val outcome = briefing.regenerateNow()) {
                    is BriefingOutcome.Done -> call.respond(BriefingResponse(outcome.localDate, outcome.text))
                    BriefingOutcome.Busy -> call.respond(HttpStatusCode.Conflict, ErrorResponse("briefing already running"))
                }
            }
        }
    }
}
