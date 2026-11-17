# Checklist W10: Screentime and Spotify - Wally 1.8.0

Run it on BOTH apps: the phone app and the installed laptop app (`Wally-1.8.0.msi`). Do the Spotify setup first: `ops/spotify.md`. For the laptop recording notes see `ops/screentime.md`. Use the Settings `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run. The "Suggest labels" part needs the XPS on with Ollama running (see `ops/ollama-xps.md`).

Release (do this first):
- [ ] The server was deployed BEFORE the apps were installed (`powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1`).
- [ ] On the laptop: Wally was quit from the tray icon (right-click > Quit) BEFORE installing the new MSI.
- [ ] Both apps show version 1.8.0.
- [ ] Entries saved before the upgrade (check-ins, journal, spending, meals, docs, dreams, goals, tasks, study files, exams) are all still listed on the laptop and on the phone.
- [ ] Backup and restore test: the helper on the HP was refreshed (see `ops/backup-setup.md`, "Update the restore helper after a schema change"), a fresh backup was made and step 7 of that guide prints all twenty-five rows (`usage_session`, `app_rule`, `listen` at the end) and `PASS`.

Phone: usage access (SPEC section 2 #14):
- [ ] Open the Screentime tab before allowing anything: a "Grant usage access" button shows and no app list is invented.
- [ ] Press the button: Android opens the usage access screen. Allow Wally. Come back: the button is gone without restarting the app.
- [ ] Use a few apps for a minute each (for example a browser and a notes app), lock the phone, wait a minute. Open Wally > Screentime > Phone: those apps show with minutes; the lock screen time is not counted as app time.
- [ ] Revoke the permission in Android settings and come back to Wally: the button is back and nothing crashes. Grant it again: the button disappears and the data collected while it was off is not invented.
- [ ] Settings > Screentime shows the usage access status and the same button.
- [ ] Leave the phone offline (airplane mode) with the app closed for an hour of normal use, turn it off, open Wally and Sync: that hour's apps are listed, each app once (no doubles when you Sync twice).

Laptop: tracker and tray (SPEC section 2 #15):
- [ ] Use a few programs (browser, editor, Wally itself) for a minute each. Screentime > Laptop shows them with minutes.
- [ ] Close the Wally window with the X: Wally stays in the taskbar corner (tray). Keep working in other programs for a few minutes, then right-click the tray icon > Open: the minutes of those programs were recorded while the window was closed.
- [ ] Leave the laptop untouched for 3 minutes (no mouse or keys): the idle time is not counted as program time. Move the mouse: recording continues.
- [ ] Settings > "Keep running in tray" off: closing the window now quits Wally (check the tray icon is gone). Turn it back on.
- [ ] Start Wally a second time (Start menu): no second copy starts; the first window comes to the front.
- [ ] Right-click tray icon > Quit: Wally closes. Start it again: the last minutes before Quit are in the list (nothing lost).
- [ ] Settings > "Start with Windows" on, sign out and in (or restart): Wally starts by itself, hidden in the tray (no window). Turn the switch off: after the next login it does not start.
- [ ] Hebrew window titles and track names show right to left and are readable.

Labels and totals (SPEC section 2 #16):
- [ ] Apps you have not sorted show in the Unsorted list. Tap one: pick Productive. A rule appears in the rules list and the totals change straight away (no wait, no Sync).
- [ ] Sync on both devices: the rule is on the other device too, and its totals changed there as well.
- [ ] Change that rule to Unproductive, then delete it: totals follow each time, the app goes back to Unsorted after delete.
- [ ] Today / 7 days toggle and the Phone / Laptop chips change the numbers as expected; the top 10 apps list is sorted by time.
- [ ] A rule by window title (for example the word `youtube`) beats the rule for the browser program.

Suggest labels (SPEC section 2 #17), laptop with the XPS on:
- [ ] Press "Suggest labels": the status line shows it queued, then done. Suggested rows show app, device and label with an Accept button.
- [ ] Nothing changed until you press Accept: the Unsorted list is the same. Accept one: the rule appears and the totals change. Press Accept again on the same row: nothing new happens.
- [ ] Turn the XPS off or stop Ollama, press "Suggest labels": a short error shows and the app does not hang.

Listening (Spotify):
- [ ] Settings shows Spotify connected after you did `ops/spotify.md` step 3. Before that it said "Spotify not connected".
- [ ] Play 3 songs (about a minute each). Within 30 minutes Sync: the Listening section shows them with the artist, newest first. Sync again after another 30 minutes: no song is listed twice.
- [ ] Hebrew song names show right to left.
- [ ] Stop the HP server, wait for the next 30-minute time, start it again: the songs played meanwhile (within Spotify's last 50) appear, none twice.
- [ ] Remember to run `spotify-auth` again in about 5 to 6 months (date at the top of `ops/spotify.md`). When it lapses Settings says "Spotify not connected" and Listening shows that text.

Two devices and errors:
- [ ] Phone sessions show on the laptop and laptop sessions on the phone, after Sync on both (device chips keep them apart).
- [ ] Take the laptop offline for ten minutes while working, then reconnect and Sync: the sessions arrive once.
- [ ] Stop the HP server: Screentime keeps recording on both devices; Sync shows a short error and nothing hangs.
- [ ] After a restore from backup (only if you did one): the Listening list is back, and `spotify-auth` has to be run again.
