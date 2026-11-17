# Wally redesign v2 (Phases 15–16) — Plan

Design source for every screen: the canvas "Wally App Redesign", https://claude.ai/artifact/RqdTz93wzV9CnRj6kBB4qP, exported to `docs/plan/wally-redesign-v2/canvas/` (start at its `README.md`; coders read these files, they cannot open the link). Page **Phone** rows 1–10 and page **Laptop** rows 1–5; each row's green note is part of the spec. Coders read the `.dc.html` frame named in a task before building it. Paths in Tasks 15.x and 16.x are relative to `C:\Users\user\Projects\assistant` (Kotlin paths start at `shared/src/commonMain/kotlin/dev/tim/assistant/` unless written in full). Paths in Tasks P.x are relative to `C:\Users\user\Projects\AI_Planner_Claude`. All tests are pure-function tests in `shared/src/commonTest/kotlin/dev/tim/assistant/ui/` (run by `:shared:jvmTest`); no Compose UI tests (U23).

## Locked decisions
| Decision | Value |
|----------|-------|
| Codebase | One `shared` Compose Multiplatform module (1.12.2); no new libraries (U22) |
| Layout switch | By device: Android = phone, Windows = laptop (U6) |
| Palette | Forest green, light only, tokens in SPEC §8 (U5) |
| Hub order | Today · Log · Chat · Insights · Browse (U7) |
| Data | No schema change; drafts in local `kv` (U11) |
| Reused from Phase 14 | `UndoController`, `SwipeRow`, `RefreshBox`, sync timer + WorkManager, `DateField`/`TimeField`, `formatDate` (MM-dd-yyyy on laptop) |
| Dream wall | N10/N33 in force for Phases 15–16: dream data never enters any AI prompt except dream analysis; only files under `ui/dream/` (plus the existing data/sync files) touch dream rows, enforced by `DreamWallTest`. The A18 "chat may use dreams when mentioned" rule arrives in Phase 17 |
| Acceptance module | `.\gradlew check` runs `tests/acceptance`; earlier acceptance tests that use changed APIs are ported by the tester in Tasks 15.11 and 16.5 (same practice as Phase 14) |
| Chat UI | Not touched in 15–16; Phase 17 (U20) |

## Changes to locked decisions
| Decision | Old | New | Why | Approved by user? |
|----------|-----|-----|-----|-------------------|
| D58 / Phase 14 theme | Amber from the icon; dark mode follows the system | Forest green, light only | Tim disliked the colours; chose light only | Yes (2026-11-11 interview); Tim re-confirms at Phase 15 approval |
| D60 / Phase 14 layout | Bar below 600 dp, rail at 600 dp+ | Phone layout on Android, laptop layout on Windows | "Switch based on the device, not screen ratio" | Yes; re-confirm at approval |
| Phase 14 hub order | Today · Log · Insights · Chat · Browse | Today · Log · Chat · Insights · Browse | Chat in the thumb centre | Yes; re-confirm |
| Phase 14 `addTargetFor` | Today + adds a task | Today + opens a log-type picker | Canvas | Yes; re-confirm |
| Phase 8 dream patterns | "Find patterns" + DREAM_PATTERNS job | Removed (table and rows kept) | Tim asks Chat instead | Yes; re-confirm |
| Phase 8 task goal picker | Goal picker in TaskForm | No goal option on task screens | Tim: remove completely | Yes; re-confirm |
| Phase 12 Look up | On every meal row | Failed rows + edit screen only | Canvas | Yes; re-confirm |
| wally-assistant-v2 Memory | Browse › Memory | Memory inside Chat (Phase 17) | Canvas | Yes; re-confirm at Phase 17 |
| Phase numbers | Chat redo = 15, Voice = 16–17 | Chat redo = 17, Voice = 18–19 | Redesign first | Yes |

## Planning tasks (done by the planner with ai-dev-format, not by coding agents)
### Task P.1: Phase 14 check note
- Files: `dev_output/wally-redesign-v2/PHASE-14-CHECK-NOTE.md` (create)
- Steps: 1. Write a short note Tim pastes to the Phase 14 manager: skip AC-2 and AC-5 and the amber-look parts of AC-10/AC-17; still check swipe + Undo (SC-1), idle 11 min (SC-2), closed 20 min while charging (SC-3), pickers + `11-10-2026` (SC-4), and phase1-e2e routes.
- Verify: open the file → it names AC-2, AC-5 and the four behaviour checks.
- Done when: the note exists and Tim has it.

### Task P.2: Move the chat redo to Phase 17 with the canvas changes
- Files: `docs/plans/wally-assistant-v2/{SPEC,PLAN,DECISIONS}.md`, `dev_output/wally-assistant-v2/*`, `final-prompt_output/` and `prompt_output/` `Wally-Assistant-v2_Phase-15_Prompt.md` (renamed `…Phase-17_Prompt.md`), `docs/PERSONAL_ASSISTANT_PHASES.md` (modify)
- Steps: 1. Renumber Tasks 15.1–15.9 to 17.1–17.9 and every "Phase 15" that means the chat redo to "Phase 17". 2. In the chat UI task (old 15.8): build the canvas Chat row (phone: chat list drawer with "6 of 10", pinned Weekly report, Memory button, New chat; conversation with 30 s Undo, pick chips, yes/no card, typing dots; offline banner + send queue) and the laptop Chat frame (always-visible list, hide toggle Ctrl+B, Enter sends, Shift+Enter new line). 3. Replace "Browse › Memory" with "Chat › Memory (from the chat list)" in SC-8 and SC-11 wording. 4. Add the offline send queue: unsent messages are stored locally (kv key `chat.outbox`) and sent in order on the next successful sync. 5. Put the Reminders settings group in Browse › Settings (U21). 6. Add a decision row: chat may summarise dream patterns when Tim asks about dreams (dream wall rule unchanged).
- Verify: `Select-String -Path docs\plans\wally-assistant-v2\*.md -Pattern 'Browse > Memory|Browse › Memory|Task 15\.'` → no matches; `Test-Path final-prompt_output\Wally-Assistant-v2_Phase-17_Prompt.md, prompt_output\Wally-Assistant-v2_Phase-17_Prompt.md` → True, True; `Select-String -Path docs\plans\wally-assistant-v2\PLAN.md -Pattern 'chat\.outbox','Ctrl\+B','Reminders'` → at least one match each.
- Done when: the chat plan reads as Phase 17 and includes steps 2–6.

### Task P.3: Move Wally Voice to Phases 18–19
- Files: `docs/plans/wally-voice/*`, `dev_output/wally-voice/*`, `final-prompt_output/` and `prompt_output/` `Wally-Voice_Phase-16/17_Prompt.md` (renamed 18/19), `docs/PERSONAL_ASSISTANT_PHASES.md`, `checkpoint.md` (modify)
- Steps: 1. Renumber phases 16→18 and 17→19 and their task numbers; keep SPEC criterion numbers. 2. Change "depends on Phase 15 (chat)" to "Phase 17".
- Verify: `Select-String -Path docs\plans\wally-voice\*.md -Pattern 'Phase 16\b','Tasks? 1[67]\.'` → no matches; `Select-String -Path docs\plans\wally-voice\PLAN.md -Pattern 'Phase 18','Phase 19'` → at least one match each.
- Done when: Voice reads as 18–19 and depends on Phase 17.

### Task P.4: Drop-ins for Phases 15–16
- Files: `dev_output/wally-redesign-v2/{phase-15-section.md, phase-16-section.md, COPY-STEPS.md}` (create)
- Steps: 1. ai-dev-format Step 7 sections with AC-n from this PLAN's verify steps. 2. COPY-STEPS: copy this plan folder into `assistant/docs/plan/wally-redesign-v2`, paste sections into `phases.md`, add mapping rows, renumber the existing chat/voice rows.
- Verify: `Select-String -Path dev_output\wally-redesign-v2\phase-1*-section.md -Pattern '^## Phase 1[56]:'` → two matches.
- Done when: both sections and the copy steps exist.

## Phase 15: Phone redesign
Goal: the Android app matches the canvas Phone page (except Chat), in Forest green, with the device switch. Builds and verifies on its own: yes. During Phase 15 the laptop keeps its Phase 14 rail shell, recoloured, and shows the new phone-designed screens (they are common code) inside it; Phase 16 gives it its own layouts.

### Task 15.1: Green theme and device switch
- Files: `shared/src/commonMain/kotlin/dev/tim/assistant/ui/Theme.kt`, `ui/Shell.kt`, `ui/App.kt`, `ui/DatePlatform.kt`, `ui/DateField.kt`, `charts/Charts.kt`, `shared/src/androidMain/.../ui/DatePlatform.android.kt`, `shared/src/jvmMain/.../ui/DatePlatform.jvm.kt`, `commonTest/.../ui/ShellLogicTest.kt`, `jvmTest/.../ui/DateFormatTest.kt` (modify)
- Steps:
  1. `Theme.kt`: replace `WallyLight`/`WallyDark` with one `WallyLight = lightColorScheme(...)` using the SPEC §8 tokens; `WallyTheme` always uses it (remove `isSystemInDarkTheme`). Add `object WallyColors` for the non-scheme tokens (`emptyDot`, `softFill`, `overdue`, `chartSeries: List<Color>`). Keep `object Motion`.
  2. `charts/Charts.kt`: `SERIES_COLORS = WallyColors.chartSeries` (no colour literals left in Charts.kt).
  3. Rename `expect val usesLaptopDates` to `expect val isLaptop: Boolean` (android `false`, jvm `true`) and replace every use: `DateField.kt` (lines 45, 49, 95, 152) and `DateFormatTest.kt` (line 25). No alias is kept.
  4. `Shell.kt`: delete `WIDE_MIN_DP` and `layoutModeFor(widthDp)`; add `fun layoutModeForDevice(laptop: Boolean): LayoutMode = if (laptop) LayoutMode.Wide else LayoutMode.Narrow`. Reorder `enum class Hub` to Today, Log, Chat, Insights, Browse; add `val hubOrder: List<Hub> = Hub.entries`.
  5. `App.kt`: keep `BoxWithConstraints` (it also sets `maxFormHeight`, and Task 16.1 reads the width for `stacksSidePanel`); only replace `layoutModeFor(maxWidth.value)` with `layoutModeForDevice(isLaptop)`.
  6. `ShellLogicTest`: replace `layoutModeSwitchesAt600` with `layoutModeForDevice` cases; change the hub order asserted in `hubOfAgreesWithHubChips` to Today, Log, Chat, Insights, Browse; add `hubOrderIsTodayLogChatInsightsBrowse`.
- Verify: `.\gradlew :shared:jvmTest --tests "*ShellLogicTest*" --tests "*DateFormatTest*"` → passes; `Get-ChildItem shared\src, desktopApp\src, androidApp\src -Recurse -Filter *.kt | Select-String -Pattern 'Color\.[A-Z]|Color\(0x'` → matches only in `Theme.kt`.
- Done when: both apps are green and the layout no longer depends on width.

### Task 15.2: Phone shell, chip row and + rules
- Files: `ui/Shell.kt`, `ui/App.kt`, `ui/LogPickerSheet.kt` (create), `ShellLogicTest.kt` (modify)
- Steps:
  1. Phone (`LayoutMode.Narrow`): bottom bar in `hubOrder`; for hubs with `showsChipRow`, a 60 dp chip row directly above the bar (selected chip filled with `primary`); screen title + "Synced …" line at the top of each screen (canvas Phone rows 1–2).
  2. Replace `addTargetFor` (and its `ShellLogicTest` case `addTargetMapping`) with `sealed interface AddAction { data object Picker; data class Form(val target: FormTarget); data object NewGoal; data object NewDoc; data object StudyAdd; data object NewEmail }` and `fun addActionFor(chip: NavTab): AddAction?` = Today → Picker; Checkins/Spending/Journal/Meals/Tasks/Dreams → Form(own type); Goals → NewGoal; Files → NewDoc; Study → StudyAdd; Mail → NewEmail; else null. `ShellState.onAdd` changes from `(FormTarget) -> Unit` to `(AddAction) -> Unit`. Picker opens `LogPickerSheet`; Form opens the existing form route. The four others bump an `Int` counter in `App` state (`newGoalRequests`, `newDocRequests`, `studyAddRequests`, `newEmailRequests`) passed to `GoalsScreen`, `FilesScreen`, `StudyScreen`, `MailScreen` as `requestNew: Int`; each screen opens its own existing create UI in `LaunchedEffect(requestNew)` when the value is above 0. StudyAdd opens a small sheet with two choices, "Upload file" and "Add exam".
  3. `LogPickerSheet`: bottom sheet "What do you want to log?" with six 96 dp tiles (Task, Check-in, Spending, Journal, Meal, Dream) and Cancel (canvas Phone row 1, "+ opened").
  4. The + sits bottom-right, 16 dp above the chip row (or above the bar when there is no chip row).
  5. Tests: `addActionFor` for all 17 tabs.
- Verify: `.\gradlew :shared:jvmTest --tests "*ShellLogicTest*"` → passes.
- Done when: the phone bar, chip row and + match the canvas.

### Task 15.3: Today (phone)
- Files: `ui/TodayScreen.kt` (modify)
- Steps: 1. Header: date + "Synced …". 2. Briefing card (primaryContainer) with an expand arrow, 3 lines collapsed. 3. Tasks card: rows with title + due line, no checkbox; swipe right = done, left = delete via the existing `SwipeRow`. 4. Exams: two countdown tiles (nearest filled). 5. When today has no check-in, a slim card "No check-in yet" with an Add button (opens the check-in sheet) between Briefing and Tasks; hidden once today's check-in exists. 6. Empty states from SPEC §7.
- Verify: `.\gradlew :shared:jvmTest` → passes; `Select-String -Path shared\src\commonMain\kotlin\dev\tim\assistant\ui\TodayScreen.kt -Pattern 'Checkbox\('` → no matches.
- Done when: Today matches canvas Phone row 1, first frame.

### Task 15.4: Log main screens — Check-ins, Spending, Journal
- Files: `ui/lists/CheckinsScreen.kt`, `ui/lists/SpendingScreen.kt`, `ui/lists/JournalScreen.kt`, `ui/lists/LogLogic.kt`, `commonTest/.../ui/LogLogicTest.kt` (create); `ui/App.kt` (modify: route Checkins/Spending/Journal to the new screens; Meals and Tasks keep `LogListsScreen` until 15.5)
- Steps:
  0. Each chip screen owns its history: `var historyOpen by rememberSaveable { mutableStateOf(false) }`; "See all …" sets it true and shows the history screen in place; back (system back or the arrow) sets it false.
  1. Check-ins: cards for today and yesterday, each with four labelled 5-dot meters (`fun checkinDots(score: Long): Int` clamps 1–5) and the note; "See all check-ins" button.
  2. Spending: "This week" card with total, one stacked bar by category and a legend (`fun weekByCategory(rows: List<Spending>, today: LocalDate): List<Pair<String, Long>>`, Sunday-start week, largest first, darkest shade first); Today rows (icon, category, note, amount; tap = edit, swipe left = delete); "See all spending".
  3. Journal: Today and Yesterday sections, cards with time + 3 lines; "See all entries".
  4. Tests for both functions.
- Verify: `.\gradlew :shared:jvmTest --tests "*LogLogicTest*"` → passes.
- Done when: the three screens match canvas Phone rows 2–4 (main frames).

### Task 15.5: Log main screens — Meals, Tasks, Dreams
- Files: `ui/lists/MealsScreen.kt`, `ui/lists/TasksScreen.kt` (create), `ui/lists/LogListsScreen.kt` (delete), `ui/App.kt`, `ui/dream/DreamScreen.kt`, `ui/lists/LogLogic.kt`, `LogLogicTest.kt` (modify)
- Steps:
  1. Meals: today's estimated total card (`fun mealDayTotal(meals: List<Meal>): MealTotal(kcal, protein, carbs, fat, missing)`); today's rows with time, Meal/Snack tag, text and one status from `enum class MealStatus { Estimated, Estimating, Failed }` via `fun mealStatus(meal: Meal, lookup: Ai_job?, nowMs: Long): MealStatus` = Estimated when `meal.kcal != null`; Estimating when the latest MEAL_LOOKUP job for the meal is QUEUED or RUNNING; Failed when that job is FAILED, or when there is no such job, `meal.kcal == null` and `nowMs - meal.updatedAt > 2 h` (the server gives up silently after 3 tries, NutritionEstimator.kt:19); else Estimating. The job comes from a new `Repository.latestMealLookup(mealId: String): Ai_job?` = newest of `jobs()` with `kind == "MEAL_LOOKUP"` whose `inputJson` has `"mealId": mealId` (same parsing as the existing `latestJob(jobs, "MEAL_LOOKUP", "mealId", id)` in LogListsScreen.kt; move that helper into Repository). "Look up" shows only for Failed (and always in the meal's edit screen). "See all meals". App.kt routes Meals and Tasks to the new screens; `LogListsScreen.kt` is deleted.
  2. Tasks: open tasks grouped Overdue / Today / This week / Later / No date (`fun taskGroups(tasks: List<Task>, today: LocalDate): Map<TaskGroup, List<Task>>`); swipe right done, left delete; "See completed tasks".
  3. Dreams (stays in `ui/dream/DreamScreen.kt`): recent dream cards (date, 5-dot mood, Lucid tag, tags, 2 lines, "Interpreted" mark); "See all dreams"; no Patterns card.
  4. Tests for `mealDayTotal` (skips unestimated meals, counts them in `missing`), `mealStatus` (all three outcomes incl. the 2 h boundary, a FAILED job, and a RUNNING job on a 3-hour-old meal → Estimating), `taskGroups` (a no-due task is never Overdue).
- Verify: `.\gradlew :shared:jvmTest --tests "*LogLogicTest*"` → passes.
- Done when: the three screens match canvas Phone rows 5–7 (main frames).

### Task 15.6: History screens
- Files: `ui/lists/HistoryScreen.kt`, `ui/lists/HistoryLogic.kt`, `ui/dream/DreamHistory.kt`, `commonTest/.../ui/HistoryLogicTest.kt` (create); `data/Repository.kt`, `ui/lists/*Screen.kt` (modify)
- Steps:
  1. `data class HistoryMonth(val year: Int, val month: Int)` (no `YearMonth` type); `fun monthRange(m: HistoryMonth): Pair<LocalDate, LocalDate>`; `fun previousMonths(today: LocalDate, count: Int): List<HistoryMonth>`.
  2. `HistoryScreen(kind: NavTab, onBack: () -> Unit)` for Check-ins, Spending, Journal, Meals, Tasks: it owns the selected month as `rememberSaveable` state, starting at the current month; the dropdown lists `previousMonths(today, 12)`. Dreams use their own `ui/dream/DreamHistory.kt` with the same shape so dream rows stay inside `ui/dream/` (dream wall).
  3. Repository: `checkinsIn(from, to)`, `spendingIn`, `journalIn` (journal has no `localDate`: use the Jerusalem date of `createdAt`), `mealsIn`, `dreamsIn(from, to)` filter by `localDate`; `completedTasksIn(from, to)` filters by the Jerusalem date of `doneAt`, all months. Each filters the existing `selectAll`/list results in memory (no new `.sq` queries). The 7-day cutoff in `tasksForLog` is no longer used by any screen.
  4. Bodies: check-ins table with green 5-dot meters per score; spending month total + days with totals; journal days with time + 2 lines; meals days with kcal totals; completed tasks by finish day with "Done in <Month>" count and `fun earlyOrLate(due: LocalDate?, doneOn: LocalDate): String?` ("early" / "late" / null); dreams by week with Dreams/Lucid counts.
  5. Tests for `monthRange` (incl. February in a leap year), `previousMonths` and `earlyOrLate`.
- Verify: `.\gradlew :shared:jvmTest --tests "*HistoryLogicTest*"` → passes.
- Done when: each "See all …" opens its history as drawn.

### Task 15.7: Add sheets, writers and drafts
- Files: `ui/forms/CheckinForm.kt`, `SpendingForm.kt`, `MealForm.kt`, `TaskForm.kt`, `JournalForm.kt`, `ui/dream/DreamForm.kt` (modify), `ui/forms/DraftStore.kt` (create), `commonTest/.../ui/forms/DraftStoreTest.kt`, `DueChipTest.kt` (create)
- Steps:
  1. Phone sheets with Cancel + Save in one bottom row: Check-in (four rows of 1–5 buttons, all start at 3, note, Today date button); Spending (large ₪ amount, 7 category tiles, note, date button, "Save ₪48 · Food"); Meal (Meal | Snack, text, date + time buttons, note line about estimates); Task (title, due chips None/Today/Tomorrow/Pick date with None selected, `enum class DueChip { None, Today, Tomorrow, Pick }` and `fun dueFromChip(chip: DueChip, today: LocalDate, picked: LocalDate?): LocalDate?`; remove the goal picker from the form; new tasks have no Done checkbox; save with the 4-argument `Repository.saveTask(id, title, dueDate, done)` so an existing task's `goalId` is kept, Repository.kt:172).
  2. Journal and Dream: full-screen writers (✕ close, Save at the bottom). Dream adds 1–5 mood (starts 3), round Lucid checkbox, tags; date button labelled "Last night" (value = today).
  3. `class DraftStore(private val get: (String) -> String?, private val put: (String, String) -> Unit)` with `load(key): String?` (returns null for missing or empty), `save(key, json)`, `clear(key)` = `put(key, "")`; built as `DraftStore(repo::setting, repo::putSetting)` (existing `Repository.setting`/`putSetting`, Repository.kt:64/66, backed by `kv`; no new query). The writer calls `save` from a composable `LaunchedEffect(text)` with `delay(500)` (debounce lives in the UI, not the store). Keys `draft.journal`, `draft.dream`. `fun initialWriterText(existing: String?, draft: String?): String = existing ?: draft ?: ""` (editing an entry shows the entry; the draft stays for the next New). "Draft kept if you leave" line.
  4. Tests: `DraftStoreTest` with an in-memory map (save → load → clear → load is null; empty value is null; `initialWriterText` both cases), `DueChipTest` (all four chips).
- Verify: `.\gradlew :shared:jvmTest --tests "*DraftStoreTest*" --tests "*DueChipTest*"` → passes; `Select-String -Path shared\src\commonMain\kotlin\dev\tim\assistant\ui\forms\TaskForm.kt -Pattern 'goal'` → no matches.
- Done when: every add sheet matches its canvas frame and drafts survive an app restart.

### Task 15.8: Dreams read-first and pattern removal
- Files: `ui/dream/DreamScreen.kt`, `ui/dream/DreamDetail.kt` (create), `ai/dream/DreamAnalysis.kt` (modify), `jvmTest/.../ai/DreamPatternDeleteTest.kt` (delete), `jvmTest/.../ai/DreamWallTest.kt` (modify)
- Steps:
  1. Tap a dream → `DreamDetail`: date, mood dots, Lucid, tags, Edit, full text, green Interpretation card ("by <model>", Interpret again) or an Interpret button when none.
  2. Remove the "Find patterns" button, pattern cards, the `DREAM_PATTERNS` entry in `DreamAnalysis.handlers()`, the `patterns()` function and the `DREAM_PATTERNS` mention in the KDoc at DreamAnalysis.kt:39. Keep `dream_pattern` queries, sync and rows. Old QUEUED `DREAM_PATTERNS` jobs fail as an unknown kind with the existing fixed text (accepted; no cancellation code).
  3. `DreamDetail.kt` and `DreamHistory.kt` load their own dream rows (`repo.dreamById(id)`, `repo.dreamsIn(from, to)`), so both match the `DreamWallTest` pattern. `DreamWallTest`: add both files to the `allowed` set (line 298; the test compares sets exactly at line 316); remove the `DREAM_PATTERNS` cases (around lines 179 and 208); keep the `DREAM_INTERPRET` checks. Delete `DreamPatternDeleteTest.kt`.
- Verify: `Get-ChildItem shared\src\commonMain -Recurse -Filter *.kt | Select-String -Pattern 'DREAM_PATTERNS|Find patterns'` → no matches; `.\gradlew :shared:jvmTest --tests "*DreamWallTest*"` → passes.
- Done when: dreams open read-first and nothing can create pattern summaries.

### Task 15.9: Insights (phone)
- Files: `charts/ChartsScreen.kt`, `health/HealthScreen.kt`, `ui/screentime/ScreentimeScreen.kt`, `ui/screentime/LabelAppsScreen.kt` (create), `usage/Labels.kt`, `ui/goals/GoalsScreen.kt`, `shared/src/jvmTest/kotlin/dev/tim/assistant/usage/LabelsTest.kt` (modify)
- Steps:
  1. Charts: four cards (check-in lines in four greens with solid/dashed styles and legend; spending by category bars; per month; tasks per week).
  2. Health: three tiles (sleep last night, steps yesterday, resting HR), sleep line with 8 h guide, steps bars, workouts; no day list on phone.
  3. Screentime: All · Laptop · Phone pill; in `usage/Labels.kt` change `inScope(s, device: String, dates)` and its callers `dayTotals`, `topApps`, `unsortedApps` (and `totalsByApp` if present) to `device: String?` where `null` = all devices (no new functions); replace the direct `it.device == device` filter in ScreentimeScreen.kt (around line 134) with the same null rule. This day · 7 days; unlabelled banner (count across the chosen device(s)) → Label apps; minutes per label (Productive, Unproductive, Neutral, Not labelled); top apps; Listening (Spotify plays are not per device and show under every option); link "Labels, rules and suggestions".
  4. Label apps screen: Phone · Laptop pill (no All); unlabelled apps with three buttons; Suggest labels + Accept; rules with Change, swipe left delete.
  5. Goals: cards with title, why and a progress bar from the existing `Repository.goalProgress(goalId): GoalProgress(done, total)` (Repository.kt:874); "Plan steps" when total = 0; Dream week card; + = new goal.
  6. Tests in the existing `LabelsTest.kt`: `dayTotals`, `topApps` and `unsortedApps` with `device = null` equal the combined phone + laptop result; existing device cases unchanged.
- Verify: `.\gradlew :shared:jvmTest --tests "*LabelsTest*"` → passes.
- Done when: the four chips + Label apps match canvas Phone row 9.

### Task 15.10: Browse (phone)
- Files: `ui/files/FilesScreen.kt`, `ui/study/StudyScreen.kt`, `ui/study/ExamsScreen.kt`, `ui/mail/MailScreen.kt`, `ui/hardware/HardwareScreen.kt`, `ui/SettingsScreen.kt` (modify)
- Steps:
  1. Files: search, folder chips (All, folders, No folder, + Folder), doc rows, "Approvals · N" link; swipe left delete, long-press menu Move/Rename.
  2. Study: course cards (exam tag, Practice/Quiz/Summary or Make study set), "Check files" and "All exams" buttons. All exams: Upcoming with countdown tiles, Past, Add exam.
  3. Mail: Wally suggests (Approve/Reject), Unread, Drafts; Organise inbox; + new email.
  4. Hardware: "What is going wrong?" + Explain; Past checks.
  5. Settings: groups Sync and Connections; laptop-only items hidden on the phone (`isLaptop`).
- Verify: `.\gradlew :shared:jvmTest` → passes; `.\gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL.
- Done when: the five chips + All exams match canvas Phone row 10.

### Task 15.11: Labels, checklist and release
- Files: `commonTest/.../ui/LabelUniquenessTest.kt`, `ops/checklists/redesign-v2-phone.md` (create), `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts` (version), `tests/acceptance/src/test/kotlin/Phase*AcceptanceTest.kt` (tester ports), `reports/phase-15.md` (create)
- Steps:
  1. Update label lists for new screens; no screen repeats a label.
  2. Checklist: one line per canvas Phone frame except the Chat row, plus SC-1…SC-11.
  3. Release 1.13.0 / versionCode 9.
  4. Tester ports earlier acceptance tests that use changed APIs (they won't compile or pass otherwise): find them with `Get-ChildItem tests\acceptance\src -Recurse -Filter *.kt | Select-String -Pattern 'layoutModeFor|WIDE_MIN_DP|addTargetFor|isSystemInDarkTheme|DREAM_PATTERNS|usesLaptopDates|LogListsScreen|"Insights", "Chat"|1\.12\.0|versionCode'`. Known today: `Phase6AcceptanceTest.kt` (imports `WIDE_MIN_DP`/`layoutModeFor`, reads `ui/lists/LogListsScreen.kt` as text at :163, addTargetFor), `Phase7AcceptanceTest.kt` and `Phase8AcceptanceTest.kt` (addTargetFor), `Phase10AcceptanceTest.kt` and `Phase12AcceptanceTest.kt` (addTargetFor), `Phase14AcceptanceTest.kt` (layoutModeFor, `isSystemInDarkTheme`, hub labels at :73, addTargetFor, the `versionCode\s*=\s*8` regex at :432), `Phase8PatternDeleteAcceptanceTest.kt` (DREAM_PATTERNS: retire with a note), version pins in several `Phase*AcceptanceTest.kt`. A `versionCode` match is changed only when it pins 8. Ports keep the original intent where it still applies (e.g. addTargetFor cases become addActionFor cases); no old API is kept as an alias.
  5. Give Tim the Overview text for U5–U16 (never staged by agents).
- Verify: the search in step 4 → remaining matches are only comments explaining a retirement and `versionCode` lines pinning 9; `.\gradlew check` → BUILD SUCCESSFUL; Tim confirms on the laptop that the app still opens and the Log chips work in the old rail shell (checklist line "laptop still usable with phone-style Log screens").
- Done when: `reports/phase-15.md` lists each SC with pass/fail and Tim has run the checklist.

## Phase 16: Laptop redesign
Goal: the Windows app matches the canvas Laptop page (except Chat). Builds and verifies on its own: yes.

### Task 16.1: Laptop shell, panels, hover and shortcuts
- Files: `ui/Shell.kt` (laptop part), `ui/App.kt`, `ui/forms/FormParts.kt`, `desktopApp/src/main/kotlin/main.kt` (modify); `ui/LaptopParts.kt`, `shared/src/jvmMain/kotlin/dev/tim/assistant/ui/Hover.jvm.kt`, `shared/src/androidMain/kotlin/dev/tim/assistant/ui/Hover.android.kt`, `ui/Hover.kt`, `commonTest/.../ui/ShortcutTest.kt`, `ops/checklists/redesign-v2-laptop.md` (create); `ShellLogicTest.kt` (modify)
- Steps:
  1. Rail: W mark, five hubs in `hubOrder`, sync button at the bottom; chips across the top of the content; "+ New" button top right where `addActionFor` is not null.
  2. `LaptopColumns(list, side)` and `fun stacksSidePanel(widthDp: Float): Boolean = widthDp < 900f`.
  3. Hover: `ui/Hover.kt` declares `expect fun Modifier.onHoverChange(onChange: (Boolean) -> Unit): Modifier`; `Hover.jvm.kt` implements it with `Modifier.onPointerEvent(PointerEventType.Enter/Exit)` under `@OptIn(ExperimentalComposeUiApi::class)` (documented, SPEC §8); `Hover.android.kt` returns `this`. `HoverActions` shows Done/Edit/Delete icons on hovered rows.
  4. Text entry: one wrapper `WallyTextField(…, multiline: Boolean)` in `FormParts.kt` around `OutlinedTextField` that sets a shared `TextEntryState(inTextField, multiline)` through `Modifier.onFocusChanged`; every `OutlinedTextField(` in `shared/src/commonMain` goes through it.
  5. `enum class Shortcut { NewItem, Cancel, Save, SaveLong, FocusSearch }`; `fun shortcutFor(key: Key, ctrl: Boolean, inTextField: Boolean, multiline: Boolean): Shortcut?` = N without Ctrl and not `inTextField` → NewItem; Esc → Cancel; Enter without Ctrl and not `multiline` → Save; Ctrl+Enter → SaveLong; Ctrl+F → FocusSearch; else null. Caught at window level so keys work with nothing focused: `main.kt` creates `val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)`, passes `onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && shortcutFor(e.key, e.isCtrlPressed, entry.inTextField, entry.multiline)?.let { shortcuts.tryEmit(it) } == true }` to `Window(` (main.kt:172), and passes `shortcuts` to `App(…, shortcuts: SharedFlow<Shortcut>? = null)`; Android passes null. App collects it: NewItem runs `onAdd(addActionFor(current chip))` (Today → log picker; Goals → new goal; Files → new doc; nothing where null); Cancel/Save/SaveLong go to the open side form; FocusSearch to the Files search field.
  6. Create `ops/checklists/redesign-v2-laptop.md` with lines for this task's manual checks; Tasks 16.2–16.4 add their frame lines.
  7. Tests: `ShortcutTest` (every row above incl. N while typing → null, Enter in a multi-line field → null), `stacksSidePanel` in `ShellLogicTest`.
- Verify: `.\gradlew :shared:jvmTest --tests "*ShortcutTest*" --tests "*ShellLogicTest*"` → passes; `.\gradlew :desktopApp:compileKotlin` → BUILD SUCCESSFUL; `Get-ChildItem shared\src\commonMain -Recurse -Filter *.kt | Select-String -Pattern 'OutlinedTextField\(' | Where-Object { $_.Path -notmatch 'FormParts\.kt' }` → no matches; manual: `.\gradlew :desktopApp:run`, right after launch press N (picker opens), Esc (closes), hover a task row (icons appear), Ctrl+F on Files (search focused); results go in `reports/phase-16.md`.
- Done when: the rail, panels, hover and shortcuts work on an empty hub.

### Task 16.2: Laptop Today and Log
- Files: `ui/TodayScreen.kt`, `ui/lists/*Screen.kt`, `ui/dream/DreamScreen.kt`, `ui/dream/DreamDetail.kt`, `ui/forms/*` (modify); dream panes stay in `ui/dream/` (dream wall)
- Canvas frames: "Laptop · Today", "Laptop · Log (Spending)", "Laptop · Log · Check-ins", "… · Journal", "… · Meals", "… · Tasks", "… · Dreams", "Laptop · Log · Dreams — a dream opened"
- Steps: 1. Today two columns (briefing in full + tasks with hover Done/Delete | exams), header date `Wednesday, 11-11-2026`. 2. Each Log chip: list/cards on the left, its add form docked on the right (Esc/Enter), typeable MM-dd-yyyy dates. 3. Journal: entries list + writing pane (Ctrl+Enter). 4. Dreams: list with Interpret on uninterpreted rows + writer pane (mood, Lucid, tags, Save & interpret); clicking a dream shows the read view with Interpretation on the right.
- Verify: `.\gradlew :desktopApp:compileKotlin` → BUILD SUCCESSFUL; `.\gradlew :shared:jvmTest --tests "*DreamWallTest*"` → passes; Tim ticks the eight frames above in `ops/checklists/redesign-v2-laptop.md` (lines written in this task).
- Done when: Today and all six Log chips match the frames above.

### Task 16.3: Laptop Insights
- Files: `charts/ChartsScreen.kt`, `health/HealthScreen.kt`, `ui/screentime/*`, `ui/goals/GoalsScreen.kt` (modify)
- Canvas frames: "Laptop · Insights" (Charts), "Laptop · Insights · Health", "… · Screentime", "… · Goals"
- Steps: 1. Charts in a 2-column grid. 2. Health tiles, sleep/steps, workouts and the day list (laptop only; last 7 days, one row per day with sleep, steps, resting HR). 3. Screentime with All/Laptop/Phone + This day/7 days on top, Label apps as a right side panel. 4. Goals list on the left, selected goal's steps (its tasks, done ticked) on the right, "+ New goal" top right.
- Verify: `.\gradlew :desktopApp:compileKotlin` → BUILD SUCCESSFUL; Tim ticks the four frames above in `ops/checklists/redesign-v2-laptop.md`.
- Done when: the four chips match the frames above.

### Task 16.4: Laptop Browse
- Files: `ui/files/*`, `ui/study/*`, `ui/mail/*`, `ui/hardware/*`, `ui/SettingsScreen.kt` (modify)
- Canvas frames: "Laptop · Browse (Files)", "Laptop · Browse · Study", "… · Mail", "… · Hardware", "… · Settings"
- Steps: 1. Files: three columns — folders (All docs, each folder, No folder, + New folder, Approvals · N) | doc list (+ New doc, title + folder + date) | open doc (title, Edit, ⋮ menu with Move/Rename/Delete, text preview); search box top right, Ctrl+F focuses it. 2. Study: three columns — courses (name + exam countdown, Upload file) | selected course (name, "Check Moodle for new files" switch, Practice/Quiz/Summary tiles with flashcard/question counts, Files list with status and Open / Make study set) | All exams (countdown tiles, Past, + Exam). 3. Mail: left column with Wally suggests (Approve/Reject), Unread, Drafts | reading pane (subject, sender, date, text, Draft reply); Organise inbox and + New email top right. 4. Hardware: form + past checks | selected explanation with delete. 5. Settings: three cards in this phase — Sync, Connections, Laptop only (Start with Windows, Keep running in tray, XPS Ollama URL). The Reminders card on the canvas is added in Phase 17 (U21).
- Verify: `.\gradlew :desktopApp:compileKotlin` → BUILD SUCCESSFUL; Tim ticks the five frames above in `ops/checklists/redesign-v2-laptop.md`.
- Done when: the five chips match the frames above.

### Task 16.5: Checklist and release
- Files: `ops/checklists/redesign-v2-laptop.md` (modify), version files, `reports/phase-16.md` (create)
- Steps: 1. Complete the checklist: the frame lines from 16.2–16.4 plus SC-13…SC-15. 2. Release 1.14.0 / versionCode 10. 3. Tester updates acceptance version pins from 1.13.0 / 9 (search `1\.13\.0|versionCode = 9` in `tests\acceptance\src`).
- Verify: `.\gradlew check` → BUILD SUCCESSFUL.
- Done when: `reports/phase-16.md` lists SC-13…SC-16 with results.

## Execution checklist
- [ ] Task P.1 — Phase 14 check note — verify: file names AC-2, AC-5 and four behaviour checks
- [ ] Task P.2 — Chat plan → Phase 17 with canvas changes — verify: `Select-String … 'Browse > Memory|Browse › Memory|Task 15\.'` → none
- [ ] Task P.3 — Voice → Phases 18–19 — verify: `Select-String … 'Phase 16\b','Tasks? 1[67]\.'` → none; 'Phase 18' and 'Phase 19' present
- [ ] Task P.4 — Drop-ins — verify: two `## Phase 1[56]:` headings
- [ ] Task 15.1 — Theme + device switch — verify: `*ShellLogicTest*`, `*DateFormatTest*` + colour search (Theme.kt only)
- [ ] Task 15.2 — Phone shell + + rules — verify: `*ShellLogicTest*`
- [ ] Task 15.3 — Today — verify: `:shared:jvmTest` + Checkbox search
- [ ] Task 15.4 — Check-ins, Spending, Journal — verify: `*LogLogicTest*`
- [ ] Task 15.5 — Meals, Tasks, Dreams — verify: `*LogLogicTest*`
- [ ] Task 15.6 — History — verify: `*HistoryLogicTest*`
- [ ] Task 15.7 — Add sheets + drafts — verify: `*DraftStoreTest*`, `*DueChipTest*`, goal search
- [ ] Task 15.8 — Dreams read-first + pattern removal — verify: pattern search + `*DreamWallTest*`
- [ ] Task 15.9 — Insights — verify: `*LabelsTest*`
- [ ] Task 15.10 — Browse — verify: `:shared:jvmTest` + `:androidApp:assembleDebug`
- [ ] Task 15.11 — Labels, checklist, release, acceptance ports — verify: port search + `.\gradlew check`
- [ ] Phase 15 gate: `.\gradlew check` passes, Tim passes `ops/checklists/redesign-v2-phone.md` on the phone, and the laptop is still usable with phone-style Log screens
- [ ] Task 16.1 — Laptop shell, hover, shortcuts — verify: `*ShortcutTest*`, `*ShellLogicTest*`, `:desktopApp:compileKotlin`, OutlinedTextField search, manual N/Esc/hover/Ctrl+F run
- [ ] Task 16.2 — Laptop Today + Log — verify: `:desktopApp:compileKotlin`, `*DreamWallTest*`, 8 frame lines
- [ ] Task 16.3 — Laptop Insights — verify: `:desktopApp:compileKotlin`, 4 frame lines
- [ ] Task 16.4 — Laptop Browse — verify: `:desktopApp:compileKotlin`, 5 frame lines
- [ ] Task 16.5 — Checklist + release — verify: `.\gradlew check`
- [ ] Phase 16 gate: `.\gradlew check` passes and Tim passes `ops/checklists/redesign-v2-laptop.md` on the laptop

## Coverage check
| Success criterion (SPEC §2) | Covered by task(s) |
|-----------------------------|--------------------|
| SC-1 green, colours only in Theme | 15.1 |
| SC-2 device switch | 15.1 |
| SC-3 bar order + chip row | 15.1, 15.2 |
| SC-4 + rules and picker | 15.2 |
| SC-5 Log main screens + history | 15.4, 15.5, 15.6 |
| SC-6 drafts | 15.7 |
| SC-7 dreams read-first, no patterns | 15.5, 15.8 |
| SC-8 no goal option, due chips | 15.7 |
| SC-9 Look up rule | 15.5 |
| SC-10 screentime pills | 15.9 |
| SC-11 every Phone frame except Chat (Today, picker, Log sheets and history, Insights, Browse) | 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.9, 15.10, 15.11 |
| SC-12 Phase 15 check | 15.11 |
| SC-13 laptop frames | 16.2, 16.3, 16.4, 16.5 |
| SC-14 shortcuts | 16.1, 16.2, 16.4 |
| SC-15 side panel stacks | 16.1 |
| SC-16 Phase 16 check | 16.5 |
