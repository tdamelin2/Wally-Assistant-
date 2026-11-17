package dev.tim.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.generated.resources.Res
import dev.tim.assistant.generated.resources.ic_add
import dev.tim.assistant.generated.resources.ic_arrow_back
import dev.tim.assistant.generated.resources.ic_bar_chart
import dev.tim.assistant.generated.resources.ic_bedtime
import dev.tim.assistant.generated.resources.ic_chat
import dev.tim.assistant.generated.resources.ic_cloud_done
import dev.tim.assistant.generated.resources.ic_cloud_off
import dev.tim.assistant.generated.resources.ic_favorite
import dev.tim.assistant.generated.resources.ic_flag
import dev.tim.assistant.generated.resources.ic_folder
import dev.tim.assistant.generated.resources.ic_hardware
import dev.tim.assistant.generated.resources.ic_list
import dev.tim.assistant.generated.resources.ic_mail
import dev.tim.assistant.generated.resources.ic_menu
import dev.tim.assistant.generated.resources.ic_more_horiz
import dev.tim.assistant.generated.resources.ic_more_vert
import dev.tim.assistant.generated.resources.ic_school
import dev.tim.assistant.generated.resources.ic_screentime
import dev.tim.assistant.generated.resources.ic_settings
import dev.tim.assistant.generated.resources.ic_sync
import dev.tim.assistant.generated.resources.ic_today
import org.jetbrains.compose.resources.vectorResource

enum class LayoutMode { Wide, Narrow }

/** The layout follows the device, not the window width: the laptop is Wide, the phone is Narrow. */
fun layoutModeForDevice(laptop: Boolean): LayoutMode = if (laptop) LayoutMode.Wide else LayoutMode.Narrow

/** The five top-level hubs: the phone's bottom bar and the laptop's rail, in this order. */
enum class Hub(val label: String) { Today("Today"), Log("Log"), Chat("Chat"), Insights("Insights"), Browse("Browse") }

/** The hubs in display order (Today, Log, Chat, Insights, Browse). */
val hubOrder: List<Hub> = Hub.entries

/** Every screen the user can open; each lives in exactly one hub (see [hubChips]). */
enum class NavTab(val label: String) {
    Today("Today"), Checkins("Check-ins"), Spending("Spending"), Journal("Journal"), Meals("Meals"), Tasks("Tasks"), Dreams("Dreams"),
    Charts("Charts"), Health("Health"), Screentime("Screentime"), Goals("Goals"),
    Chat("Chat"),
    Files("Files"), Study("Study"), Mail("Mail"), Hardware("Hardware"), Settings("Settings"),
}

/** The chips (screens) of each hub, in display order. */
val hubChips: Map<Hub, List<NavTab>> = mapOf(
    Hub.Today to listOf(NavTab.Today),
    Hub.Log to listOf(NavTab.Checkins, NavTab.Spending, NavTab.Journal, NavTab.Meals, NavTab.Tasks, NavTab.Dreams),
    Hub.Insights to listOf(NavTab.Charts, NavTab.Health, NavTab.Screentime, NavTab.Goals),
    Hub.Chat to listOf(NavTab.Chat),
    Hub.Browse to listOf(NavTab.Files, NavTab.Study, NavTab.Mail, NavTab.Hardware, NavTab.Settings),
)

fun hubOf(tab: NavTab): Hub = hubChips.entries.first { tab in it.value }.key

/** What the + does on a chip. */
sealed interface AddAction {
    /** Today: the "What do you want to log?" sheet. */
    data object Picker : AddAction
    /** A Log chip: its own form for a new row. */
    data class Form(val target: FormTarget) : AddAction
    data object NewGoal : AddAction
    data object NewDoc : AddAction
    data object StudyAdd : AddAction
    data object NewEmail : AddAction
}

/** What the + opens on [chip], or null where the chip has nothing to add. */
fun addActionFor(chip: NavTab): AddAction? = when (chip) {
    NavTab.Today -> AddAction.Picker
    NavTab.Checkins -> AddAction.Form(FormTarget.Checkin(null))
    NavTab.Spending -> AddAction.Form(FormTarget.Spending(null))
    NavTab.Journal -> AddAction.Form(FormTarget.Journal(null))
    NavTab.Meals -> AddAction.Form(FormTarget.Meal(null))
    NavTab.Tasks -> AddAction.Form(FormTarget.Task(null))
    NavTab.Dreams -> AddAction.Form(FormTarget.Dream(null))
    NavTab.Goals -> AddAction.NewGoal
    NavTab.Files -> AddAction.NewDoc
    NavTab.Study -> AddAction.StudyAdd
    NavTab.Mail -> AddAction.NewEmail
    else -> null
}

/** True when a create request counter is above what the screen already handled. */
fun pendingRequest(request: Int, handled: Int): Boolean = request > handled

/**
 * Runs [onRequest] once for every + press on this screen. The handled counter starts at the current [requestNew], so
 * re-entering a chip never replays an old request, and cancelling then pressing + again works.
 */
@Composable
fun OnNewRequest(requestNew: Int, onRequest: () -> Unit) {
    var handled by rememberSaveable { mutableStateOf(requestNew) }
    LaunchedEffect(requestNew) {
        if (pendingRequest(requestNew, handled)) {
            handled = requestNew
            onRequest()
        }
    }
}

/** Every phone screen but Today and Chat gets its title and "Synced" line from the shell; those two draw their own header. */
fun showsShellHeader(chip: NavTab): Boolean = chip != NavTab.Today && chip != NavTab.Chat

/** The phone's sync error bar sits above every screen except Chat, whose own header is the top of the screen (the chat has its offline banner). */
fun showsPhoneSyncBar(chip: NavTab, status: SyncStatus): Boolean = showsErrorBar(status) && chip != NavTab.Chat

/** Remembers [chip] as the last-used chip of its hub. */
fun selectChip(memory: Map<Hub, NavTab>, chip: NavTab): Map<Hub, NavTab> = memory + (hubOf(chip) to chip)

/** The chip row only shows for hubs with two or more screens. */
fun showsChipRow(hub: Hub): Boolean = hubChips.getValue(hub).size >= 2

/** Each hub starts on its first chip. */
fun initialChips(): Map<Hub, NavTab> = hubChips.mapValues { it.value.first() }

enum class RowAction(val label: String) {
    Delete("Delete")
}

/** The layout mode of the whole window; forms read it to pick dialog or full-screen layout. */
val LocalLayoutMode = compositionLocalOf { LayoutMode.Wide }

/** Below this root window width (before the rail is subtracted) the laptop's side panel moves under the list. */
fun stacksSidePanel(widthDp: Float): Boolean = widthDp < 900f

/** The Log chip a form target belongs to (a dream opened read-first belongs to Dreams). */
private fun chipOfTarget(target: FormTarget): NavTab = when (target) {
    is FormTarget.Checkin -> NavTab.Checkins
    is FormTarget.Spending -> NavTab.Spending
    is FormTarget.Journal -> NavTab.Journal
    is FormTarget.Task -> NavTab.Tasks
    is FormTarget.Meal -> NavTab.Meals
    is FormTarget.Dream, is FormTarget.DreamRead -> NavTab.Dreams
}

/** The Log chip the laptop jumps to when a type is picked from Today's New. */
fun pickerChipFor(target: FormTarget): NavTab = chipOfTarget(target)

/**
 * The form the laptop shows docked in the side panel of [chip]: the [open] one when it belongs to this chip's type,
 * otherwise the chip's new-row form (the six Log chips), otherwise null (the form then opens as a dialog).
 */
fun dockedFormFor(chip: NavTab, open: FormTarget?): FormTarget? {
    if (open != null && chipOfTarget(open) == chip) return open
    return (addActionFor(chip) as? AddAction.Form)?.target
}

private val RailWidth = 88.dp
private val SidePanelWidth = 400.dp

@Composable
private fun tabIcon(tab: NavTab): ImageVector = vectorResource(
    when (tab) {
        NavTab.Today -> Res.drawable.ic_today
        NavTab.Checkins, NavTab.Spending, NavTab.Journal, NavTab.Meals, NavTab.Tasks -> Res.drawable.ic_list
        NavTab.Dreams -> Res.drawable.ic_bedtime
        NavTab.Charts -> Res.drawable.ic_bar_chart
        NavTab.Health -> Res.drawable.ic_favorite
        NavTab.Screentime -> Res.drawable.ic_screentime
        NavTab.Goals -> Res.drawable.ic_flag
        NavTab.Chat -> Res.drawable.ic_chat
        NavTab.Files -> Res.drawable.ic_folder
        NavTab.Study -> Res.drawable.ic_school
        NavTab.Mail -> Res.drawable.ic_mail
        NavTab.Hardware -> Res.drawable.ic_hardware
        NavTab.Settings -> Res.drawable.ic_settings
    },
)

@Composable
private fun hubIcon(hub: Hub): ImageVector = vectorResource(
    when (hub) {
        Hub.Today -> Res.drawable.ic_today
        Hub.Log -> Res.drawable.ic_list
        Hub.Insights -> Res.drawable.ic_bar_chart
        Hub.Chat -> Res.drawable.ic_chat
        Hub.Browse -> Res.drawable.ic_more_horiz
    },
)

@Composable
private fun syncVector(icon: SyncIcon): ImageVector = vectorResource(
    when (icon) {
        SyncIcon.Synced -> Res.drawable.ic_cloud_done
        SyncIcon.Syncing -> Res.drawable.ic_sync
        SyncIcon.NotSynced -> Res.drawable.ic_cloud_off
    },
)

/** Shared state of the shell, owned by App so a resize never resets it. */
class ShellState(
    val hub: Hub,
    val chip: NavTab,
    val onHub: (Hub) -> Unit,
    val onChip: (NavTab) -> Unit,
    val onAdd: (AddAction) -> Unit,
    val snackbar: SnackbarHostState,
)

/** The scrollable row of filter chips for a hub with several screens. */
@Composable
fun ChipRow(chips: List<NavTab>, selected: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier, centered: Boolean = false) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = if (centered) Alignment.CenterVertically else Alignment.Top,
    ) {
        chips.forEach { c ->
            FilterChip(
                selected = selected == c,
                onClick = { onSelect(c) },
                label = { Text(c.label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/**
 * The laptop's two-pane body: the [list] on the left and, when [side] is given, a side panel on the right (the docked
 * form or the opened item). When [stacked] (window narrower than 900 dp) the panel goes under the list. Both panes
 * are bounded, so each scrolls on its own.
 */
@Composable
fun LaptopColumns(stacked: Boolean, side: (@Composable () -> Unit)?, list: @Composable () -> Unit) {
    if (side == null) {
        Box(Modifier.fillMaxSize()) { list() }
    } else if (stacked) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { list() }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { side() }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { list() }
            Box(Modifier.width(SidePanelWidth).fillMaxHeight().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) { side() }
        }
    }
}

@Composable
fun WideShell(
    services: AppServices,
    state: ShellState,
    stacked: Boolean = false,
    side: (@Composable () -> Unit)? = null,
    destination: @Composable () -> Unit,
) {
    val status by services.sync.status.collectAsState()
    Row(Modifier.fillMaxSize()) {
        Rail(services, state, status)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (showsErrorBar(status)) SyncBar(services)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    val action = addActionFor(state.chip)
                    val chipRow = showsChipRow(state.hub)
                    if (chipRow || action != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (chipRow) ChipRow(hubChips.getValue(state.hub), state.chip, state.onChip, Modifier.weight(1f))
                            else Box(Modifier.weight(1f))
                            if (action != null) NewButton(onClick = { state.onAdd(action) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { LaptopColumns(stacked, side, destination) }
                }
                SnackbarHost(state.snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** "+ New" with the N shortcut hint, top right of the laptop content. */
@Composable
private fun NewButton(onClick: () -> Unit) {
    Row(Modifier.padding(end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onClick) {
            Icon(vectorResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New")
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text("N", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Rail(services: AppServices, state: ShellState, status: SyncStatus) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(RailWidth).fillMaxHeight(),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("W", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    hubOrder.forEach { h ->
                        RailItem(hubIcon(h), h.label, state.hub == h, { state.onHub(h) })
                    }
                }
                RailItem(
                    icon = syncVector(syncIconFor(status)),
                    label = railSyncLabel(status),
                    selected = false,
                    onClick = { services.syncInBackground() },
                    description = sidebarSyncText(status),
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** The one-word status under the rail's sync button; the full wording is its content description. */
private fun railSyncLabel(status: SyncStatus): String = when (syncIconFor(status)) {
    SyncIcon.Synced -> "Synced"
    SyncIcon.Syncing -> "Syncing"
    SyncIcon.NotSynced -> "Sync"
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, description: String = label) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val pill = Modifier.size(width = 52.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
        Box(
            if (selected) pill.background(MaterialTheme.colorScheme.secondaryContainer) else pill,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NarrowShell(services: AppServices, state: ShellState, destination: @Composable () -> Unit) {
    val status by services.sync.status.collectAsState()
    val action = addActionFor(state.chip)
    val chatUi = remember { PhoneChatUi() }
    LaunchedEffect(state.chip) { if (state.chip != NavTab.Chat) chatUi.drawer = false }
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalPhoneChatUi provides chatUi) {
    Scaffold(
        snackbarHost = { SnackbarHost(state.snackbar) },
        bottomBar = {
            // The chip row sits directly above the bar; the + (placed by Scaffold) floats 16 dp above both.
            Column {
                if (showsChipRow(state.hub)) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        ChipRow(hubChips.getValue(state.hub), state.chip, state.onChip, Modifier.fillMaxWidth().height(60.dp), centered = true)
                    }
                }
                NavigationBar {
                    hubOrder.forEach { h ->
                        NavigationBarItem(
                            selected = state.hub == h,
                            onClick = { state.onHub(h) },
                            icon = { Icon(hubIcon(h), contentDescription = null) },
                            label = { Text(h.label, maxLines = 1) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (action != null) {
                FloatingActionButton(onClick = { state.onAdd(action) }) {
                    Icon(vectorResource(Res.drawable.ic_add), contentDescription = "Add")
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.statusBars)) {
            if (showsPhoneSyncBar(state.chip, status)) SyncBar(services)
            if (showsShellHeader(state.chip)) ScreenHeader(services, state.chip)
            Box(Modifier.weight(1f).fillMaxWidth()) { destination() }
        }
    }
    }
    // The chat list drawer sits above the whole shell, bottom bar included (D9).
    if (state.chip == NavTab.Chat) PhoneChatDrawer(services, chatUi)
    }
}

/** The screen title with the "Synced ..." line under it, at the top of every phone screen. */
@Composable
private fun ScreenHeader(services: AppServices, chip: NavTab) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(chip.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SyncedLine(services)
    }
}
/** The three-dots button with a small menu; its clicks never reach the row behind it. */
@Composable
fun RowActionMenu(actions: List<RowAction>, onAction: (RowAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(vectorResource(Res.drawable.ic_more_vert), contentDescription = "Options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { open = false; onAction(action) })
            }
        }
    }
}

@Composable
fun BackIcon() {
    Icon(vectorResource(Res.drawable.ic_arrow_back), contentDescription = "Back")
}
