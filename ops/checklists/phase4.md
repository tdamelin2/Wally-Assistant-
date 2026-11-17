# Phase 5 checklist: Garmin health data (PLAN Tasks 4.1-4.3)

File name is phase4.md because it follows the PLAN task numbers (Tasks 4.x). Run it on BOTH apps: the phone app and the installed laptop app (Wally 1.18.0). Use the Settings tab `Sync now` button whenever a step says Sync.

Before you start (once):
- [ ] In the Garmin Connect app, sharing with Health Connect is on for sleep, steps, resting heart rate and exercise, and the watch has synced to Garmin Connect.

Phone (Health tab):
- [ ] Open the Health tab. If it shows `Allow Health Connect`, tap it and allow all four. Then tap `Sync now` in Settings.
- [ ] Last night's sleep matches the Garmin Connect app within 1 percent (the per-day list shows it as `7 h 30 min`).
- [ ] Today's steps match the Garmin Connect app within 1 percent.
- [ ] Resting heart rate matches Garmin Connect, or shows `no data` where `ops/health-check.md` says Garmin does not write it.
- [ ] A day you did not wear the watch shows `no data` (not 0).
- [ ] A workout recorded only in Samsung Health does not appear in the workouts list.
- [ ] Workout line: record a short workout with the watch, sync Garmin Connect, then Sync Wally. It shows with type and duration. If it never appears, update the exercise row in `ops/health-check.md`.
- [ ] Deny test: in Android Settings remove Health Connect access for Wally, reopen the app. The Health tab shows `Allow Health Connect`, and the other tabs and Sync still work. Allow again.

Laptop (Health tab):
- [ ] After `Sync now`, the laptop shows the same sleep, steps, resting heart rate and workouts as the phone. It has no `Allow` button.
- [ ] The two charts (sleep in hours, steps) cover 30 days and days without data have no point or bar.

Add as task (D60):
- [ ] Replaced in 1.9.0: Today no longer shows events, so there is no "Add as task" on Today. Events become tasks with "Make Wally task" in Wally Calendar; check that in `ops/checklists/w13-calendar.md`.
