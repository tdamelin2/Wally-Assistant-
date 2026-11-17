# Wally UI redesign — PLAN

Reads with `SPEC.md` and `DECISIONS.md`. One phase in the assistant project, numbered after Phase 13
(call it Phase 14). The paths below are relative to `C:\Users\user\Projects\assistant`.
All UI code is in `shared/src/commonMain/kotlin/dev/tim/assistant/ui/` (`UI/` below).
Tests go in `shared/src/commonTest/kotlin/dev/tim/assistant/ui/` unless noted.

Before starting: re-read `UI/Shell.kt`, `UI/App.kt` and `UI/TodayScreen.kt` as they stand after Phase 13.
Phases 12–13 add the Mail tab, the Hardware tab and the approval UI, so `NavTab` will have more entries
than the 10 listed in this plan. Map every `NavTab` that exists into the hub table (SPEC §4).
If a tab exists that the table doesn't list, stop and ask Tim.

## Locked decisions this phase changes (Tim approves at the phase approval step)
See SPEC §7: dark mode becomes in scope, D60 changes, and Today no longer shows mail or events.

## Task 1 — Theme with dark mode
Precondition (Manual, Tim, before the phase starts): open https://material-foundation.github.io/material-theme-builder/, set the source colour to `#F5B301`, export as "Jetpack Compose", and save the colour file as `logs/wally-theme-export.kt`. The phase does not start until this file exists.
Files: new `UI/Theme.kt`, `UI/App.kt` (the `MaterialTheme {` call at line 103), any file the step 3 search finds.
Steps:
1. Copy the light and dark values from `logs/wally-theme-export.kt` into `Theme.kt` as `val WallyLight = lightColorScheme(...)` and `val WallyDark = darkColorScheme(...)`. Put a comment at the top with the tool URL, the seed and the export date. No new dependency.
2. Add `@Composable fun WallyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (isSystemInDarkTheme()) WallyDark else WallyLight, content = content)`, and replace `MaterialTheme {` in `App.kt` with `WallyTheme {`.
3. Run `Get-ChildItem shared\src, desktopApp\src, androidApp\src -Recurse -Filter *.kt | Select-String -Pattern 'Color\.[A-Z]|Color\('`. Replace every hit in UI code with a `MaterialTheme.colorScheme` role (for example `Color.Red` → `colorScheme.error`). Chart series colours may stay fixed if they read well on both backgrounds; list them in the phase report.

Verify: `.\gradlew build`, then the step 3 search returns only `Theme.kt` and the chart colours you listed.
Done when: both pass, and `.\gradlew :desktopApp:run` follows the Windows light/dark setting after a restart.

## Task 2 — Navigation: hubs, chips, bar, rail, + button
This is one task on purpose. Renaming the sections and rebuilding the shell must land together, or the old shell stops compiling halfway.
Files: `UI/Shell.kt`, `UI/App.kt`, `UI/lists/LogListsScreen.kt`, `UI/dream/DreamScreen.kt`, `UI/ShellLogicTest.kt`.
Steps:
1. **Model (Shell.kt):**
   - Add `enum class Hub(val label: String) { Today("Today"), Log("Log"), Insights("Insights"), Chat("Chat"), Browse("Browse") }`.
   - In `NavTab`, replace `Log` with `Checkins("Check-ins")`, `Spending("Spending")`, `Journal("Journal")`, `Meals("Meals")` and `Tasks("Tasks")`. Rename `Dream` to `Dreams("Dreams")`. Add `Settings("Settings")`.
   - Add `val hubChips: Map<Hub, List<NavTab>>` exactly as SPEC §4 (Today and Chat hold one tab each), and add `fun hubOf(tab: NavTab): Hub`.
   - Add `fun addTargetFor(chip: NavTab): FormTarget?`:
     - Today and Tasks → `FormTarget.Task(null)`
     - Checkins → `FormTarget.Checkin(null)`
     - Spending → `FormTarget.Spending(null)`
     - Journal → `FormTarget.Journal(null)`
     - Meals → `FormTarget.Meal(null)`
     - Dreams → `FormTarget.Dream(null)`
     - Anything else → `null`
   - Add `fun selectChip(memory: Map<Hub, NavTab>, chip: NavTab): Map<Hub, NavTab> = memory + (hubOf(chip) to chip)`.
   - Update `tabIcon`: Checkins/Spending/Journal/Meals/Tasks → `ic_list`, Dreams → `ic_bedtime`, Settings → `ic_settings`. The rest stay unchanged.
   - Delete `phoneBarTabs`, `moreSheetTabs`, `moreSelected`, `destinationsFor`, `showsAddButton`, `AddEntry`, `AddEntry.newForm`, `AddMenuAnchor` and their KDoc comments.
2. **Screens:** change `LogListsScreen(services, onOpenForm)` to `LogListsScreen(services, kind: NavTab, onOpenForm)`, where `kind` is one of Checkins/Spending/Journal/Meals/Tasks. Delete its private `LogTab` enum (line 46), its own tab row and its "Log" heading (line 102). In `DreamScreen.kt`, delete the "New dream" button (line 66); the + button replaces it.
3. **State (App.kt):** replace `tab` and `settingsOpen` with `hub: Hub` and `chipFor: Map<Hub, NavTab>`. `chipFor` starts with each hub's first chip and changes only through `selectChip`. The open chip is `chipFor.getValue(hub)`. Keep this state in `App` so a resize never resets it. The `when (tab)` at line 87 becomes `when (chip)`: Log chips → `LogListsScreen(services, chip, ...)`, Dreams → `DreamScreen`, Settings → `SettingsScreen`. The rest stay unchanged.
4. **NarrowShell:** remove the `topBar` and the More `ModalBottomSheet`. The `NavigationBar` shows the 5 hubs. The FAB shows when `addTargetFor(chip) != null` and opens that form. Put `windowInsetsPadding(WindowInsets.statusBars)` on the content.
5. **WideShell:** the sidebar becomes a rail. It holds the toggle (icon, label "Wally"), the 5 hubs, and the sync item at the bottom (existing `sidebarSyncText`). Remove the "New entry" button and the Settings item. A small FAB sits at the top of the content column and follows the same rule as on the phone. Keep the collapse behaviour and its stored flag.
6. **Chips:** add `@Composable fun ChipRow(chips: List<NavTab>, selected: NavTab, onSelect: (NavTab) -> Unit)`, using material3 `FilterChip`. Show it at the top of the content when the hub has 2 or more chips. Hub icons: Today `ic_today`, Log `ic_list`, Insights `ic_bar_chart`, Chat `ic_chat`, Browse `ic_more_horiz`. Chips have no icons.
7. In `RowActionMenu`, change the content description from "More" to "Options".
8. **ShellLogicTest:** delete `studyIsTheNinthDestinationInTheSidebarAndTheMoreSheet`, `tabsAreInOrderWithoutSettings`, `phoneBarAndMoreSheetCoverEveryTabOnce`, `moreIsSelectedWhileAChildTabIsOpen`, `addEntriesOpenNewForms` and `addButtonHiddenOnChatStudyAndTheOwnCreateScreens`. Keep `layoutModeSwitchesAt600`, `sidebarSettingParsing`, `syncIconAndErrorBar` and `sidebarSyncTextWording`. Add these tests:
   - every `NavTab` is in exactly one hub;
   - `hubOf` agrees with `hubChips`;
   - `addTargetFor` matches step 1, and returns `null` for Charts, Files, Settings and Chat;
   - `selectChip` keeps Insights on Health after switching to Log and back;
   - `showsChipRow(hub)` (new one-line helper: `hubChips.getValue(hub).size >= 2`) is false for Today and Chat and true for the other three. `ChipRow` is drawn only when it is true.
9. **Phase 12–13 tabs:** use the `NavTab` names Phase 12 actually gave the Mail and Hardware tabs. Place them in Browse as SPEC §4 says. Only a tab SPEC §4 doesn't mention at all triggers "stop and ask Tim".

Verify: run `.\gradlew :shared:jvmTest --tests "*ShellLogicTest*"`, then `.\gradlew build`, then `.\gradlew :desktopApp:run`. Resize the window across 600dp: the bar and rail swap, and the hub and chip stay selected.
Done when: all three pass, and `Get-ChildItem shared\src -Recurse -Filter *.kt | Select-String -Pattern 'AddEntry|moreSheetTabs|settingsOpen|NavTab\.Log\b|NavTab\.Dream\b|Text\("More"\)|contentDescription = "More"'` returns nothing.

## Task 3 — Today without mail or events
Files: `UI/TodayScreen.kt`, `shared/src/commonMain/kotlin/dev/tim/assistant/google/GoogleToday.kt` and `shared/src/commonTest/kotlin/dev/tim/assistant/google/GoogleTodayTest.kt`. Delete both files if step 4 finds nothing else using them; otherwise remove only the mail and event parts and their test cases.
Steps:
1. Remove the "Next 7 days", events, "Unread mail" and "Updated hh:mm" items, plus the `GoogleSnapshot` mail/events fields they used. Keep `ok` for the Google-disconnected banner.
2. Remove the `"title"` "Today" heading (it repeats the hub name).
3. Order: banner (if any) → briefing card titled "Briefing" → "Tasks" → "Exams". The briefing card is hidden when there's no briefing for today; keep the existing `if (briefing != null)`. Empty texts: "No open tasks", "No exams soon".
4. If `googleToday()` / `GoogleToday.kt` is then unused, delete it and its test. Do not touch `Repository.unreadMail()` (chat and Mail use the table).

Verify: `.\gradlew :shared:jvmTest`, then `Select-String -Path shared\src\commonMain\kotlin\dev\tim\assistant\ui\TodayScreen.kt -Pattern 'Unread mail|Next 7 days|unreadMail\(|calendarEvents\('` returns no hits.
Done when: both pass.

## Task 4 — Chat mail context gets the sender
Files: `shared/src/commonMain/kotlin/dev/tim/assistant/ai/ChatContext.kt` (mail line 116, section title line 31), `shared/src/jvmTest/kotlin/dev/tim/assistant/ai/ChatContextTest.kt`.
Steps:
1. Change the mail line to `"$date ${safeTake(m.sender, FREE_TEXT_MAX)}: ${safeTake(m.subject, FREE_TEXT_MAX)}"`.
2. Rename the section title "Unread mail subjects" (line 31) to "Unread mail (sender: subject)". No existing test checks the old title (I checked).
3. Add a test: one mail row with sender `"דנה <dana@example.com>"` produces a line that contains both the sender and the subject.
4. Do not change the dream-data exclusion (N10, N33): check that `DreamWallTest` still passes.

Verify: `.\gradlew :shared:jvmTest --tests "*ChatContextTest*" --tests "*DreamWallTest*"`.
Done when: both pass.

## Task 5 — No repeated labels
Files: new `UI/LabelUniquenessTest.kt`. Where a label or heading has to change: the screen files under `UI/` (`lists/`, `goals/`, `study/`, `dream/`, `files/`, `screentime/`, `ChatScreen.kt`, `SettingsScreen.kt`, `charts/ChartsScreen.kt`, `health/HealthScreen.kt`, and the Phase 12–13 screens).
Steps:
1. For each chip screen, find its heading texts (`Text("…")` with a `title*`/`headline*` style). If a heading equals its hub or chip name, remove it. Example: the "Screentime" heading inside the Screentime chip. If a heading repeats another heading on the same screen, rename one. "Exams" is a heading in `study/ExamsScreen.kt:68` and a button in `study/StudyScreen.kt:154`. Those are different views, so this is allowed, but it is the place to check first.
2. Put each screen's visible labels into an `internal val <screen>Labels: List<String>` beside the screen. Include the hub name, the chip names, and the section headings and screen-level buttons the screen shows. Form buttons (Save/Cancel/Delete) are exempt per R2.
3. `LabelUniquenessTest` builds, for each hub and chip, the list *hub labels + chip labels + that screen's labels*, and asserts that it has no case-insensitive duplicates.
4. Write `ops/checklists/redesign.md` (Tim's manual checklist). For each of the 5 hubs and each chip: phone light, phone dark, laptop light, laptop dark. Check that nothing is unreadable, clipped or repeated, and that the + button does the right thing. Add one line for the colours: the main accent is amber, matching the icon.
5. Known limit: the test proves the label lists are unique, not what the screens actually draw. If a list and its screen drift apart, only the manual checklist catches it.

Verify: `.\gradlew :shared:jvmTest --tests "*LabelUniquenessTest*"`.
Done when: the test passes and the checklist file exists.

## Task 6 — Release and docs
Files: `desktopApp/build.gradle.kts` (`packageVersion`, line 35 today) and `androidApp/build.gradle.kts` (`versionCode`, `versionName`, lines 35–36 today). Raise both by one minor version from whatever Phase 13 released, and `versionCode` by 1. Also `Overview.md` and `docs/plan/DECISIONS.md` only after Tim approves the SPEC §7 changes, `ops/checklists/phase1-e2e.md` version line.
Verify: `.\gradlew check`, then the full end-to-end checklist and `ops/checklists/redesign.md` on the phone and the laptop (Manual, Tim).
Done when: all of them pass.

## Execution checklist
- [ ] Tim saves `logs/wally-theme-export.kt` → precondition of Task 1
- [ ] Tim approves the SPEC §7 locked-decision changes → Task 6
- [ ] Task 1 theme → build passes; colour search clean; desktop follows the Windows theme
- [ ] Task 2 navigation → `ShellLogicTest` and build pass; resize check passes; old-helper search clean
- [ ] Task 3 Today → jvmTest passes; mail/events search clean
- [ ] Task 4 chat sender → `ChatContextTest` and `DreamWallTest` pass
- [ ] Task 5 labels → `LabelUniquenessTest` passes; `redesign.md` written
- [ ] Task 6 release → `.\gradlew check` passes; Tim passes both checklists on both devices in light and dark

Each task maps to one checklist line, and each line maps to one task.
