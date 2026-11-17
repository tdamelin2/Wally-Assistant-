import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import dev.tim.assistant.ai.jobs.UsageJobs
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.desktop.LaptopRecorder
import dev.tim.assistant.usage.DesktopControls
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.dream.DreamAnalysis
import dev.tim.assistant.ai.jobs.GoalJobs
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.ai.jobs.HardwareJobs
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.MailJobs
import dev.tim.assistant.hardware.HardwareCheck
import dev.tim.assistant.ai.jobs.OllamaModel
import dev.tim.assistant.ai.jobs.StudyJobs
import dev.tim.assistant.study.StudyGuideJob
import dev.tim.assistant.ui.study.FilePicker
import dev.tim.assistant.ui.study.PickedFile
import dev.tim.assistant.study.STUDY_FILE_MAX_BYTES
import java.awt.FileDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.tim.assistant.data.DriverFactory
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.tim.assistant.ui.Shortcut
import dev.tim.assistant.ui.ShortcutState
import dev.tim.assistant.ui.TextEntryState
import dev.tim.assistant.ui.shortcutFor
import dev.tim.assistant.ui.shortcutHandled
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.tim.assistant.ui.App
import dev.tim.assistant.ui.AppServices
import kotlin.time.Clock

/** The desktop file chooser (java.awt.FileDialog). Reads at most the size limit plus one byte; cancel returns null. */
private val desktopFilePicker = FilePicker {
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as java.awt.Frame?, "Choose a PDF or PPTX", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file
        val dir = dialog.directory
        dialog.dispose()
        if (name == null || dir == null) {
            null
        } else {
            val file = File(dir, name)
            file.inputStream().use { input ->
                val limit = STUDY_FILE_MAX_BYTES + 1
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (total < limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                PickedFile(name, out.toByteArray())
            }
        }
    }
}

/** The switches Settings can reach on the laptop. The tray setting is read again at every close. */
private class Controls(private val repo: Repository) : DesktopControls {
    override fun keepInTray(): Boolean = repo.setting(SettingKeys.KEEP_IN_TRAY) != "false"
    override fun setKeepInTray(value: Boolean) = repo.putSetting(SettingKeys.KEEP_IN_TRAY, value.toString())
    override fun autostartAvailable(): Boolean = Autostart.available()
    override fun autostartEnabled(): Boolean = Autostart.enabled()
    override fun setAutostart(value: Boolean) = Autostart.set(value)
}

/** How long Quit waits for the last session to be saved before leaving anyway. */
private const val QUIT_SAVE_TIMEOUT_MS = 5_000L

fun main(args: Array<String>) {
    val driverFactory = DriverFactory()
    // Only one Wally per database file: a second launch asks the first to show its window and ends here.
    val showRequests = MutableStateFlow(0)
    val instance = SingleInstance.acquire(driverFactory.databaseFile()) { showRequests.value += 1 }
    if (instance == null) exitProcess(0)
    val startHidden = "--tray" in args

    // One service graph for the whole process, created before any window exists.
    val services = AppServices(driverFactory, runsJobs = true)
    // One job runner per desktop process (never on Android): runs queued AI jobs while the app is running.
    val model = OllamaModel(ChatClient(), services.repo)
    val clock = { Clock.System.now().toEpochMilliseconds() }
    // Research and meal lookups run on the HP server now; the laptop never starts Codex.
    JobRunner(
        repo = services.repo,
        sync = { services.sync.sync() },
        handlers = DreamAnalysis(services.repo, model).handlers() + GoalJobs(services.repo, model, clock) {
            val cfg = services.sync.serverConfig()
            if (cfg == null) DreamWeekResult.Unavailable("no server") else services.client.dreamWeek(cfg)
        }.handlers() +
            UsageJobs(services.repo, model, clock).handlers() +
            StudyJobs(services.repo, model, services.client) { services.sync.serverConfig() }.handlers() +
            StudyGuideJob(services.repo, model, services.client, { services.sync.serverConfig() }, clock).handlers() +
            MailJobs(services.repo, model, services.client) { services.sync.serverConfig() }.handlers() +
            HardwareJobs(services.repo, model).handlers(),
        clock = clock,
    ).start(services.scope)
    // The laptop recorder lives with the process, not the window: it keeps recording while the window is hidden.
    val recorder = LaptopRecorder(services.repo)
    recorder.start(services.scope) { withContext(Dispatchers.IO) { WindowsSampler.read() } }
    val controls = Controls(services.repo)
    val hardwareRunner = HardwareCheck(ProcessCommandRunner(), clock)

    application {
        val windowIcon = remember {
            BitmapPainter(
                useResource("wally-256.png") { loadImageBitmap(it) },
            )
        }
        val trayAvailable = remember { isTraySupported }
        var visible by remember { mutableStateOf(!(startHidden && trayAvailable)) }
        var raise by remember { mutableIntStateOf(0) }
        var quitting by remember { mutableStateOf(false) }
        val uiScope = rememberCoroutineScope()
        val showCount by showRequests.collectAsState()
        LaunchedEffect(showCount) {
            if (showCount > 0) {
                visible = true
                raise++
            }
        }

        // The one Quit path: save the last laptop session, then leave.
        fun quit() {
            if (quitting) return
            quitting = true
            uiScope.launch {
                withContext(Dispatchers.IO) { withTimeoutOrNull(QUIT_SAVE_TIMEOUT_MS) { recorder.stopAndFlush() } }
                // An orderly quit commits a swipe still waiting for its Undo window.
                withContext(Dispatchers.IO) { withTimeoutOrNull(QUIT_SAVE_TIMEOUT_MS) { services.undo.flush() } }
                instance.close()
                exitApplication()
            }
        }

        if (trayAvailable) {
            Tray(
                icon = windowIcon,
                tooltip = "Wally",
                onAction = { visible = true; raise++ },
                menu = {
                    Item("Open") { visible = true; raise++ }
                    Item("Quit") { quit() }
                },
            )
        }
        // One command stream for the window: the key hook below only emits a command the app says it will handle.
        val shortcuts = remember { MutableSharedFlow<Shortcut>(extraBufferCapacity = 8) }
        val textEntry = remember { TextEntryState() }
        val shortcutState = remember { ShortcutState() }
        Window(
            onCloseRequest = { if (trayAvailable && controls.keepInTray()) visible = false else quit() },
            visible = visible,
            title = "Wally",
            icon = windowIcon,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val command = shortcutFor(event.key, event.isCtrlPressed, textEntry.inTextField, textEntry.multiline)
                    if (command != null && shortcutHandled(command, shortcutState.context())) {
                        shortcuts.tryEmit(command)
                        true
                    } else {
                        false
                    }
                }
            },
        ) {
            LaunchedEffect(raise) {
                if (raise > 0) {
                    window.isMinimized = false
                    window.toFront()
                }
            }
            App(services, filePicker = desktopFilePicker, desktopControls = controls, hardwareRunner = hardwareRunner, foreground = visible, shortcuts = shortcuts, textEntry = textEntry, shortcutState = shortcutState)
        }
    }
}