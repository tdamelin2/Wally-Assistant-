# SPEC — Wally Phase 14 edits + Phase 17 chat redo

Project: `C:\Users\user\Projects\assistant`. Decisions: `DECISIONS.md` (A1–A20). Research: `RESEARCH.md`.

## Goal
Phase 14 makes Wally feel fluid (swipe, undo, animations, pull-to-refresh, pickers, auto-sync) on top of the planned 5-hub redesign. Phase 17 turns chat into a friend-like helper on the HP that can act on almost everything you see, works with the laptop off, remembers past chats, and messages your phone.

## Who it's for
You, on the Android phone and the Dell XPS laptop.

## Phase 14 additions (the original redesign Tasks 1–6 are unchanged)
1. Swipe a task or list row right to complete, left to delete; a snackbar with Undo shows for 5 s and Undo restores it exactly.
2. Check-off animation; list rows animate in and out; hub and chip changes cross-fade; light haptic on complete/delete on the phone.
3. Pull down on any list hub to sync; a label shows "Synced N min ago" / "Offline — N waiting".
4. Auto-sync every 10 min while the app is open; every 15 min in the background on the phone.
5. Every date field opens a calendar picker (and time fields a clock picker) on both devices; the laptop field also accepts typed MM-dd-yyyy; the laptop shows all dates MM-dd-yyyy.

## Phase 17
6. Chat goes through the HP (`/v1/chat/*`). It answers when the laptop is off.
7. The HP uses the XPS model when it answers `/api/tags` within 2 s, otherwise its own model chosen in Task 17.1.
8. "Make a study guide on calculus" finds the course by name; if several fit, tap-to-pick chips; never asks for an ID.
9. Small adds happen at once with a 30-s Undo in chat (longer than the 5-s list snackbar on purpose: you read the reply first); bigger writes show a yes/no card; no deletes, no sending mail.
10. Replies are warm, short and refer to real data.
11. Up to 10 chats, same on both devices. Deleting one, or starting an 11th, summarizes the removed chat into memory, viewable in Chat › Memory (from the chat list).
12. Notifications on the phone: check-in reminder, morning due list, 30-min heads-up, up to 2 questions/ideas a day, weekly report on Sunday 19:00; quiet hours 23:00–07:00; at most 6 a day.
13. Codex runs on the HP and is usable from the phone.
14. Dream data is in a prompt only when your message mentions dreams; never in reports or Codex.

## Success criteria (observable)
- SC-1: Swiping a task completes it; Undo within 5 s restores it (phone and laptop).
- SC-2: With the app open and idle for 11 min, a change made on the other device appears without touching anything.
- SC-3: With the phone app closed for 20 min while charging (so Doze, Android's battery-saving idle mode, doesn't delay the job), the background job has run: the `Synced` label shows a time earlier than when you reopened the app and later than the laptop change (or `adb logcat -s SyncWorker` shows the run). On battery and idle, Doze may delay it; that is accepted.
- SC-4: No date field requires typing on the phone; the laptop shows `11-10-2026` style dates and accepts typed `11-10-2026`.
- SC-5: With the XPS shut down, "what's on today?" from the phone gets a real answer within 30 s.
- SC-6: "Make a study guide on <course name>" never asks for an ID and shows chips when more than one match exists.
- SC-7: "Add task buy milk" creates the task immediately with Undo; "move my exam to Friday" shows a card.
- SC-8: Starting chat 11 removes the oldest and its summary appears in Chat › Memory (from the chat list) within 2 min.
- SC-9: A check-in reminder fires on the phone within 15 min of the set time (Doze may delay an idle-allowed alarm by up to about 15 min) when the check-in is missing (it was queued ahead), and doesn't fire if the check-in is done; none arrive 23:00–07:00; never more than 6 a day.
- SC-10: A Codex research request from the phone completes with the XPS off.
- SC-11: `DreamWallTest` passes: no dream text in prompts without a dream word, none in reports, none to Codex.
- SC-12: In 5 real chats, replies are 1–4 sentences unless asked for more, mention at least one real data value when relevant, and never show an ID (Tim's check, in `reports/phase-17.md`).

## Out of scope (owner)
- Quick-add box: covered by chat (A7a).
- Deep multi-month pattern analysis: later phase, not yet planned (Phase 18 candidate).
- Instant push (ntfy/FCM): not planned; revisit if 15-min delay is a problem.
- Local fallback model on the phone: not planned (A19).
- Chat editing Settings or Files: not planned (A17).
- Wally Voice: Phases 18–19 (renumbered plan `docs/plans/wally-voice/`).

## Data
- Phase 14: no new tables. Sync timestamps already in `SyncEngine`.
- Phase 17, server SQLite (not synced to clients except as stated):
  - `chat` (id, title, created_at, last_used_at) — max 10 rows. Read by clients via `/v1/chat/list`.
  - `chat_message` (id, chat_id, role, text, card_json, created_at) — deleted with its chat.
  - `memory_note` (id, source_chat_title, summary, created_at) — kept forever; read via `/v1/memory`.
  - `outbox_notification` (id, kind, title, body, chat_id, fire_at, delivered_at) — fetched by the phone at sync; rows older than 7 days deleted.
- All times stored as epoch millis, Asia/Jerusalem for scheduling (existing convention).

## Constraints
- HP: 8 GB RAM, Debian, Tailscale only, server heap `-Xmx512m`, one model run at a time (queue the rest).
- Privacy: no new cloud services; Codex is the only cloud call, unchanged rules.
- Never edit `C:\Users\user\Projects\WallyCalendar`.
