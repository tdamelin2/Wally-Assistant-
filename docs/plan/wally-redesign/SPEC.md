# Wally UI redesign — SPEC

Track: Large. Runs as one new phase **after Phase 13** of the assistant project (D1).
Research: `RESEARCH.md`. Decisions: `DECISIONS.md` (R1–R17).

## 1. Goal
Make Wally easier to scan and navigate on the Samsung S25 phone and the Dell XPS 16 laptop.
The 10+ sections become 5 hubs, the look gets refreshed with light and dark themes, and no visible
screen shows the same word twice.

## 2. Done when (observable)
1. Phone: bottom bar with exactly 5 items **Today · Log · Insights · Chat · Browse**, no top bar.
2. Laptop (window ≥ 600dp): side rail with the same 5 items, collapsible like today's sidebar, content in one centred column (max 720dp).
3. Each hub with more than one part shows a chip row at the top. The last chip picked in each hub is remembered while the app runs.
4. Today shows only the morning briefing, open tasks and upcoming exams. **No unread mail, no calendar events.**
5. Chat's AI context includes each unread mail's **sender and subject**.
6. The + button: on Today it opens a new Task; on Log it opens a new entry of the open chip's type (Log's Tasks chip lists all tasks, done ones too; Dreams loses its own "New dream" button). Nowhere else.
7. The colours come from the Wally icon's amber (seed `#F5B301`). Light/dark follows the device setting.
8. On any one visible screen, no label word-group repeats (hub name, chip name, section heading, button text). Standard buttons (Save, Cancel, Delete) may repeat across *different* screens.
9. `.\gradlew check` passes, including the new `LabelUniquenessTest`, and Tim passes `ops/checklists/redesign.md` on both devices in both themes.

## 3. Scope
**In:** navigation shell, hub/chip structure, Today content, theme (colours, typography left at M3 default), dark mode, label wording, chat mail context (sender), the + button behaviour.

**Out (owner):**
- Two-pane / list-detail layouts on the laptop — not planned; revisit only if Tim asks (R9).
- Manual light/dark switch in Settings — not planned (R7).
- `NavigationSuiteScaffold` / new libraries — not used (R13).
- Redesigning the inside of individual forms and screens beyond labels and theme — a later phase if wanted.
- Wally reference look (`wally-reference.webp`) — stays out of scope.
- Any change to data, sync, server or schema.

## 4. Hub map
| Hub | Chips (order) | + button |
|---|---|---|
| Today | none (single page: Briefing, Tasks, Exams) | New task |
| Log | Check-ins · Spending · Journal · Meals · Tasks · Dreams | New entry of open chip |
| Insights | Charts · Health · Screentime · Goals | none |
| Chat | none | none |
| Browse | Files · Study · Mail · Hardware · Settings | none |

Log's chip order (Meals before Tasks) differs from today's inner tabs on purpose; keep it. Mail and Hardware are the tabs Phase 12 adds (R12). Phase 13 approval cards stay where Phase 13 puts them.
Sync status: shown as a row at the bottom of the rail (laptop) and as the first line of the Settings chip (phone). The red sync error bar keeps working as now on both.

## 5. Data
No new tables, no new settings. Chip memory is in-memory state in `App` (lost on restart; first chip by default). Sidebar collapsed flag keeps its existing storage.

## 6. Edge cases
- Empty Today: each section shows one short line ("No open tasks", "No exams soon"). The briefing card is hidden when there's no briefing.
- Window resized across 600dp: hub and chip stay selected (state lives in `App`, as now).
- Google disconnected: the banner stays on Today (it isn't mail content).
- Hebrew text keeps `TextDirection.Content`.
- Dark mode: every hard-coded colour gets replaced by a `MaterialTheme.colorScheme` role.

## 7. Locked decisions changed (needs Tim's approval in the assistant project)
| Decision | Was | Now |
|---|---|---|
| `Overview.md` "Out of scope" line (dark mode) and D58 in `docs/plan/DECISIONS.md` (the only row that mentions dark mode) | No dark mode | Dark mode following the system |
| D60 adaptive layout | Sidebar with all tabs + phone bar of 4 + More sheet | Rail and bar with 5 hubs, chips inside |
| Today screen shows unread mail and next 7 days (Phase 4/11) | Shown | Removed from Today; mail stays in the database for chat and in the Phase 12 Mail tab |
