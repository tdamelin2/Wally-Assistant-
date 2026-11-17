# Decisions — Wally Phase 14 edits + Phase 17 chat redo

Interview 2026-11-10. Research: `RESEARCH.md` (same folder).

| # | Area | Decision | Why |
|---|------|----------|-----|
| A1 | Numbering | Chat redo = Phase 17. Wally Voice becomes Phases 18–19 (was 15–16). | Voice answers through the chat brain, so the better brain comes first |
| A2 | Chat brain | Chat runs on the HP server. The HP calls the XPS Ollama when it answers within 2 s, otherwise its own model. | Chat works with the laptop off; tools, chats and reminders live in one place |
| A3 | Codex | Codex CLI moves from the XPS to the HP, including research jobs and meal lookup (`ServerCodex`, `ServerMealLookup`). Same Phase 13 rules (auto for food/word/app-category templates, approval for the rest). | Phone access with the laptop off |
| A4 | Push | No push service. The HP queues notifications; the phone fetches them at sync and shows Android notifications. Timed reminders are scheduled on the phone after sync. | No Google, no extra app or server |
| A5 | Auto-sync | Every 10 min while the app is open (phone and laptop); WorkManager every 15 min in the background on the phone. | WorkManager's minimum is 15 min (2.12.0, RESEARCH §3); an always-on service costs battery and a permanent notification |
| A6 | Dates | One shared date field: a Material 3 picker button on both devices; on the laptop also a typeable MM-dd-yyyy field. Laptop shows every date as MM-dd-yyyy; phone shows the phone's locale format. | Nobody types dates on a phone; laptop format asked for |
| A7 | Phase 14 UX | Add: swipe to complete/delete with a 5-s Undo snackbar; animations (check-off, list add/remove, hub and chip changes); haptics on the phone; pull-to-refresh; "Synced N min ago". | Chosen from the research list |
| A7a | Quick-add | Not built. Chat (Phase 17) understands "gym tomorrow 6pm". | Avoids two natural-language parsers |
| A8 | Approvals | Small adds (task, check-in, meal, spending, journal note, dream when asked) happen at once with an Undo button in the chat. Edits, study-guide/quiz jobs, events, mail organising, Codex beyond templates and anything else show a yes/no card. Still no deletes and no sending mail. | Fewer clicks; a small model can misread, so bigger actions ask |
| A9 | Resolving names | Chat searches names (course, study doc, goal, task, file name) plus the screen you opened chat from. One match: uses it. Several: shows up to 5 tap-to-pick chips. None: says so and lists what exists. Never asks for an ID. | The study-guide complaint |
| A10 | Personality | Warm friend: casual, uses your name, refers to your real data, 1–4 sentences unless asked for more, no canned templates. One system prompt file. | Asked for "helper, friend" |
| A11 | Chats | Up to 10 chats in a list (like Claude/ChatGPT), stored on the HP, same on phone and laptop. Deleting a chat summarizes it in the background into the `memory_note` table. | Asked for |
| A12 | 11th chat | Starting an 11th archives the least recently used chat the same way (summarize, then remove) and shows a one-line notice. | Never blocks you |
| A13 | Memory view | A read-only Memory screen inside Chat (opened from the chat list) shows all summaries, newest first (moved from Browse by redesign v2 U20). | Asked for ("a file in a new tab") |
| A14 | Notifications | Check-in reminder (time set in Settings, default 21:00); morning due list (07:30) and a heads-up 30 min before anything timed; up to 2 Wally questions/ideas a day (incl. screentime classification questions); quiet hours 23:00–07:00; cap 6 a day (timed heads-ups count). Tapping opens the matching chat. | Asked for |
| A15 | Reports | A weekly report, Sunday 19:00, from the last 7 days (sleep, mood, tasks, spending, study, screentime), posted to a "Weekly report" chat and notified. Deep multi-month analysis → later phase (Phase 18 candidate, not planned). | Little data yet; small model |
| A16 | HP model | Task 17.1 tests qwen3:4b and llama3.2:3b on 20 fixed prompts; higher score kept, tie → qwen3:4b. Ollama stays 0.40.14 unless the test fails for a version reason. | Tool calling on these models unconfirmed (RESEARCH §5) |
| A17 | Tool scope | Read + write: tasks, check-ins, meals, spending, journal, goals, study, calendar (via WallyCalendar hook), hardware troubleshooter, screentime classification, mail organising (no sending). Read-only: Garmin. Off-limits: Settings, Files. | Your answer |
| A18 | Dreams | Dream data enters a chat prompt only when your message mentions dreams (word list in code). Reports stay dream-free. Never to Codex. `DreamWallTest` rewritten for this. | Your answer; changes N10/N33 |
| A19 | Offline | If the phone/laptop cannot reach the HP, the chat shows an offline banner; messages wait in the local send queue (A26). No local fallback model. | Simplest; the HP is on Tailscale and always on |
| A20 | Versions | Ollama 0.40.14 (kept); Codex CLI 0.167.0 on the HP; WorkManager 2.12.0; Compose Multiplatform 1.12.2 (already Wally's); kotlinx-datetime format DSL (version Wally already uses; 0.8.0 if an upgrade is needed). | RESEARCH.md |
| A21 | Card approvals | Only the HP executes approvals: the existing `ApprovalExecutor` is refactored to take an `ApprovalSink` and runs on the server; chat cards and Files > Approvals both call `POST /v1/approvals/{id}`; approving needs the HP. | Only option that works with the laptop off (review round 2) |
| A22 | Hardware in chat | Read-only (`get_hw_checks`); checks themselves run on the laptop | The troubleshooter needs the laptop hardware (JNA) |
| A23 | Existing tools | `queue_job`, `suggest_mail_action`, `draft_email`, `read_doc` kept (card/read); no duplicate study/mail tools added; `research` (templates) auto, `research_question` (free text) card | Review round 3: reuse what exists |

## Changes to locked decisions (you approve at each phase's approval step)
| Locked | Was | Now | Phase |
|--------|-----|-----|-------|
| Overview "HP has 4 GB RAM … one model run at a time" | 4 GB, `qwen3:1.7b`, one run | 8 GB, model from Task 17.1, still one run at a time; server heap stays `-Xmx512m` | 17 |
| "Chat: apps call Ollama on the XPS directly" | Client → XPS | Client → HP `/v1/chat/*` → XPS or HP model | 17 |
| Codex on the XPS (N28–N31, Phase 13) | XPS | HP | 17 |
| Dream wall N10/N33 | Dreams only in dream analysis | Also in chat when your message mentions dreams; never Codex, never reports | 17 |
| Chat history per device until Clear | Per device | 10 shared chats on the HP | 17 |
| Approval card for every write (N3) | Every write | Small adds auto with Undo (A8) | 17 |
| Approvals executed on the device (Phase 13 `ApprovalExecutor`) | Client | HP only, via `/v1/approvals/{id}` (A21) | 17 |
| Approved mail suggestion | Stored as `SUGGESTED`; Tim acts later in the Mail tab | Approving in chat or Approvals runs the label/archive at once (`APPROVED` + `gmailWake`) | 17 |
| Wally Voice Phases 17–18 | 15–16 | 16–17; its "XPS-or-HP brain" calls the Phase 17 chat endpoint | 17 |
| Sync "on open, after each save and from a Sync button" | No timer | Plus 10-min timer while open and 15-min background | 14 |
| Out of scope "Android background workers" (N19) | Out | WorkManager sync + notification worker | 14 |
| Phase 14 Notes "No new dependencies" | None | androidx.work 2.12.0 | 14 |
| Out of scope "saved chat history" | Out | 10 saved chats + memory notes | 17 |
| N2 "AI writes without an approval card" out of scope | Out | Small adds auto with Undo (A8) | 17 |
| Codex CLI 0.166.0 (Tech stack) | 0.166.0 on XPS | 0.167.0 on HP | 17 |
| HP model `qwen3:1.7b` for briefing + nutrition | qwen3:1.7b | Task 17.1 winner (`briefingModel` config) | 17 |

## Added 2026-11-11 (redesign v2 canvas)
| # | Decision | Chosen | Why |
|---|----------|--------|-----|
| A24 | Dream patterns | The pattern job is removed in Phase 15 (redesign v2 U12); in chat, when Tim asks about dreams, Wally may summarise patterns across his dreams (dreams enter chat only when mentioned, A18). Never in reports or Codex. | Tim: "If I want patterns to be found I'll ask the chat" |
| A25 | Chat layout | Built from the canvas: phone drawer + composer above the bar; laptop chat list column with hide toggle (Ctrl+B), Enter sends / Shift+Enter new line | Canvas Phone row 8, Laptop row 3 |
| A26 | Offline send queue | Messages typed offline are kept in local `kv` `chat.outbox` and sent in order after the next successful sync; shown greyed with a clock | Tim chose to keep the queue |
