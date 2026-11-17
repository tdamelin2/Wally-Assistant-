# Phase 1 checklist: desktop app screens (PLAN Task 1.5)

Run the dev app with `.\gradlew :desktopApp:run`. It uses its own test database (`%LOCALAPPDATA%\Assistant\dev.db`), so the installed app is not touched. Use only synthetic (made-up) entries. Delete `dev.db` before the first pass so Settings start empty.

Screen labels used below: bottom tabs `Today`, `Log`, `Charts`, `Health`, `Chat`, `Settings`; Today buttons `Check-in`, `Spending`, `Journal`, `Task`; form buttons `Save` and `Cancel`; Log tabs `Check-ins`, `Spending`, `Journal`, `Tasks`; row buttons `Edit` and `Delete`; Settings fields `Server URL`, `Device token`, `XPS Ollama URL`, buttons `Test connection` and `Sync now`.

- [ ] Today shows the four quick-add buttons (`Check-in`, `Spending`, `Journal`, `Task`) and the open tasks list. A task with a due date is listed before one without.
- [ ] With empty Settings the bar at the top shows "Not set up — open Settings".
- [ ] Save one check-in (move the sliders, optional note): it appears under Log > `Check-ins`. Saving a second check-in the same day edits the same entry.
- [ ] Save one spending entry: amount `12.50`, a category, a note. Log > `Spending` shows `12.50`. Amount `0` or `abc` disables `Save` and shows "Enter an amount above 0".
- [ ] Save one journal entry: it appears under Log > `Journal`. An empty text disables `Save`.
- [ ] Save one task (title, optional due date): it appears under Today and Log > `Tasks`. An empty title disables `Save`.
- [ ] Tick a task done: it moves below the open tasks in Log > `Tasks` and leaves the Today list. Untick it: it returns to the open tasks.
- [ ] Edit each entry type (`Edit` or tap the row), change a value, `Save`: the list shows the new value.
- [ ] Delete each entry type with `Delete`: a confirm dialog appears; `Cancel` keeps the entry, `Delete` removes it from the list.
- [ ] In Settings set `Server URL` to a wrong host (for example `http://127.0.0.1:9`) and any `Device token`, then save an entry: the bar shows "Offline — N waiting" (N counts the unsent entries) and the entry has a grey dot in the Log list.
- [ ] `Test connection` with the wrong host shows "Server unreachable".
- [ ] Journal text `היום היה טוב` displays right-to-left (starts at the right edge) in the list and in the form.
