# Wally redesign canvas (exported copy)

Exported 2026-11-11 from the claude.ai canvas "Wally App Redesign" (https://claude.ai/artifact/RqdTz93wzV9CnRj6kBB4qP), version 1794391387-d159.
This folder is the design source for Phases 15–16. Each `*.dc.html` file is one screen (frame), written as plain HTML with inline styles: read it for layout, spacing, sizes, colours and wording. Open it in a browser to see it (the missing `support.js` and the `<x-dc>` wrapper do not matter for reading).
Each row below lists its frames left to right and its green note. The green note is Tim's decision for that row and is part of the build prompt. Text in square brackets (`[Task title]`) is placeholder data, not copy to ship.
If the canvas and this copy ever disagree, ask Tim; do not guess.

**Chat frames are Phase 17, not Phases 15–16.** `ChatMain`, `ChatCard`, `ChatList`, `ChatMemory` and `LaptopChat` show the chat redesign, which is built in Phase 17 (the chat redo). In Phases 15–16 the existing Chat screen stays as a placeholder: it keeps working as it does now, only recoloured by the new theme and moved to the middle of the bar. Do not build or change anything from these five frames before Phase 17.

## Phone page (frames 390 × 844)

### Screen 1 · Today (phone)
Frames: `Main.dc.html` (Today — Forest green (chosen)), `AddSheet.dc.html` (+ opened: pick what to log)

Green note: Chosen: Forest green. Layout: date + sync on top (pull down to resync), briefing card, tasks with swipe only (right = done, left = delete), exam tiles, + opens a picker for every log type. Text is placeholder.

### Screen 2 · Log hub — Check-ins (phone)
Frames: `LogHistoryLink.dc.html` (Check-ins (chosen)), `CheckinHistoryDots.dc.html` (Check-in history (chosen: dot meters)), `CheckinAdd.dc.html` (+ on Check-ins: new check-in)

Green note: Chosen: chips above the bar, today + yesterday as cards, 'See all check-ins' under them. + adds a check-in only (sheet: scores start at 3, tap to change, optional note, date defaults to today). History: all-green 5-dot meters, month button at the top. Values are placeholders.

### Screen 3 · Log hub — Spending (phone)
Frames: `SpendingWeek.dc.html` (Spending (chosen: week by category)), `SpendingHistory.dc.html` (Spending history), `SpendingAdd.dc.html` (+ on Spending: new spending)

Green note: Chosen: this week split by category, then today's rows, 'See all spending'. + adds spending only (sheet: amount, category tiles, optional note, date defaults to today). Rows: tap = edit, swipe left = delete. Amounts are placeholders.

### Screen 4 · Log hub — Journal (phone)
Frames: `JournalMain.dc.html` (Journal), `JournalHistory.dc.html` (Journal history), `JournalAdd.dc.html` (+ on Journal: write an entry)

Green note: Same pattern. Main: today's and yesterday's entries as cards (time + first 3 lines), 'See all entries', + to write. History: days newest first, month button. Write: full screen, big text area, Save at the bottom. NEW: an unsaved draft is kept if you close the screen (chosen). Entries store only text + the time written. Text is placeholder.

### Screen 5 · Log hub — Meals (phone)
Frames: `MealsMain.dc.html` (Meals), `MealsHistory.dc.html` (Meals history), `MealsAdd.dc.html` (+ on Meals: new meal or snack)

Green note: Same pattern. Main: today's estimated total (kcal + protein/carbs/fat), today's meals and snacks, 'See all meals' (yesterday lives in history, since a day of meals fills the screen). Each row: time, Meal/Snack tag, what you ate, nutrition line. States: estimate done, estimating, failed. Chosen: 'Look up' (asks Codex) shows on failed rows and inside every meal's edit screen, not on every row. Add sheet: Cancel + Save row at the bottom (same as Spending), Meal | Snack, what you ate, date + time pickers; nutrition is estimated after saving. Numbers are placeholders.

### Screen 6 · Log hub — Tasks (phone)
Frames: `TasksMain.dc.html` (Tasks), `TasksHistory.dc.html` (Completed tasks), `TasksAdd.dc.html` (+ on Tasks: new task)

Green note: Same pattern. Main: open tasks grouped by due date (Overdue, Today, This week, Later, No date); swipe right = done, swipe left = delete, tap = edit (same as Today). 'See completed tasks' opens history (done tasks by the day finished, month button). Add sheet: title, due-date quick chips (None by default, Today, Tomorrow, Pick date), Cancel + Save. Chosen: no goal option on Tasks screens; goal tasks are managed from the Goals screen. Titles are placeholders.

### Screen 7 · Log hub — Dreams (phone)
Frames: `DreamsMain.dc.html` (Dreams), `DreamsHistory.dc.html` (Dream history), `DreamsAdd.dc.html` (+ on Dreams: write a dream), `DreamDetail.dc.html` (A dream opened: interpretation)

Green note: Same pattern. Main: recent dreams (date, mood dots, lucid tag, tags, 2 lines, 'Interpreted' mark), 'See all dreams', +. History: month counts (dreams, lucid), dreams by week. Chosen: pattern summaries removed from the app (no Patterns card, no 'Find patterns'). Write: full screen like Journal (text, mood on waking 1–5, lucid circle checkbox, tags, draft kept). Opened dream: full text, details, interpretation with 'Interpret again', Edit button. Text is placeholder. Approved changes: draft kept, new dream defaults to last night, opening a dream shows it to read first (Edit is separate).

### Screen 8 · Chat (phone)
Phase 17 only. In Phases 15–16 the current Chat screen stays as a placeholder.
Frames: `ChatMain.dc.html` (Chat: conversation (Undo + pick chips)), `ChatCard.dc.html` (Chat: yes/no card + offline), `ChatList.dc.html` (Chat: chat list (10 max)), `ChatMemory.dc.html` (Chat: Memory (from the chat list))

Green note: Bottom bar order changed on every screen: Today · Log · Chat · Insights · Browse (Chat in the middle). Chat follows the Phase 15 plan: small adds happen at once with a 30-s Undo; bigger changes show a yes/no card; several matches show pick chips; up to 10 chats on both devices, the Weekly report pinned; starting an 11th summarizes the oldest into Browse > Memory. Composer sits right above the bar. Messages are placeholders. Chosen: offline banner plus a send queue (unsent messages wait with a clock icon and send when back online) — check whether Phase 15 already plans the queue. Bar order change must be applied to the Phase 14 plan. Chosen: Memory opens from the chat list (Chat hub) instead of Browse > Memory.

### Screen 9 · Insights hub (phone)
Frames: `InsightsCharts.dc.html` (Insights · Charts), `InsightsHealth.dc.html` (Insights · Health), `InsightsScreentime.dc.html` (Insights · Screentime), `InsightsGoals.dc.html` (Insights · Goals), `ScreentimeLabel.dc.html` (Screentime: label apps)

Green note: Same shell as Log: chips above the bar (Charts, Health, Screentime, Goals), no + except 'New goal' on Goals. Each chip keeps today's content, redrawn as cards with green charts. Charts: check-in lines, spending by category, per month, tasks per week. Health: last-night tiles, sleep and steps charts, workouts. Screentime: This day / 7 days, unlabelled-apps banner, minutes per label (Productive, Unproductive, Neutral, Not labelled), top apps, Spotify; label screen holds labelling, suggestions and rules. Goals: goal cards with step progress, Dream week card. Numbers are placeholders.

### Screen 10 · Browse hub (phone)
Frames: `BrowseFiles.dc.html` (Browse · Files), `BrowseStudy.dc.html` (Browse · Study), `BrowseMail.dc.html` (Browse · Mail), `BrowseHardware.dc.html` (Browse · Hardware), `BrowseSettings.dc.html` (Browse · Settings), `StudyExams.dc.html` (Study: all exams)

Green note: Same shell: chips above the bar (Files, Study, Mail, Hardware, Settings; the row scrolls). Each keeps today's features, redrawn as cards. Files: search, folders as chips, docs list, + New doc. Study: courses as cards with exam countdown, study set / quiz / summary buttons. Mail: unread, Wally's inbox suggestions with Approve/Reject, drafts. Hardware: describe a problem, past checks. Settings: grouped cards (Sync, Connections, Laptop only). Text is placeholder. All exams (from 'All exams' on Study): upcoming with countdown, past, + Exam; tap = edit, swipe left = delete.

## Laptop page (frames 1280 × 800)

### Laptop · Today
Frames: `LaptopToday.dc.html` (Laptop · Today)

Green note: Laptop = simpler, mouse + keyboard. Left rail (same 5 hubs, same order), content uses the width: two columns. No swipe on a laptop, so task rows show Done / Delete buttons on hover. Dates as MM-dd-yyyy (Phase 14 rule). '+ New' top right opens the same log picker; N is the keyboard shortcut. Same Forest green colours. Text is placeholder.

### Laptop · Log
Frames: `LaptopLog.dc.html` (Laptop · Log (Spending)), `LaptopCheckins.dc.html` (Laptop · Log · Check-ins), `LaptopJournal.dc.html` (Laptop · Log · Journal), `LaptopMeals.dc.html` (Laptop · Log · Meals), `LaptopTasks.dc.html` (Laptop · Log · Tasks), `LaptopDreams.dc.html` (Laptop · Log · Dreams), `LaptopDreamOpen.dc.html` (Laptop · Log · Dreams — a dream opened)

Green note: Log: chips across the top (room for all six). List on the left; the form opens in the right panel instead of a sheet, so you can see the list while adding. Esc cancels, Enter saves. Other chips follow the same split. All Log chips: list or cards on the left, the add form docked on the right (Check-ins 1–5 buttons starting at 3, Meals with typeable MM-dd-yyyy date + time, Tasks with due chips). Journal writes in the right pane; Dreams opens the selected dream on the right. Clicking a dream opens it in the right pane: full text, mood, lucid, tags, Edit/Delete, and the green Interpretation card with Interpret again (or an Interpret button if there's none yet).

### Laptop · Chat
Phase 17 only. In Phases 15–16 the current Chat screen stays as a placeholder.
Frames: `LaptopChat.dc.html` (Laptop · Chat)

Green note: Chat: chat list always visible on the left (10 max, Weekly report pinned, Memory link), conversation on the right. Enter sends, Shift+Enter new line. Same Undo, pick chips and yes/no cards as the phone. A hide-chats icon (top of the list, Ctrl+B) collapses the list so the conversation gets the full width; the same icon at the top left of the conversation brings it back.

### Laptop · Insights
Frames: `LaptopInsights.dc.html` (Laptop · Insights), `LaptopHealth.dc.html` (Laptop · Insights · Health), `LaptopScreentime.dc.html` (Laptop · Insights · Screentime), `LaptopGoals.dc.html` (Laptop · Insights · Goals)

Green note: Insights: chips across the top; charts in a 2-column grid so you see more at once. Screentime keeps All | Laptop | Phone and This day | 7 days. Health adds back the day-by-day list (there's room). Screentime shows Label apps in a side panel (Phone | Laptop). Goals opens the selected goal's steps on the right; '+ New goal' top right.

### Laptop · Browse
Frames: `LaptopBrowse.dc.html` (Laptop · Browse (Files)), `LaptopStudy.dc.html` (Laptop · Browse · Study), `LaptopMail.dc.html` (Laptop · Browse · Mail), `LaptopHardware.dc.html` (Laptop · Browse · Hardware), `LaptopSettings.dc.html` (Laptop · Browse · Settings)

Green note: Browse: chips across the top. Files: folders on the left, docs in the middle, the open doc on the right. Laptop-only settings (start with Windows, tray, XPS Ollama URL) live in Browse > Settings here. Study: course cards + All exams panel. Mail: list + reading pane with Draft reply. Hardware: form + past checks + opened explanation. Settings: 4 cards including 'Laptop only'.
