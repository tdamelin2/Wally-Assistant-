# Tim's checklists — what you do yourself in each phase

The agents write and test the code. This file is only **your** part: what to have ready
before a phase, the hands-on jobs during it, and the checks only you can do (marked
**Manual (Tim)** in `phases.md`). Tick each box as you go.

Names used below: server `server.example-tailnet.ts.net` (in `ssh`/`scp` just `hp`),
laptop `laptop.example-tailnet.ts.net`, phone on home Wi-Fi for `adb`.

## Every phase
- [ ] Before: the previous phase is finished and committed (the manager says so).
- [ ] Before: `ssh hp "echo ok"` prints `ok`, and `adb devices` shows the phone as `device`
      (if not: `adb connect` — see `ops/phone-adb.md`).
- [ ] Start: `.\scripts\start-phase.ps1 -Phase N` from `C:\Users\user\Projects\assistant`.
- [ ] Approval: read the manager's summary, then reply `go` (or say what to change).
- [ ] When the manager asks to approve one of the four allowed HP commands, approve it.
      Any **other** SSH command: read it first and only approve if it makes sense.
- [ ] End: answer each "Manual (Tim)" check below honestly — say exactly what you saw.
- [ ] After: the manager refreshes the code map (`graphify update .`). If it didn't, run
      `graphify update .` yourself in PowerShell in the project folder (about a minute, free).

---

## Phase 1: Project skeleton and shared schema
Before
- [ ] SETUP-STEPS.md steps 1–11 done (including step 10, graphify, and step 11, git).
- [ ] `$env:JAVA_HOME` shows the Temurin 21 folder (new PowerShell window).

During
- [ ] Nothing hands-on. Approve the plan.

After
- [ ] `.\gradlew build` ends with `BUILD SUCCESSFUL` (the manager shows you this).

## Phase 2: Logging, sync, server and backups (the big one)
Before
- [ ] **Server memory fixed**: Hyper-V VM set to 4 GB with Dynamic Memory off, and
      `ssh hp free -h` shows about 3.8 GB total.

During (when the manager reaches these tasks)
- [ ] Make two device passwords: run `openssl rand -hex 32` twice on the HP, save them in
      your password manager as "assistant phone" and "assistant laptop".
- [ ] Create `/etc/assistant/config.json` on the HP with those tokens (PLAN Task 1.3
      step 3) and install the `assistant.service` unit (same step; asks your sudo password).
- [ ] rclone for backups (PLAN Task 1.7 steps 2–3): Google Cloud project "assistant",
      Drive API, Desktop OAuth client, publish "In production"; `rclone config` on the HP
      and `rclone authorize` on the XPS. Save **both crypt passwords** in your password
      manager — without them the backups can never be opened.
- [ ] Install the desktop app from the `.msi` the manager builds.
- [ ] In both apps, Settings: server `http://server.example-tailnet.ts.net:8080` and that
      device's token.

Manual checks
- [ ] AC-7: both apps show "Connected" in Settings.
- [ ] AC-8: every line of `ops/checklists/phase1.md` and `ops/checklists/phase1-e2e.md`
      passes — entries move phone↔laptop, airplane-mode save syncs once with no duplicate,
      the later edit wins, a delete disappears on the other device, Hebrew shows right-to-left.
- [ ] AC-9: a backup downloaded from Google Drive passes the restore test, and a second
      backup more than 1 hour later shows no errors.

After
- [ ] Only now start entering **real** data (the restore test has passed).
- [ ] Note today's date: the 14-night backup check in Phase 4 counts from here.

## Phase 3: Diet log and charts
Before
- [ ] Phase 2 is committed, and the plan update (diet log, Wally look) is copied into the
      project and committed (the launcher refuses unsaved changes).

During
- [ ] When the manager deploys the new server, reinstall **both** apps in the same
      sitting, and don't log a meal until both are updated.

Manual checks
- [ ] AC-5: every line of `ops/checklists/meals.md` — a meal on the phone shows on the
      laptop and a snack the other way; edit and delete work; each shows "nutrition
      pending"; a Hebrew meal reads right-to-left; **all your older entries are still
      there** after the update.
- [ ] AC-6: stop the server (`ssh -t hp "sudo systemctl stop assistant"`), open Charts on
      both apps: four charts, values match what you count by hand. Then start it again
      (`ssh -t hp "sudo systemctl start assistant"`).
- [ ] Both apps were reinstalled with the new version (the manager does the build).

## Phase 4: Google (read-only)
During
- [ ] Google Cloud console (PLAN Task 3.1): enable Calendar API and Gmail API, add the two
      read-only scopes, keep "In production", create a **Desktop app** OAuth client, put its
      id and secret in `/etc/assistant/config.json`, restart the server.
- [ ] Sign in (PLAN Task 3.2 verify 1): `ssh -L 8765:127.0.0.1:8765 -t hp`, run
      `google-auth`, open the printed link in the XPS browser, accept the "unverified app"
      warning. Write the date in `ops/google-setup.md` as "last google-auth".
- [ ] Run `google-calendars`, copy the calendar ids you want into the config, restart.

Manual checks
- [ ] AC-3: every line of `ops/checklists/phase3.md` — a new calendar event appears within
      15 min + Sync; an email you mark read disappears; **nothing** was sent or changed in
      Google; revoking access shows "Google disconnected"; signing in again clears it
      (then update "last google-auth").
- [ ] AC-4: **8 days** after "last google-auth", `google-calendars` still works.
      If it doesn't: Google expires the sign-in weekly (D38) — re-run `google-auth` each week.
- [ ] AC-5: 14 backups, one per date, both on the HP and on Google Drive. If 14 nights
      haven't passed since Phase 2, do this before the Phase 5 approval instead.

You don't have to wait the 8 days before starting Phase 5; just come back and tick AC-4.

## Phase 5: Garmin health data
Before
- [ ] Wear the watch for a few days first, and let Garmin Connect sync to the phone.

During
- [ ] When the debug button appears (PLAN Task 4.1), tap it on the phone and allow the
      Health Connect permissions. Keep the phone connected (`adb devices`).

Manual checks
- [ ] AC-3: every line of `ops/checklists/phase4.md` — last night's sleep and today's steps
      match the Garmin Connect app (within 1%); the laptop shows the same; a Samsung
      Health–only workout does **not** appear; a day without the watch shows "no data";
      denying permission shows the "Allow Health Connect" button.

## Phase 6: Adaptive layout
Before
- [ ] Open `docs/plan/ui-layout-guide.html` in a browser: it shows where everything goes
      on the laptop and the phone.

During
- [ ] If the manager asks to add an icon library, say yes or no (a new dependency needs
      your approval).

Manual checks
- [ ] AC-3: every line of `ops/checklists/phase5.md` — on the laptop the sidebar collapses
      to icons and stays that way after a restart; narrowing the window below 600 dp
      switches to the phone layout; on the phone the bottom bar has
      five tabs and Settings is behind the gear; the + button opens all five forms; tap a
      row to edit, ⋮ to delete; nothing cut off; Hebrew still right-to-left.
- [ ] AC-4: run `ops/checklists/phase1-e2e.md` once more.

## Phase 7: AI briefing and chat
During
- [ ] On the HP: install Ollama and pull `qwen3:1.7b` (PLAN Task 6.1), add the `ollama`
      block to the config, restart.
- [ ] On the XPS: install Ollama 0.40.14 from the GitHub release, pull `qwen3.5:9b`, set
      `OLLAMA_HOST` to the laptop's Tailscale IP `xxxxxxxx:11434`, and run the firewall
      command in an **admin** PowerShell (PLAN Task 6.3).
- [ ] In both apps' Settings: XPS Ollama URL `http://laptop.example-tailnet.ts.net:11434`.

Manual checks
- [ ] AC-5: on the phone (Tailscale on), open `http://laptop.example-tailnet.ts.net:11434/` →
      "Ollama is running". From a device **not** on Tailscale, the laptop's Wi-Fi address
      (`http://xxxxxxxx:11434/`) does **not** answer.
- [ ] AC-6: every line of `ops/checklists/phase6.md` — ask "What was my average mood this
      week?" on both apps and the answer uses your real numbers; quit Ollama on the XPS →
      "XPS is offline" within 5 seconds; the next morning the briefing card is there by 07:30.
- [ ] AC-7: run `ops/checklists/phase1-e2e.md` once more — everything from Phase 2 still works.
- [ ] AC-9: every line of `ops/checklists/nutrition.md` — older meals show "≈ … kcal"
      once the backlog is done (about 20 meals an hour); a new meal gets numbers within 30 minutes after Sync; editing its
      text shows "nutrition pending" and then new numbers; chat answers "What did I eat
      yesterday?". The numbers are rough guesses from a small model; only check they're
      plausible.

## Done
- [ ] All seven phases approved, every box above ticked. Keep using the app; check now and
      then that `ssh hp sudo -n journalctl -u assistant -n 100 --no-pager` shows no errors.
