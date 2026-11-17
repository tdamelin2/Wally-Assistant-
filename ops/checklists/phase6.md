# Phase 6 checklist: AI briefing and chat (PLAN Tasks 6.1-6.4)

File name is phase6.md because it follows the PLAN task numbers (Tasks 6.x). Run it on BOTH apps: the phone app and the installed laptop app (Wally 1.18.0). Do the setup guides first: `ops/ollama-hp.md` and `ops/ollama-xps.md`. Use the Settings tab `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run.

Before you start (once):
- [ ] Both apps are version 1.18.0 and still named Wally (laptop installer `Wally-1.18.0.msi`).
- [ ] In Settings of both apps, the field `XPS Ollama URL` shows `http://xxxxxxxx:11434` without typing it.
- [ ] The XPS is on and Ollama is running (tray icon).

Chat (do it on the phone, then again on the laptop):
- [ ] Open the Chat tab. Ask: `What was my average mood this week?` The answer shows real values from your check-ins (compare with the Check-ins list) and not invented numbers.
- [ ] Under the finished answer there is a small line `prompt tokens: N`. Write N here: ______ . It must be below 16384.
- [ ] Ask a follow-up such as `And the week before?` The answer takes the first question into account.
- [ ] Ask something your data cannot answer, for example `What is my blood type?` The answer says the data does not contain it.
- [ ] While an answer is still being written, the Send button waits (only one question at a time).
- [ ] Leave the Chat tab and come back: the old conversation is gone (it is cleared on purpose).
- [ ] On the phone the round + button is not shown on the Chat tab and does not cover Send; it is still there on the other tabs.
- [ ] Hebrew: ask a question in Hebrew. The question and answer show correctly (right to left).

Offline message (time it with a clock):
- [ ] On the XPS, right-click the Ollama tray icon and choose Quit.
- [ ] In the Chat tab ask anything. Within 5 seconds the chat shows `XPS is offline - turn it on to chat` (start counting when you press Send). Phone: ______ s. Laptop: ______ s.
- [ ] Start Ollama again on the XPS. Ask again: the answer works.

Briefing (the morning after the server has the `ollama` block in its config):
- [ ] Before 7:00, `ssh hp` then `systemctl status assistant --no-pager` says `active (running)`.
- [ ] Do not run anything by hand at 7:00. The server writes the briefing by itself within a minute or two of 7:00 (the HP must be on).
- [ ] After 7:00, press Sync on the laptop. At the top of Today a briefing card shows a short summary (about 120 words or less). Time it appeared: ______ (must be by 7:30).
- [ ] The same card shows on the phone after Sync, by 7:30. Time: ______ .
- [ ] The summary mentions only things you really have (today's events, tasks, yesterday's mood), nothing invented.
- [ ] If you open the app on a day when no briefing exists yet (before 7:00), there is no card and no empty box.
- [ ] Test now (any time): run `curl.exe -X POST -H "Authorization: Bearer <laptop token>" http://xxxxxxxx:8080/v1/admin/briefing`, then Sync. The card shows the new text. Running the same command twice quickly answers `briefing already running` the second time.
- [ ] If the HP was off at 7:00 and is switched on later, the briefing is written within a minute or two of the server starting.
- [ ] Leave the app open past midnight (or change nothing and check the next day): the card shows the new day's briefing, not yesterday's.
