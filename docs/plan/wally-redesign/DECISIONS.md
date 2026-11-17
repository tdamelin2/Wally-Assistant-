# Wally UI redesign — decision log

| # | Decision | Chosen | Why |
|---|---|---|---|
| R1 | When to build | One new phase after Phase 13 | Tim's choice: finish all features, then redesign once |
| R2 | "No repeated words" scope | Per visible screen; common buttons may repeat across screens | Tim chose recommended; users expect standard buttons |
| R3 | Unread mail | Removed from the UI (Today); kept in DB for chat | Tim's note |
| R4 | Mail fields in AI context | Subject + sender | Useful answers, short prompts for the local model |
| R5 | Navigation | 5 hubs: Today, Log, Insights, Chat, Browse | Material 3 bar rule is 3–5 items (RESEARCH.md); Todoist/Apple Health use the same hub + Browse pattern |
| R6 | Calendar on Today | No events at all | Tim: calendar lives in WallyCalendar after Phase 11 |
| R7 | Visual style | Material 3 refresh + dark mode following the system | Tim's choice; no settings toggle (simplest) |
| R8 | Colours | Seed `#F5B301` (amber from the Wally icon) | Ties the app to its icon |
| R9 | Laptop layout | Rail + one centred column | Same screens on both devices; no extra library |
| R10 | Inside hubs | Chip row at top, last pick remembered in memory | One tap to switch; no title that repeats the hub name |
| R11 | + button | Per hub: Today → task, Log → open chip's type | Always one obvious action, no menu |
| R12 | Phase 12 Mail + Hardware tabs | Kept; both live in Browse | Tim's choice |
| R13 | Shell implementation | Reuse own `Shell.kt`, no NavigationSuiteScaffold | No new dependency; its CMP 1.12.2 version is unconfirmed |
| R14 | Phone top bar | Removed; sync status in rail / Settings chip | Removes the hub-name repeat; error bar still shows problems |
| R15 | Verification | `LabelUniquenessTest` + Tim's checklist in both themes | Catches repeats automatically; looks need human eyes |
| R16 | Data / settings | No new tables or settings; chip memory not persisted | Nothing needs it yet |
| R17 | Log's existing Tasks list | Kept as a Log chip; Log's inner tabs become NavTabs; Dreams' own create button goes | Found in review: Log screen already has 5 inner tabs incl. Tasks history; reuse, don't lose it |
