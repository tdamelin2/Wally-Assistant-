# Checklist Phase 17: chat on the home server, reminders, Codex on the HP - Wally 1.15.0

What this is for: you check the new chat, the phone reminders and the research on the HP with your own eyes. Do the setup first: `ops/deploy-phase17.md` (it includes the HP model test, Codex login, the config and the restore test). Run the chat parts on BOTH apps: the phone app and the installed laptop app (`Wally-1.15.0.msi`). Use the Settings `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run, and "pending" next to a line that needs a day or a Sunday to happen.

How slow the HP is: when the laptop is off, chat runs on the HP's own small model in "slow simple mode". Each reply takes 1 to 2 minutes, and the typing line says so after 10 seconds. That is expected, not a bug. With the laptop awake, replies come from the laptop's model and are fast.

Release (do this first):
- [ ] The server was deployed BEFORE the apps were installed.
- [ ] On the laptop: Wally was quit from the tray icon (right-click, then Quit) BEFORE installing the new MSI.
- [ ] Both apps show version 1.15.0.
- [ ] Entries saved before the upgrade (check-ins, journal, spending, meals, docs, dreams, goals, tasks, study files, exams) are all still listed on the laptop and on the phone.
- [ ] The backup restore test printed 34 table rows and `PASS` (`ops/backup-setup.md` step 7).

HP model (success criterion AC-1, from `ops/hp-model-test.md`):
- [ ] `reports/phase-17-model.md` is filled: the chosen model scored at least 10 out of 14 on the lite test, the warm timed round was 150 seconds or less, free memory stayed at 500 MB or more with the server, Ollama and Codex all running.
- [ ] `ssh hp ollama list` shows only the chosen model.
- [ ] The on-demand briefing printed a real text (not "Briefing unavailable today").

SC-5: answer with the laptop off (laptop asleep or Ollama stopped on it):
- [ ] On the phone, open Chat and ask "what's on today?". The typing line says "The laptop is off, so this can take 1-2 minutes". A real answer with today's tasks and events arrives within 3 minutes (slow simple mode; the original 30-second goal only holds when the laptop is awake).
- [ ] With the laptop awake and Ollama running on it, the same question is answered within about 30 seconds.

HP slow-mode chat with the laptop off:
- [ ] "Add task buy milk" saves the task at once and shows an Undo button for a short time.
- [ ] "Add meal two eggs and toast" and "I spent 30 on lunch" each save an entry (check Log).
- [ ] Ask for something bigger, for example "make a study guide on my first course". Wally answers that it needs the laptop to be on and nothing is saved.
- [ ] Mention a dream ("I had a weird dream last night"): the chat can offer to note it. Then in a new chat ask "what did I do today?": the answer never repeats dream text.

SC-6 (no IDs, chips):
- [ ] With the laptop awake: "Make a study guide on <a course name>". Wally never asks for an ID. If two courses match, it shows buttons (chips) with the course names; tapping one continues.
- [ ] No chat reply anywhere shows a long code or an ID.

SC-7 (add at once, change by card):
- [ ] "Add task buy milk" creates the task immediately with an Undo button. Press Undo within the countdown: the task is gone after Sync. Do it again and wait: the Undo button disappears and the task stays.
- [ ] "Move my exam to Friday" does NOT change anything at once: a card appears with the change and Yes/No buttons (also under Files > Approvals). Yes applies it; No leaves the exam as it was. Pressing Yes on the phone and laptop at nearly the same time applies it once, the other shows "already decided".
- [ ] Saying a check-in for a day that already has one shows a card (update), it never makes a second check-in.

SC-8 (10 chats and memory):
- [ ] The chat list shows "N of 10". Make chats until you start the 11th: the oldest one that is not pinned disappears from the list and a message says it was archived.
- [ ] Within 2 minutes, Chat > Memory (opened from the chat list) shows a note for it. While it is being written it says "Summarizing...".
- [ ] A new chat knows what the archived one was about (ask "what did we say about <topic>?").
- [ ] The "Weekly report" chat is pinned and cannot be deleted. Swiping another chat away asks for confirmation and says "Wally will remember a summary".
- [ ] Both apps show the same chats and the same messages after Sync (chats now live on the server).

Offline sending:
- [ ] Turn Wi-Fi off on the phone, type a message and send it. It appears greyed with a clock, and an offline banner shows. Turn Wi-Fi on: it is sent by itself and answered. It is sent only once.

Research on the HP (SC-10):
- [ ] Laptop off. From the phone ask a research question or press Look up on a failed meal estimate. The research row or the meal estimate appears within a few minutes.
- [ ] `ssh -t hp sudo journalctl -u assistant -n 80 --no-pager` shows a `codex exec` run. No Codex window or process opens on the laptop.
- [ ] A research request that needs your approval waits for your Yes, then runs once.

Reminders (SC-9) - phone, needs one day (mark pending until the day has passed):
- [ ] On the first start the phone asked once whether Wally may show notifications; you answered Allow.
- [ ] Settings > Reminders shows the check-in time (default 21:00), quiet hours 23:00 to 07:00 and five switches (check-in, morning list, heads-up, ideas, weekly report). Change the check-in time to 5 minutes from now, press Save: it says saved. Entering a bad time like 25:00 shows a message and keeps the old value.
- [ ] Check-in missing: the reminder arrives within 15 minutes of the time (the phone may delay it a little when idle). Tapping it opens the check-in form.
- [ ] Check-in already done: no reminder arrives.
- [ ] Nothing arrives between 23:00 and 07:00.
- [ ] No more than 6 reminders in one day.
- [ ] 07:30: a list of what is due arrives when a task is due that day (a task with only a date counts). Tapping it opens Today.
- [ ] 30 minutes before a timed exam or calendar event: a heads-up arrives. Tapping it opens the exam (Study > Exams) or Today.
- [ ] Ideas at 12:00 and 17:00: tapping one opens a new chat that starts with the idea. Ignoring it makes no chat.
- [ ] Sunday 19:00 (pending until a Sunday): the weekly report appears in the pinned "Weekly report" chat and a reminder opens it. It does not mention dreams.
- [ ] Turn one switch off: that kind stops arriving.
- [ ] Switch the phone to Do not disturb or restart it: reminders still arrive when the app was opened once after the restart.

Dreams stay private (SC-11):
- [ ] The weekly report, the ideas, the memory notes and any research text never contain dream text.

SC-12: five real chats (Tim's check):
- [ ] Have five real chats about your day. Replies are 1 to 4 sentences unless you ask for more, mention a real value from your data when it fits (a real task name, amount or time), and never show an ID. Write what you saw in `reports/phase-17.md`.

Screens match the design pictures (AC-16; pictures are in `docs/plan/wally-redesign-v2/canvas/`):
- [ ] Phone chat: matches `ChatMain` (messages, typing line, chips, composer above the bar).
- [ ] Phone chat card: matches `ChatCard` (Yes/No buttons, Undo countdown).
- [ ] Phone chat list: matches `ChatList` (drawer, "N of 10", pinned report, swipe to delete).
- [ ] Phone Memory: matches `ChatMemory`.
- [ ] Laptop chat: matches `LaptopChat` (chat list column on the left, Ctrl+B hides it, Enter sends, Shift+Enter makes a new line).
- [ ] Phone Settings Reminders card matches `BrowseSettings`; laptop Settings 4th card matches `LaptopSettings`.
- [ ] Both light and dark mode look right (phone: light only; laptop follows the system).

Laptop no longer runs research:
- [ ] With the laptop awake, a Look up or research request is still done by the HP (the journalctl line above), not by the laptop. The laptop's tray menu shows no research activity.
