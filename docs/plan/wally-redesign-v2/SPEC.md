# Wally redesign v2 (Phases 15–16) — Spec

## 1. Goal
Make Wally look and feel like the approved Design canvas "Wally App Redesign" (https://claude.ai/artifact/RqdTz93wzV9CnRj6kBB4qP). Phase 14 gave both devices the same screens, switched by window width, so Tim said the UI "didn't really change". This work gives the **phone** a thumb-first layout (Phase 15) and the **laptop** a wide, mouse-and-keyboard layout (Phase 16), in Forest green, from one Compose Multiplatform codebase. Data, sync, chat logic and AI stay as they are, except for the small behaviour changes listed in §4.

## 2. Success criteria
Phase 15 (phone):
- SC-1: The app is Forest green in light mode on both devices; `Theme.kt` holds the only colour values (SPEC §8 tokens) apart from the chart series list. Check: the colour search in PLAN Task 15.1 returns only `Theme.kt` (Charts.kt takes its series from `WallyColors.chartSeries`).
- SC-2: The Android app always shows the phone layout and the Windows app always shows the laptop layout, whatever the window width. Check: `ShellLogicTest` `layoutModeForDevice` case; Tim resizes the laptop window to 500 dp wide and still sees the rail.
- SC-3: The phone bottom bar reads Today · Log · Chat · Insights · Browse, and multi-chip hubs show their chip row directly above the bar. Check: `ShellLogicTest` hub order case; Tim on the phone.
- SC-4: On Today the + opens a "What do you want to log?" picker with six types; on each Log chip the + opens only that type's form. Check: `ShellLogicTest` `addActionFor` cases; Tim on the phone.
- SC-5: Each Log chip's main screen shows recent items as drawn (check-ins today + yesterday with dot meters; spending week-by-category bar + today; journal today + yesterday; meals today's total + today; tasks grouped by due; dreams recent) and a "See all …" button that opens a month history screen. Check: `LogLogicTest`, `HistoryLogicTest`; Tim on the phone.
- SC-6: Journal and Dream writers keep an unsaved draft on the device after the screen is closed and the app restarted, until Save or Clear. Check: `DraftStoreTest`; Tim on the phone.
- SC-7: Tapping a dream opens it read-first with its interpretation and Interpret / Interpret again; no "Find patterns" control exists anywhere. Check: the pattern search in Task 15.8 returns nothing; Tim on the phone.
- SC-8: Task screens have no goal option; new tasks start with due date None and offer None / Today / Tomorrow / Pick date. Check: `DueChipTest`; the goal search in Task 15.7 returns nothing in `TaskForm.kt`.
- SC-9: Meals shows "Look up" only on failed estimates and in the meal's edit screen. "Failed" = the meal's latest MEAL_LOOKUP job failed, or it still has no calories 2 hours after it was saved. Check: `LogLogicTest` `mealStatus` cases.
- SC-10: Screentime offers All · Laptop · Phone (All = sum of both devices) and This day · 7 days; Label apps offers Phone · Laptop. Check: `LabelsTest` (`device = null` cases); Tim on the phone.
- SC-11: Every frame on the canvas Phone page except the Chat row (Today, the + picker, Log sheets and history, Insights incl. Label apps, Browse incl. All exams) matches in the Android app. Check: Tim with `ops/checklists/redesign-v2-phone.md`.
- SC-12: `.\gradlew check` passes at the end of Phase 15.

Phase 16 (laptop):
- SC-13: Every laptop frame on the canvas's Laptop page exists in the Windows app: rail, list + detail, side forms, hover actions, Health day list, Study three columns, Mail reading pane, Settings "Laptop only" group. Check: Tim with `ops/checklists/redesign-v2-laptop.md`.
- SC-14: Shortcuts work on the laptop when not typing: N runs the current screen's "new" action (log picker on Today, that type on a Log chip, new goal on Goals, new doc on Files), Esc cancels and Enter saves a side form (Enter adds a new line in multi-line fields), Ctrl+Enter saves Journal/Dream, Ctrl+F focuses Files search. Check: `ShortcutTest`; Tim on the laptop.
- SC-15: A laptop window narrower than 900 dp stacks the side panel under the list instead of squeezing it. Check: `ShellLogicTest` `stacksSidePanel` case; Tim resizes the window.
- SC-16: `.\gradlew check` passes at the end of Phase 16.

## 3. Scope
### In scope
- Phase 15: palette, device switch, hub order, phone shell, Today, all six Log chips (main, history, add), Dreams read-first view, Insights four chips + Label apps, Browse five chips + All exams, drafts, removal of the dream-pattern job and UI, removal of the task goal picker.
- Phase 16: the laptop versions of every screen above.
- Planning-side: Phase 14 check note for Tim; chat plan moved to Phase 17 with the canvas chat changes; Wally Voice moved to Phases 18–19.

### Out of scope (deferred)
| Item | Owned by |
|------|----------|
| Chat screens (phone and laptop), Memory screen inside Chat, offline send queue, hide-chats toggle | Phase 17 (chat redo) |
| Reminders group in Settings | Phase 17 |
| Dark mode | Not planned; would need a new phase |
| Changing the app icon colour (still amber) | Not planned |
| Deleting the `dream_pattern` table | Not planned (rows kept) |
| Compose UI/screenshot tests | Not planned (U23) |

## 4. Users and flows
- **Log on the phone:** Log → chip row (above the bar) → main screen shows recent items → + opens that chip's add sheet (Cancel + Save at the bottom) → "See all …" opens the month history with a month picker at the top.
- **Log anything from Today:** Today → + → picker with six tiles (Task, Check-in, Spending, Journal, Meal, Dream) → the chosen form.
- **Write in Journal/Dreams:** full-screen writer; leaving keeps a draft ("Draft kept if you leave"); returning reopens the draft; Save clears it.
- **Dreams:** list → tap → read view with interpretation → Edit for changes, Interpret / Interpret again for the AI.
- **Laptop:** rail → chips across the top → list on the left, side form or detail on the right → N for new, Esc/Enter in forms, hover a row for Done/Edit/Delete.

## 5. Data
No schema change. Existing tables only.
| Item | Where | Lifetime |
|------|-------|----------|
| Journal draft | `kv` key `draft.journal` (JSON: text) | Until Save or Clear; this device only |
| Dream draft | `kv` key `draft.dream` (JSON: text, mood, lucid, tags) | Until Save or Clear; this device only |
| Dream patterns | `dream_pattern` table, unchanged; no new rows are written | Kept |
| Task `goalId` | `task` table, unchanged; set only by Goals / AI steps | Kept |

## 6. Interfaces
Screens are the canvas frames. Pure functions the tests cover (names fixed in PLAN): `layoutModeForDevice`, `hubOrder`, `addActionFor`, `checkinDots`, `weekByCategory`, `mealDayTotal`, `mealStatus`, `taskGroups`, `monthRange`, `previousMonths`, `earlyOrLate`, `dueFromChip`, `DraftStore`, `initialWriterText`, `dayTotals` (device = null for All), `stacksSidePanel`, `shortcutFor`. Reused unchanged: `Repository.goalProgress`.

## 7. Edge cases and error states
| Case | Expected behaviour |
|------|--------------------|
| No check-in today | Today card shows "No check-in yet" with an Add button |
| Month with no items | History shows "Nothing in <Month>" |
| Meal estimate missing for some meals | Daily total adds only estimated meals and says "≈ … (2 not estimated)" |
| Task with no due date | Grouped under "No date"; never "Overdue" |
| Draft exists and an existing entry is opened for edit | The edit form shows the entry, not the draft; the draft stays for the next New |
| Screentime "All" with only phone data | Shows phone minutes; no error |
| Laptop window < 900 dp | Side panel moves under the list |
| Old dream pattern rows | Not shown; still synced; untouched |

## 8. Technical decisions
| Area | Choice | Version | Source (link) |
|------|--------|---------|---------------|
| UI | Compose Multiplatform (existing) | 1.12.2 | Locked in Overview.md Tech stack |
| Keys | All shortcuts caught once at window level: `Window(onPreviewKeyEvent = …)` in `desktopApp/main.kt`, forwarded to `App` as a `SharedFlow<Shortcut>` (works with nothing focused) | 1.12.2 | "Keyboard events" (section "Events in a window scope"), https://kotlinlang.org/docs/multiplatform/compose-desktop-keyboard.html (checked 2026-11-11) |
| Hover | `Modifier.onPointerEvent(PointerEventType.Enter / Exit)` in `jvmMain` behind an `expect fun Modifier.onHoverChange` (Experimental, `@OptIn(ExperimentalComposeUiApi::class)`); Android actual does nothing | 1.12.2 | "Mouse event listeners", https://kotlinlang.org/docs/multiplatform/compose-desktop-mouse-events.html (checked 2026-11-11) |
| Months | Own `data class HistoryMonth(year, month)`; no `kotlinx.datetime.YearMonth` (not confirmed in 0.8.0) | kotlinx-datetime 0.8.0 | Overview.md Tech stack |
| New libraries | None | — | U22 |

Colour tokens (light only, from the canvas):
| Role | Hex |
|------|-----|
| primary | #1F6F4A |
| onPrimary | #FFFFFF |
| primaryContainer | #DCEFE3 |
| onPrimaryContainer | #0F3D27 |
| background | #F6F7F4 |
| surface (cards, bars) | #FFFFFF |
| onSurface | #16201A |
| onSurfaceVariant (muted text) | #55605A |
| outline (chip/field border) | #D5DBD3 |
| outlineVariant (card border, dividers) | #E4E7E1 |
| surfaceVariant (soft fill) | #EFF2EE |
| error / overdue | #B3261E |
| empty dot | #DCE3DA |
| chart series | #0F3D27, #1F6F4A, #4E9A73, #9CCBAE |

## 9. Constraints
Phone: Samsung S25 (Android). Laptop: Dell XPS 16 (Windows). Touch targets ≥ 44 dp on the phone. Text contrast ≥ 4.5:1 with the tokens above. No personal data leaves Tim's devices.

## 10. Compatibility
- Respects: one codebase (`shared`), SQLDelight schema (no migration), sync rules, Undo controller and swipe rows from Phase 14, MM-dd-yyyy on the laptop, dream wall N10/N33 (dream data only in dream analysis; only `ui/dream/` and the existing data/sync files touch dream rows, enforced by `DreamWallTest`). The A18 "chat may use dreams when mentioned" rule only arrives in Phase 17.
- Earlier acceptance tests in `tests/acceptance` that use changed APIs are ported, not kept as aliases (PLAN Tasks 15.11, 16.5).
- Changes (Tim approves at Phase 15 approval): U5 palette/dark mode, U6 device switch, U7 hub order, U9 + on Today, U12 dream patterns, U14 dream screen, U15 task goal picker, U16 Look up, U20 Memory location (Phase 17).
