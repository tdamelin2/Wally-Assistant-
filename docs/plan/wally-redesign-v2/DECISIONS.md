# Decision log — Wally redesign v2 (Phases 15–16)

Design source: the Design canvas "Wally App Redesign", https://claude.ai/artifact/RqdTz93wzV9CnRj6kBB4qP (pages **Phone** and **Laptop**; each row's green note lists the chosen options). Decided with Tim on 2026-11-11.

| # | Decision | Chosen | Alternatives considered | Why | Status |
|---|----------|--------|-------------------------|-----|--------|
| U1 | Where the redesign goes | New Phase 15 (phone) and Phase 16 (laptop) after Phase 14 | Reopen Phase 14 as a Tim change; merge into the chat phase | Phase 14 is built and in Verify; its plumbing (Undo, sync, pickers) is reused; a rewrite is too big for a Verify fix | Locked |
| U2 | Phase 14 checks | Tim checks only behaviour (swipe + Undo, auto-sync open/closed, pickers + MM-dd-yyyy, nothing broken); skips AC-2 (dark mode), AC-5 (600dp resize) and judging the amber look | Full checklist | Those parts are replaced in Phase 15 | Locked |
| U3 | Phase split | Phase 15 = colours, device switch, every phone screen; Phase 16 = every laptop screen | One phase | Coder turn limits; Tim checks the phone before the laptop starts | Locked |
| U4 | Renumbering | Chat redo (wally-assistant-v2 Tasks 15.1–15.9) becomes Phase 17 (Tasks 17.1–17.9); Wally Voice becomes Phases 18–19 | Put redesign after chat | Tim chose the redesign first | Locked |
| U5 | Palette | Forest green, light mode only (tokens in SPEC §8) | Dark set picked in the plan; Material Theme Builder export | Tim's choice; dark mode can return later | Changed (was Phase 14 "dark mode follows the system", D58 as changed by R-decisions) |
| U6 | Layout switch | By device: Android app = phone layout, Windows app = laptop layout; the 600dp width switch is removed | Keep width switch | Tim: "switch based on the device, not screen ratio" | Changed (was D60/R: bar below 600dp, rail at 600dp+) |
| U7 | Hub order | Today · Log · Chat · Insights · Browse | Today · Log · Insights · Chat · Browse | Chat in the thumb-centre slot | Changed (was redesign R order) |
| U8 | Phone chip row | Above the bottom bar | Under the title | Thumb reach | Locked |
| U9 | + button | Today: opens a picker of all log types. Each Log chip: adds only that type. Goals: new goal. Files: new doc. Study: file or exam. Mail: new email. Elsewhere: none | Today adds a task (Phase 14) | Canvas | Changed (was addTargetFor Today → Task) |
| U10 | History | Main Log screens show recent items; a "See all …" button opens a history screen with a month picker; completed tasks show all months (not only 7 days) | Long single list | Canvas | Locked |
| U11 | Drafts | Journal and Dreams keep an unsaved draft on this device only (local `kv` table, never synced) until saved or cleared | Synced draft table | Rare cross-device case; no sync conflicts | Locked |
| U12 | Dream patterns | Remove the "Find patterns" UI and the `DREAM_PATTERNS` job; keep the `dream_pattern` table, its rows and its sync untouched. Tim asks Chat for patterns instead | Drop table with a migration; hide only | Tim: "If I want patterns to be found I'll ask the chat" | Changed (was Phase 8 dream patterns) |
| U13 | Dream date | "Last night" is a label only; the dream is saved under today's date (unchanged behaviour, DreamForm.kt:42) | Save under yesterday | Lines up with check-ins and history | Locked |
| U14 | Dream screen | Tapping a dream opens it read-first (text, mood, lucid, tags, interpretation, Interpret / Interpret again); Edit is a separate button | Tap opens the editor (today) | Canvas; removes "save first, then interpret" | Changed (was DreamScreen tap → editor) |
| U15 | Tasks and goals | No goal picker on task screens; tasks keep `goalId`; goal tasks are made from the Goals screen | Set as goal checkbox | Tim: remove goal completely | Changed (was Phase 8 TaskForm goal picker) |
| U16 | Meals | Today's estimated total card; "Look up" only on failed rows and in each meal's edit screen. "Failed" = latest MEAL_LOOKUP job FAILED, or no calories 2 h after saving (the server's estimator gives up silently after 3 tries and stores no flag) | Look up on every row (today); a new server failure flag | Cleaner list; no schema change | Changed (was LogListsScreen Look up on every meal) |
| U17 | Screentime | Main screen: All · Laptop · Phone (All sums `usage_session` across devices) and This day · 7 days. Label apps screen: Phone · Laptop | Phone/Laptop only (today) | Canvas | Locked |
| U18 | Health | Phone: tiles + sleep + steps + workouts (no day list). Laptop: same plus the day list | Day list on both | Phone space | Locked |
| U19 | Laptop interaction | Rail; list + detail; side forms instead of sheets; hover Done/Edit/Delete; shortcuts N (new), Esc (cancel), Enter (save), Ctrl+Enter (save Journal/Dream), Ctrl+F (search Files); narrow windows (< 900dp) stack the side panel below | Phone-style sheets on the laptop | Canvas | Locked |
| U20 | Chat screens | Built in Phase 17 from the canvas: Memory opens from the chat list inside Chat (not Browse › Memory); offline send queue; laptop chat list always visible with a hide toggle (Ctrl+B); Enter sends, Shift+Enter new line | Build chat UI in Phase 15 | Chat is rewritten in Phase 17 anyway | Changed (was wally-assistant-v2 SC-8/§ "Browse > Memory") |
| U21 | Reminders settings | Shown as a "Reminders" group in Browse › Settings; built in Phase 17 with the reminders feature | Build now | Reminders don't exist until Phase 17 | Deferred → Phase 17 |
| U22 | New libraries | None | — | Hover, key events, pickers already in Compose Multiplatform 1.12.2 | Locked |
| U23 | Verification | Pure-function tests (no Compose UI tests) + Tim's checklists on phone and laptop | Screenshot tests | Project lesson from Phase 14 | Locked |
