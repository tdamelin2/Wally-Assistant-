# Checklist W13: Wally Calendar, Dream week coaching and event creation - Wally 1.12.0

Run it on BOTH apps: the phone app and the installed laptop app (`Wally-1.12.0.msi`). Do the setup first: `ops/wallycal.md` (connect the server to Wally Calendar) and, if you signed in to Google before, the "Moving an existing setup to Gmail-only" part of `ops/google-setup.md`. Use the Settings `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run. The coaching part needs the XPS on with Ollama running (see `ops/ollama-xps.md`).

Release (do this first):
- [ ] The server was deployed BEFORE the apps were installed (`powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1`).
- [ ] On the laptop: Wally was quit from the tray icon (right-click > Quit) BEFORE installing the new MSI.
- [ ] Both apps show version 1.12.0.
- [ ] Entries saved before the upgrade (check-ins, journal, spending, meals, docs, dreams, goals, tasks, study files, exams) are all still listed on the laptop and on the phone.

Events from Wally Calendar (SPEC section 2 #31):
- [ ] Make a new event in Wally Calendar for tomorrow. Wally's Today does not list events any more (Wally Calendar is the place to look at them). Within 15 minutes, `ssh -t hp sudo journalctl -u assistant -n 50 --no-pager` shows a `wallycal poll ok` line, and after `Sync now` Chat answers "what's on my calendar tomorrow?" with that event (day, time, title right) on both apps.
- [ ] No event appears twice. In particular there are no leftover copies of Google events that Wally used to read directly.
- [ ] A Google event appears in Wally only after you press Import in Wally Calendar and wait up to 15 minutes plus `Sync now`. Make a new event in Google Calendar and do NOT press Import: it does not show in Wally.
- [ ] Change the title of an event in Wally Calendar and delete another one. After the next 15 minutes plus `Sync now` Wally shows the new title and the deleted event is gone.
- [ ] Make Wally task still works: in Wally Calendar press "Make Wally task" on an event. The task shows in Open tasks after Sync. (`/etc/assistant/config.json` still has `tokens.calendar`.)
- [ ] Turn Wally Calendar off (stop its server, or leave Tailscale on the HP but stop its service). After the next 15 minutes plus `Sync now`, the Settings line next to the Spotify status reads "Wally Calendar: not reachable - Wally keeps the saved events" and Chat still answers from the saved events. Turn it on again: after the next check plus `Sync now` the line reads "Wally Calendar: connected". (Before the first connection it reads "Wally Calendar: not set up".) Today shows no calendar line at all.
- [ ] Gmail still works: Browse > Mail shows `Unread mail`, and there is no red `Google disconnected` banner.

Dream week coaching (SPEC section 2 #33):
- [ ] Before you ever sent a Dream week from Wally Calendar: Goals shows "Dream week not sent from Wally Calendar yet". Pressing "Coach me on my Dream week" ends with the message "Send your Dream week from Wally Calendar first" and no document is made.
- [ ] In Wally Calendar fill your Dream week and press "Send to Wally". Open Goals again: it shows "Last sent from Wally Calendar: <date and time>".
- [ ] Goals has no typing box any more, only the button "Coach me on my Dream week".
- [ ] Press the button with the XPS on: the job queues (it also queues while the laptop is offline), then finishes. A document "Dream week coaching <date>" appears in Docs, in the Plans folder, on both apps after Sync.
- [ ] The document lists differences between the Dream week and your next 7 days, then 3 to 10 concrete steps (lines starting with "-").
- [ ] Press the button twice quickly: only one job runs.
- [ ] With Wally Calendar off, Goals shows "Wally Calendar not reachable" and pressing the button ends in a clear error, nothing hangs.
- [ ] Edit the finished document yourself, then run the coaching again: your edited document is not overwritten (a new document appears).

Creating an event from Chat (SPEC section 2 #32, Wally 1.12.0; needs the HP server updated and Wally Calendar connected):
- [ ] In Chat on the phone ask: "put a dentist appointment in my calendar tomorrow at 15:00 for one hour". An approval card appears in the chat (and in Files > Approvals). Nothing is in Wally Calendar yet. Also try the same without "for one hour": Wally asks how long the event lasts instead of guessing, and the card comes after you answer.
- [ ] Tap Approve on the phone. The card turns to Done. Within 15 minutes plus `Sync now` the event is in Wally Calendar on the right day and time, and Chat lists it.
- [ ] Sync the laptop. The same card shows as Done there. If you also tap Approve on the laptop for a card that is still Pending there (do this with a second new card: approve it on the phone and the laptop at nearly the same time), Wally Calendar still has exactly ONE such event, never two.
- [ ] Ask Chat for a one-day all-day event ("all day tomorrow: exam day"). Approve it. In Wally Calendar it shows as one day only (not two days).
- [ ] Stop Wally Calendar (or turn off the connection) and approve another event card. The card shows "Failed" with "calendar-unreachable - check Wally Calendar before asking again". Look in Wally Calendar first: if the event is there, do not ask again; if it is not, ask Chat again (a new card).
- [ ] With the laptop and phone offline from the HP server, tapping Approve on an event card shows "Server not reachable - try again" and the card stays Pending.

Two devices and errors:
- [ ] Chat's calendar answers and the coaching document are the same on the phone and the laptop after Sync on both.
- [ ] Stop the HP server: Today still opens and shows the saved tasks and exams (and Browse > Mail shows the saved mail), Sync shows a short error and nothing hangs.
