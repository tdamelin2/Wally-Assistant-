package dev.tim.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tim.assistant.charts.ChartsScreen
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.notify.LaunchTarget
import dev.tim.assistant.data.launchSyncTimer
import dev.tim.assistant.hardware.HardwareCheckRunner
import dev.tim.assistant.health.HealthScreen
import dev.tim.assistant.health.HealthUi
import dev.tim.assistant.ui.dream.DreamDetail
import dev.tim.assistant.ui.dream.DreamForm
import dev.tim.assistant.ui.dream.DreamScreen
import dev.tim.assistant.ui.files.FilesScreen
import dev.tim.assistant.ui.goals.GoalsScreen
import dev.tim.assistant.ui.hardware.HardwareScreen
import dev.tim.assistant.ui.mail.MailScreen
import dev.tim.assistant.ui.screentime.ScreentimeScreen
import dev.tim.assistant.ui.study.FilePicker
import dev.tim.assistant.usage.DesktopControls
import dev.tim.assistant.usage.UsageAccess
import dev.tim.assistant.ui.study.StudyScreen
import dev.tim.assistant.ui.forms.CheckinForm
import dev.tim.assistant.ui.forms.JournalForm
import dev.tim.assistant.ui.forms.MealForm
import dev.tim.assistant.ui.forms.SpendingForm
import dev.tim.assistant.ui.forms.TaskForm
import dev.tim.assistant.ui.lists.CheckinsScreen
import dev.tim.assistant.ui.lists.JournalScreen
import dev.tim.assistant.ui.lists.MealsScreen
import dev.tim.assistant.ui.lists.SpendingScreen
import dev.tim.assistant.ui.lists.TasksScreen
import kotlinx.coroutines.launch

/** Which form is open (null row = a new entry). */
sealed interface FormTarget {
    data class Checkin(val row: dev.tim.assistant.db.Checkin?) : FormTarget
    data class Spending(val row: dev.tim.assistant.db.Spending?) : FormTarget
    data class Journal(val row: dev.tim.assistant.db.Journal?) : FormTarget
    data class Task(val row: dev.tim.assistant.db.Task?) : FormTarget
    data class Meal(val row: dev.tim.assistant.db.Meal?) : FormTarget
    /** A dream by id (null = new); the form loads the row itself. */
    data class Dream(val id: String?) : FormTarget
    /** A saved dream opened read-first (its detail page); Edit switches to Dream(id). */
    data class DreamRead(val id: String) : FormTarget
}

/** True for the targets drawn as a bottom sheet on the phone; Journal and Dreams are full-screen writers. */
fun opensAsSheet(target: FormTarget): Boolean = when (target) {
    is FormTarget.Checkin, is FormTarget.Spending, is FormTarget.Meal, is FormTarget.Task -> true
    is FormTarget.Journal, is FormTarget.Dream, is FormTarget.DreamRead -> false
}

/** True when [target] edits a row that is waiting for Undo or being committed (it must no longer be edited). */
fun targetIsHidden(target: FormTarget, hidden: Set<Pair<String, String>>): Boolean = when (target) {
    is FormTarget.Checkin -> target.row?.let { hidden.hides(UndoTables.CHECKIN, it.id) } ?: false
    is FormTarget.Spending -> target.row?.let { hidden.hides(UndoTables.SPENDING, it.id) } ?: false
    is FormTarget.Journal -> target.row?.let { hidden.hides(UndoTables.JOURNAL, it.id) } ?: false
    is FormTarget.Task -> target.row?.let { hidden.hides(UndoTables.TASK, it.id) } ?: false
    is FormTarget.Meal -> target.row?.let { hidden.hides(UndoTables.MEAL, it.id) } ?: false
    is FormTarget.Dream -> target.id?.let { hidden.hides(UndoTables.DREAM, it) } ?: false
    is FormTarget.DreamRead -> hidden.hides(UndoTables.DREAM, target.id)
}

/** One opening of a form; the id makes every opening a fresh session. */
private class FormSession(val id: Int, val target: FormTarget)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    services: AppServices,
    healthUi: HealthUi? = null,
    filePicker: FilePicker? = null,
    usageAccess: UsageAccess? = null,
    desktopControls: DesktopControls? = null,
    hardwareRunner: HardwareCheckRunner? = null,
    foreground: Boolean = true,
    shortcuts: SharedFlow<Shortcut>? = null,
    textEntry: TextEntryState? = null,
    shortcutState: ShortcutState? = null,
) {
    val textEntryState = textEntry ?: remember { TextEntryState() }
    val shortcutHub = shortcutState ?: remember { ShortcutState() }
    var focusSearchRequests by remember { mutableIntStateOf(0) }
    var hub by remember { mutableStateOf(Hub.Today) }
    var chipFor by remember { mutableStateOf(initialChips()) }
    var session by remember { mutableStateOf<FormSession?>(null) }
    var sessionCount by remember { mutableIntStateOf(0) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Create requests from the shell's +; each screen opens its own create UI once per bump (see OnNewRequest).
    var newGoalRequests by remember { mutableIntStateOf(0) }
    var newDocRequests by remember { mutableIntStateOf(0) }
    var studyAddRequests by remember { mutableIntStateOf(0) }
    var newEmailRequests by remember { mutableIntStateOf(0) }
    // Counts closed forms: the docked new-row form of a Log chip is a fresh session after every Save or Cancel.
    var freshCount by remember { mutableIntStateOf(0) }
    val leaveGuard = remember { LeaveGuard() }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) { services.sync.sync() }

    // A tap on a phone reminder: go to the row's target (once per tap).
    val launchRequest by services.launch.pending.collectAsState()
    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        val goTo = { chip: NavTab -> chipFor = selectChip(chipFor, chip); hub = hubOf(chip) }
        when (val target = request.target) {
            LaunchTarget.CheckinForm -> { goTo(NavTab.Checkins); sessionCount++; session = FormSession(sessionCount, FormTarget.Checkin(null)) }
            LaunchTarget.Today, is LaunchTarget.Event -> goTo(NavTab.Today)
            is LaunchTarget.Exam -> { services.launch.examsRequested.value = true; goTo(NavTab.Study) }
            is LaunchTarget.Idea -> {
                goTo(NavTab.Chat)
                services.chat.newChat("idea-${target.notificationId}", request.body, target.notificationId)
            }
            is LaunchTarget.Chat -> { goTo(NavTab.Chat); services.chat.select(target.id) }
        }
        services.launch.consume(request)
    }

    // While the app is in front, sync every 10 minutes; leaving the screen cancels the timer (its own child job).
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        val timer = launchSyncTimer { services.sync.sync() }
        try {
            timer.join()
        } finally {
            timer.cancel()
        }
    }

    // One snackbar for the whole app; both shells show it. The pending action's token ties each Undo to its own
    // action, so a late result of an old snackbar can never resolve a newer one.
    val snackbar = remember { SnackbarHostState() }
    val pending by services.undo.pending.collectAsState()
    LaunchedEffect(pending?.token) {
        val action = pending ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = if (action.kind == UndoKind.Delete) "Deleted" else "Done",
            actionLabel = "Undo",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) services.undo.undo(action.token)
    }
    LaunchedEffect(Unit) {
        services.undo.errors.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    // The destination lives in movable content so a resize keeps its scroll position and sub-tab.
    val destination = remember(services, healthUi, filePicker, usageAccess, desktopControls, hardwareRunner) {
        movableContentOf {
          AnimatedContent(
            targetState = chipFor.getValue(hub),
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(Motion.CrossfadeMs)) togetherWith fadeOut(tween(Motion.CrossfadeMs)) },
            label = "chip",
          ) { chip ->
            when (chip) {
                NavTab.Today -> TodayScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Checkins -> CheckinsScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Spending -> SpendingScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Journal -> JournalScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Meals -> MealsScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Tasks -> TasksScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Dreams -> DreamScreen(services) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Charts -> ChartsScreen(services)
                NavTab.Health -> HealthScreen(services, healthUi)
                NavTab.Screentime -> ScreentimeScreen(services, usageAccess)
                NavTab.Goals -> GoalsScreen(services, newGoalRequests) { target -> sessionCount++; session = FormSession(sessionCount, target) }
                NavTab.Chat -> ChatScreen(services)
                NavTab.Files -> FilesScreen(services, newDocRequests, focusSearchRequests)
                NavTab.Study -> StudyScreen(services, filePicker, studyAddRequests)
                NavTab.Mail -> MailScreen(services, newEmailRequests)
                NavTab.Hardware -> HardwareScreen(services, hardwareRunner)
                NavTab.Settings -> SettingsScreen(services, usageAccess, desktopControls)
            }
          }
        }
    }

    WallyTheme {
      WallyRoot {
        // Default text direction follows the content, so Hebrew text renders right-to-left.
        ProvideTextStyle(TextStyle(textDirection = TextDirection.Content)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val mode = layoutModeForDevice(isLaptop)
                val maxFormHeight = maxHeight - 48.dp
                val chipNow = chipFor.getValue(hub)
                // Laptop: forms of the current Log chip are docked in the side panel instead of a dialog.
                val dockTarget = if (mode == LayoutMode.Wide) dockedFormFor(chipNow, session?.target) else null
                // A docked session that belongs to another chip is dropped when the chip changes.
                val leaveChip = { newChip: NavTab ->
                    val s = session
                    if (mode == LayoutMode.Wide && s != null && dockedFormFor(newChip, s.target) != s.target) { session = null; freshCount++ }
                }
                // A screen with unsaved work (a dirty doc or mail draft) makes a click that would replace it ask first.
                val askLeave: (() -> Unit) -> Unit = { action ->
                    if (mode == LayoutMode.Wide && leaveGuard.isDirty) pendingLeave = action else action()
                }
                val doAdd: (AddAction) -> Unit = { action ->
                        when (action) {
                            AddAction.Picker -> pickerOpen = true
                            is AddAction.Form -> {
                                // The laptop's New while the empty writer is already docked changes nothing: re-opening it would
                                // build a second writer that reads the draft before the first one has flushed its latest text.
                                val writerShown = mode == LayoutMode.Wide && session == null && dockTarget == action.target &&
                                    (action.target is FormTarget.Journal || action.target is FormTarget.Dream)
                                if (!writerShown) { sessionCount++; session = FormSession(sessionCount, action.target) }
                            }
                            AddAction.NewGoal -> newGoalRequests++
                            AddAction.NewDoc -> newDocRequests++
                            AddAction.StudyAdd -> studyAddRequests++
                            AddAction.NewEmail -> newEmailRequests++
                        }
                }
                val state = ShellState(
                    hub = hub,
                    chip = chipNow,
                    onHub = { h ->
                        val target = chipFor.getValue(h)
                        val go = { leaveChip(target); hub = h }
                        if (target == chipNow) go() else askLeave(go)
                    },
                    onChip = { chip ->
                        val go = { leaveChip(chip); chipFor = selectChip(chipFor, chip); hub = hubOf(chip) }
                        if (chip == chipNow) go() else askLeave(go)
                    },
                    onAdd = { action ->
                        if (action is AddAction.Picker || action is AddAction.Form) askLeave { doAdd(action) } else doAdd(action)
                    },
                    snackbar = snackbar,
                )
                val close: () -> Unit = { session = null; freshCount++ }
                val open = session
                // Laptop: a row that Undo is hiding (just deleted or done) is no longer edited; the panel goes back to New.
                val hiddenRows by services.undo.hidden.collectAsState()
                LaunchedEffect(hiddenRows, open?.id) {
                    val s = session
                    if (mode == LayoutMode.Wide && s != null && targetIsHidden(s.target, hiddenRows)) { session = null; freshCount++ }
                }
                // The form (docked, dialog, sheet or full screen) is one keyed session in movable content, so
                // switching between side panel, stacked panel, dialog or full screen keeps what was typed and a
                // running save; every new opening or closing gets a new key and so a fresh form.
                val formTarget: FormTarget? = dockTarget ?: open?.target
                val formKey: String? = when {
                    dockTarget != null && open != null && open.target == dockTarget -> "s${open.id}"
                    dockTarget != null -> "f$freshCount-${chipNow.name}"
                    open != null -> "s${open.id}"
                    else -> null
                }
                val form: (@Composable () -> Unit)? = if (formKey != null && formTarget != null) {
                    remember(formKey) {
                        movableContentOf { ShowForm(services, formTarget, close) { target -> sessionCount++; session = FormSession(sessionCount, target) } }
                    }
                } else null
                val dockedForm: (@Composable () -> Unit)? = if (dockTarget != null && form != null) {
                    {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxSize(),
                        ) { form() }
                    }
                } else null
                val dialogOpen = mode == LayoutMode.Wide && open != null && form != null && dockTarget == null
                SideEffect {
                    shortcutHub.chip = chipNow
                    if (chipNow != NavTab.Chat) services.lastScreen = chipNow.name
                    shortcutHub.pickerOpen = pickerOpen
                    shortcutHub.dialogOpen = dialogOpen
                }
                // The one place the window's keyboard commands are carried out (the window already checked that
                // each is handled); it sits above the animated destination so a chip change never replays one.
                val onShortcut by rememberUpdatedState<(Shortcut) -> Unit> { s ->
                    when (s) {
                        Shortcut.NewItem -> addActionFor(chipNow)?.let(state.onAdd)
                        Shortcut.Cancel -> if (pickerOpen) pickerOpen = false else shortcutHub.registry.cancel()
                        Shortcut.Save -> shortcutHub.registry.save()
                        Shortcut.SaveLong -> shortcutHub.registry.saveLong()
                        Shortcut.FocusSearch -> focusSearchRequests++
                        Shortcut.ToggleChatList -> services.chat.toggleListHidden()
                    }
                }
                LaunchedEffect(shortcuts) { shortcuts?.collect { onShortcut(it) } }
                CompositionLocalProvider(
                    LocalLayoutMode provides mode,
                    LocalTextEntry provides textEntryState,
                    LocalShortcutRegistry provides shortcutHub.registry,
                    LocalLeaveGuard provides leaveGuard,
                ) {
                    if (mode == LayoutMode.Wide) WideShell(services, state, stacksSidePanel(maxWidth.value), dockedForm) { destination() }
                    else NarrowShell(services, state) { destination() }

                    pendingLeave?.let { action ->
                        AlertDialog(
                            onDismissRequest = { pendingLeave = null },
                            title = { Text("Discard your changes?") },
                            confirmButton = { TextButton(onClick = { pendingLeave = null; action() }) { Text("Discard") } },
                            dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("Keep editing") } },
                        )
                    }

                    if (pickerOpen) {
                        LogPickerSheet(
                            onPick = { target ->
                                pickerOpen = false
                                if (mode == LayoutMode.Wide) {
                                    // The laptop jumps to the picked Log chip, whose docked form is the new entry.
                                    val picked = pickerChipFor(target)
                                    chipFor = selectChip(chipFor, picked)
                                    hub = hubOf(picked)
                                    session = null
                                    freshCount++
                                } else {
                                    sessionCount++
                                    session = FormSession(sessionCount, target)
                                }
                            },
                            onDismiss = { pickerOpen = false },
                        )
                    }

                    if (open != null && form != null && dockTarget == null) {
                        if (mode == LayoutMode.Wide) {
                            Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                                CompositionLocalProvider(LocalLayoutMode provides mode) {
                                    ProvideTextStyle(TextStyle(textDirection = TextDirection.Content)) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 6.dp,
                                            modifier = Modifier.widthIn(max = 560.dp).heightIn(max = maxFormHeight),
                                        ) { form() }
                                    }
                                }
                            }
                        } else if (opensAsSheet(open.target)) {
                            ModalBottomSheet(
                                onDismissRequest = close,
                                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) { form() }
                        } else {
                            Surface(Modifier.fillMaxSize().pointerInput(Unit) {}) { form() }
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun ShowForm(services: AppServices, target: FormTarget, close: () -> Unit, open: (FormTarget) -> Unit) {
    when (target) {
        is FormTarget.Checkin -> CheckinForm(services, target.row, close)
        is FormTarget.Spending -> SpendingForm(services, target.row, close)
        is FormTarget.Journal -> JournalForm(services, target.row, close)
        is FormTarget.Task -> TaskForm(services, target.row, close)
        is FormTarget.Meal -> MealForm(services, target.row, close)
        is FormTarget.Dream -> DreamForm(services, target.id, close)
        is FormTarget.DreamRead -> DreamDetail(services, target.id, onBack = close, onEdit = { open(FormTarget.Dream(target.id)) })
    }
}

