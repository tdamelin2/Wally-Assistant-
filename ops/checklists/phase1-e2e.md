# Phase 1 end-to-end checklist: installed apps (PLAN Phase 1 gate, SPEC criteria 1-7 and 13)

Use the installed desktop app (`Wally`, from the MSI `Wally-1.18.0.msi`) and the phone app. Both must show "Connected" under Settings > `Test connection` first. Use only synthetic (made-up) test entries and delete them at the end. Do not enter real data until the backup restore test has passed (`ops/backup-setup.md`).

Screen labels used below: tabs `Today`, `Log`, `Charts`, `Health`, `Chat` (sidebar on the laptop, bottom bar on the phone) and `Settings` (bottom of the sidebar, gear icon on the phone); the `+` menu (`New entry` on the laptop, round `+` button on the phone) with `Check-in`, `Spending`, `Journal`, `Task`, `Meal`; Log tabs `Check-ins`, `Spending`, `Journal`, `Tasks`; tap a row to edit it, and `Delete` is in the row's three-dot menu; Settings buttons `Test connection` and `Sync now`; sync status: the sync icon (laptop sidebar text "Synced HH:mm") and the red bar text "Offline — N waiting".

1. Cross-device entries
- [ ] Phone: save one check-in, one spending, one journal entry and one task. Laptop: tap `Sync now`. All four appear in the matching Log lists.
- [ ] Laptop: save one entry of each type. Phone: tap `Sync now`. All four appear on the phone.

2. Offline save, no duplicate
- [ ] Phone: turn on airplane mode. Save one spending entry. The red bar under the title shows "Offline — 1 waiting".
- [ ] Turn airplane mode off and tap `Sync now`. The red bar goes away and the sync icon shows the cloud with a tick, and the laptop, after `Sync now`, shows exactly one new spending row (no duplicate).

3. Last edit wins
- [ ] On the HP (logged in there) stop the server: `sudo systemctl stop assistant`.
- [ ] Edit the same journal entry on the phone (text `edit phone`) and on the laptop (text `edit laptop`, saved later). Both show "Offline — 1 waiting".
- [ ] Start the server: `sudo systemctl start assistant` (on the HP).
- [ ] Tap `Sync now` on both devices, twice each. Both show `edit laptop` (the later edit).

4. Delete propagates
- [ ] Laptop: open the three-dot menu on one entry, choose `Delete` and confirm. Phone: tap `Sync now`. The entry is gone on the phone.

5. Login is required
- [ ] From the laptop run `curl.exe -s -o NUL -w "%{http_code}" http://<server>:8080/v1/health` (replace `<server>` with the server's Tailscale address). It prints `401`.

6. Hebrew right-to-left
- [ ] Save a journal entry with the Hebrew text היום היה טוב. It shows right-to-left on the laptop and on the phone.

Cleanup
- [ ] Delete all test entries on one device and tap `Sync now` on both devices.
