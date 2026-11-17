# Phase 4 checklist: Google on Today (PLAN Task 3.4)

File name is phase3.md because it follows the PLAN task numbers (Tasks 3.x). Do this after `ops/google-setup.md` is finished and `google poll ok` appears in the server journal. Run it on BOTH apps: the installed laptop app (Wally 1.2.0) and the phone app. Use the Settings tab `Sync now` button whenever a step says Sync.

Screen labels used below: Browse > Mail shows `Unread mail` (sender, subject and one line of text; `No unread mail` when empty); Today no longer shows mail (since Phase 14). The red banner reads `Google disconnected — run google-auth on the server`.

Checks (do each on the laptop app and on the phone app):
- [ ] Replaced in 1.9.0: events now come from Wally Calendar, check them in `ops/checklists/w13-calendar.md` (Today shows no events; they are checked via the server journal and Chat).
- [ ] Browse > Mail shows `Unread mail` with your real unread inbox mail (check two: sender and subject).
- [ ] `Updated HH:MM` shows a recent time (within about 20 minutes).
- [ ] Replaced in 1.9.0: a test event made in Wally Calendar is checked in `ops/checklists/w13-calendar.md`. A Google event only appears after you press Import in Wally Calendar.
- [ ] Open one unread mail in Gmail so it becomes read. After the next 15-minute check plus `Sync now` it disappears from `Unread mail` in Browse > Mail.
- [ ] Nothing changed in Google: no mail was sent, no event was edited or added by Wally (look at Gmail Sent; Wally no longer reads Google Calendar).
- [ ] Revoke test: open https://myaccount.google.com/permissions, remove `Wally` only. After the next 15-minute check plus `Sync now` the red banner `Google disconnected — run google-auth on the server` appears.
- [ ] Backup still works after the revoke test: run `ssh -t hp sudo -u assistant -H rclone lsf gcrypt:` and the newest file is from today or the next night's backup adds a new one. If not, follow "If the backup login was also hit" in `ops/google-setup.md`.
- [ ] Sign in again with `google-auth` (`ops/google-setup.md` step 6). After the next 15-minute check plus `Sync now` the banner is gone and `Updated HH:MM` moved on.
- [ ] Update "last google-auth" and "AC-4 due" in `ops/google-setup.md`.

Dated pending checks (not for today):
- [ ] AC-4 (Gmail evidence since 1.9.0; it was due 2026-11-16 or 8 days after "last google-auth", whichever is later; the new due date is 8 days after the re-auth done for 1.9.0, see `ops/google-setup.md`): on that date `ssh -t hp sudo journalctl -u assistant -n 50 --no-pager` shows a recent `google poll ok` line and Browse > Mail still shows `Unread mail` after `Sync now` (no red `Google disconnected` banner). If Google logged you out, record it (decision D38 fallback) and sign in again.
- [ ] AC-5, due 2026-11-19, before the Phase 5 gate: `ssh -t hp sudo ls /var/backups/assistant` and `ssh -t hp sudo -u assistant -H rclone lsf gcrypt:` each list 14 files, one per date, the newest from today. The first file is dated 2026-11-06.
