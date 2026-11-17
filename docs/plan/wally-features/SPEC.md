# Wally new features (W8–W13) — Spec

Built in `C:\Users\user\Projects\assistant` after Wally Phase 7 passes (N23). W13 also waits for WallyCalendar (`C:\Users\user\Projects\WallyCalendar`) Phase 5. Decisions: `DECISIONS.md` (N1–N33). Facts: `RESEARCH.md`.

## 1. Goal
Extend Wally, Tim's private assistant on his Samsung S25 and Dell XPS 16, with new tabs: Files, Dream, Goals, Study, Screentime, Mail, Hardware and Approvals, plus AI tools and Codex research in Chat. Tim is a student: Study turns his Moodle slides into summaries, flip cards and practice quizzes with an exam countdown; the other tabs keep his documents, dreams, goals, device use, email and laptop health in one place; the chat AI can read all of it (except dreams), look things up through Codex (keeping a research memory so it asks less), and propose actions he approves. Once WallyCalendar is ready, Wally takes its calendar from it, creates events through its hook and coaches Tim toward the Dream week he designs there. Everything keeps Wally's rules: offline-first sync, local AI, and nothing leaves his hardware without a rule or an approval saying so.

## 2. Success criteria
1. (W8) A doc created on one device, with a folder, appears on the other after sync; rename, move, edit and delete also sync; search by title or body text finds it offline.
2. (W8) The doc editor's Preview shows headings, bold, italic, bullet lists and links as formatted text; Hebrew text displays right-to-left.
3. (W8) A dream saved on the phone appears on the laptop after sync with date, text, mood, lucid flag and tags.
4. (W8) "Interpret" on a dream produces an analysis stored on that dream once the desktop app has run the job; "Patterns" produces one over the last 30 dreams, stored in the `dream_pattern` row of that day.
5. (W8) `DreamWallTest` passes: chat context, briefing, every non-dream job prompt, every read tool's output and every research question contain no text from a dream row, and only the allowed files reference dream queries.
6. (W8) A job queued on the phone while the laptop is closed runs within 1 minute of opening the desktop app, and its result appears on the phone after sync; a job that fails 3 times shows "Failed" with the error.
7. (W8) "Plan steps" on a goal proposes 3–10 steps; ticked steps become tasks with that `goalId`; the goal shows "done / total" for linked, non-deleted tasks.
8. (W8) "Plan my ideal week" saves a doc in folder `Plans` comparing the typed ideal week with the next 7 days of calendar events, open tasks and goals, with concrete steps. (W13 replaces the typed week with the WallyCalendar Dream week, criterion #33.)
9. (W9) After Tim pastes the Moodle link in Settings, the HP lists his enrolled courses and their PDF/PPTX files within 24 hours, and again daily; a file already listed is not listed twice.
10. (W9) A manually uploaded PDF or PPTX (≤ 50 MB) appears in the Study tab like a Moodle file.
11. (W9) "Make study set" produces a summary doc in folder `Study – <course>` and 10–40 flip cards linked to the file; marking cards "knew it / didn't" is saved and synced.
12. (W9) "Practice quiz" on a course produces 10 multiple-choice questions from that course's study sets; finishing it saves the score.
13. (W9) The Today screen shows the next 3 exams with "in N days" (Asia/Jerusalem dates); an exam in the past is not shown.
14. (W10) After a phone sync, the phone's app use since the last read (max 7 days) appears as sessions on both devices, with no duplicates after repeated syncs.
15. (W10) With desktop Wally in the tray, laptop foreground-app sessions are recorded; no session is recorded while there is no input for 120 s or more.
16. (W10) The Screentime tab shows productive / unproductive / neutral minutes for today and each of the last 7 days per device, top 10 apps, and an "Unsorted" list; changing a rule changes the totals immediately.
17. (W10) Spotify tracks played appear in a Listening list within 30 minutes of playing (when the HP has a Spotify token); polling twice never duplicates a play.
18. (W11) "Draft reply" on an unread mail produces a draft in Wally; nothing is sent until Tim taps Send; after Send the draft shows "Sent" and the mail is in Gmail's Sent folder, in the same thread for replies.
19. (W11) A draft is sent at most once, even if Send is tapped on both devices or the server restarts during sending.
20. (W11) "Organise inbox" suggests label/archive actions using only existing Gmail labels; only approved suggestions are applied in Gmail.
21. (W11) "Run check" on the XPS saves a hardware check with battery, disk and recent error sections; a section whose command fails says "Not available" instead of failing the whole check.
22. (W11) "Explain" with a typed problem produces likely causes and next steps citing values from the latest check.
23. (W12) In chat, a question like "what did I do on my laptop yesterday?" is answered using a read tool with real values.
24. (W12) A request like "make a task to email my lecturer" shows an approval card; nothing is written until Approve; Reject creates no output row (only the approval's status changes); the same pending approval shows on the other device after sync and expires after 7 days.
25. (W12) No tool can read dream data and no tool sends email or changes Gmail without an approved approval (covered by tests).
26. (all) Existing Wally checklist `ops/checklists/phase1-e2e.md` still passes after each phase, and data saved before each upgrade is still there on both devices.
27. (W12) A research request in an automatic category with a valid item (e.g. nutrition of "hummus") runs through Codex without an approval card once the desktop app runs it, and is saved as a `research` row; asking the same question again is answered from that row with no Codex call.
28. (W12) Any other research question, or an automatic one whose item fails validation, shows an approval card with the exact text that will be sent; nothing is sent to Codex before Approve.
29. (W12) "Look up" on a meal replaces its kcal/protein/carbs/fat with values computed from research; foods already in the research memory are not asked again.
30. (W12) When Codex is missing, not logged in, or over its limit, the research job shows "Failed" with "Codex unavailable on this laptop" or "Codex limit reached", and nothing else breaks.
31. (W13) Events created or changed in WallyCalendar appear on Wally's Today screen within 15 minutes plus the device's next sync; Wally's server no longer calls the Google Calendar API, and no Google-sourced duplicates remain.
32. (W12, Task 13.3) An approved `create_event` card creates exactly one event in WallyCalendar, even if the card is approved on both devices.
33. (W13) "Coach me on my Dream week" reads the blocks last sent from WallyCalendar and saves a coaching doc with differences and 3–10 concrete steps; if nothing was sent yet it says "Send your Dream week from Wally Calendar first".

## 3. Scope
### In scope
Everything in §2, on Android and Windows desktop, with the server parts on the HP and Codex on the XPS.

### Out of scope (deferred)
| Item | Owned by |
|------|----------|
| Netflix history | Not planned (N24) |
| Spaced-repetition scheduling | Plan after W13 (N25) |
| Storing arbitrary uploaded files in Files (images etc.) | Not planned (N8) |
| Writing to Google Calendar | Not planned (WallyCalendar C22) |
| Any edit to WallyCalendar documents or code; Dream week editing inside Wally | WallyCalendar's own plan |
| Gmail drafts inside Gmail, creating Gmail labels | Not planned (N15) |
| Temperature/fan monitoring, any hardware fix that changes settings | Not planned (N16) |
| Cloud AI models other than Codex research | Not planned (N3) |
| "Similar question" matching in the research memory | Not planned (N30) |
| Automatic research on every meal | Not planned (N31) |
| Android background tracking workers | Not planned (N20) |
| Saved chat history | Stays out (D35 part kept) |
| Ollama upgrade | Only if PLAN Task 12.1 requires it (N22) |

## 4. Users and flows
- **Files**: Files tab → folder list and docs → New doc (title, folder) → editor with Edit/Preview toggle → Save. Long-press/right-click: rename, move, delete. Search box filters by title and body.
- **Dream**: Dream tab → "+" → form (date default today, text, mood 1–5, lucid switch, tags comma-separated) → Save. Dream detail → Interpret → "Queued — runs when Wally is open on the laptop" → analysis text appears. Patterns button on the list.
- **Goals**: Goals tab → "+" (title, why, deadline) → goal detail → "Plan steps" → queued → proposed steps with checkboxes → "Add selected as tasks". Progress bar. W8–W12: "Plan my ideal week" → text box → queued → doc opens. From W13: "Coach me on my Dream week" (no text box) with "Last sent from Wally Calendar: <time>" → queued → doc opens.
- **Study**: Settings → Moodle → "Open Moodle login" opens the browser at the launch URL → after SSO the browser fails to open `wally://token=…` → Tim copies the address bar → pastes in "Paste Moodle link" → "Connected". Study tab → courses → files (new ones marked) → "Make study set" → queued → summary doc + "Cards (N)" → flip view (tap to flip, "Knew it" / "Didn't"). Course → "Practice quiz" → questions one at a time → score. Exams list → "+" (course, title, date, time).
- **Screentime**: Android: on first open of the tab, a button opens Usage access settings. Desktop: Settings → "Keep running in tray" (default on) and "Start with Windows". Tab: Today / 7 days, per device, top apps, Unsorted list → tap an app → choose label (creates a rule). "Suggest labels" queues an AI job → suggestions with Accept per row. Listening section lists Spotify plays.
- **Mail**: Mail tab shows the unread list → mail → "Draft reply" (optional instruction) → queued → draft editor (to, subject, body editable) → Send → "Sending…" → "Sent". "New email" opens an empty draft. "Organise inbox" → queued → suggestion list → Approve / Reject each.
- **Hardware** (desktop only; the phone shows past checks read-only): Hardware tab → Run check → sections → "Describe a problem" → Explain → queued → answer.
- **AI tools**: Chat → question → the AI may call read tools (shown as "Looked at: tasks, screentime") → answer. Write requests → approval card (summary, Approve / Reject). Approvals tab lists pending ones from both devices.
- **Research**: Chat → "how many calories in hummus?" → the AI calls `research(NUTRITION, "hummus")` → memory hit: answer now; miss: "Queued research — answer appears when Wally is open on the laptop" → answer with sources appears in chat history for that session and in the Research list. A free question ("what's the best way to study organic chemistry?") → approval card showing the exact text → Approve → queued → answer. Approvals tab → Research section: browse, delete.
- **Meal Look up**: meal detail → "Look up" → queued → kcal/protein/carbs/fat replaced, labelled "≈ … kcal (research)".
- **WallyCalendar (W13)**: Tim designs the Dream week and events in the WallyCalendar app. Wally's Today shows those events. In chat, "add gym Monday 7–8 every week" → `create_event` approval card → Approve → event appears in WallyCalendar after its sync.

## 5. Data
All synced tables have Wally's five columns (`id TEXT PK`, `updatedAt INTEGER`, `deleted INTEGER`, `serverSeq INTEGER DEFAULT 0`, `syncState TEXT`), last-edit-wins, soft delete. Instants UTC epoch ms; `localDate` `yyyy-MM-dd` Asia/Jerusalem. Kept forever unless deleted, except where stated.

| Table (phase, migration) | Fields after the five columns | Written by |
|---|---|---|
| `doc_folder` (W8, 2.sqm) | `name TEXT` (flat folders, no nesting) | C |
| `doc` (W8, 2.sqm) | `folderId TEXT NULL`, `title TEXT`, `body TEXT` (Markdown, ≤ 200 000 chars), `source TEXT` (`USER`/`AI`) | C |
| `ai_job` (W8, 2.sqm) | `kind TEXT`, `inputJson TEXT`, `status TEXT` (`QUEUED`/`RUNNING`/`DONE`/`FAILED`), `resultJson TEXT NULL`, `error TEXT NULL`, `attempts INTEGER`, `createdAt INTEGER`, `startedAt INTEGER NULL` | C |
| `dream` (W8, 2.sqm) | `localDate TEXT`, `text TEXT`, `mood INTEGER` 1–5, `lucid INTEGER` 0/1, `tags TEXT` (comma-separated, lowercase, trimmed), `analysis TEXT NULL`, `analysisModel TEXT NULL` | C |
| `dream_pattern` (W8, 2.sqm) | `localDate TEXT`, `text TEXT`, `model TEXT` (id `dreampattern:<localDate>`) | C |
| `goal` (W8, 2.sqm) | `title TEXT`, `why TEXT NULL`, `deadline TEXT NULL` (localDate), `status TEXT` (`ACTIVE`/`DONE`/`DROPPED`) | C |
| `task.goalId` (W8, 2.sqm) | new nullable column `goalId TEXT` on existing `task` | C |
| `course` (W9, 3.sqm) | `moodleId INTEGER NULL`, `name TEXT` (id `course:<moodleId>` or `course:manual:<uuid>`) | S |
| `study_file` (W9, 3.sqm) | `courseId TEXT`, `name TEXT`, `origin TEXT` (`MOODLE`/`UPLOAD`), `sizeBytes INTEGER`, `textStatus TEXT` (`PENDING`/`READY`/`FAILED`), `studySetDocId TEXT NULL` (id `sfile:<sha256 of moodle fileurl>` or `sfile:upload:<uuid>`) | S |
| `card` (W9, 3.sqm) | `fileId TEXT`, `front TEXT`, `back TEXT`, `lastResult TEXT NULL` (`KNEW`/`DIDNT`), `lastReviewedAt INTEGER NULL` | C |
| `quiz` (W9, 3.sqm) | `courseId TEXT`, `questionsJson TEXT` (10 × {q, options[4], answerIndex}), `score INTEGER NULL`, `takenAt INTEGER NULL` | C |
| `exam` (W9, 3.sqm) | `courseId TEXT NULL`, `title TEXT`, `localDate TEXT`, `time TEXT NULL` (`HH:mm`) | C |
| `usage_session` (W10, 4.sqm) | `device TEXT` (`PHONE`/`LAPTOP`), `app TEXT` (package or exe name), `title TEXT NULL` (≤ 200 chars, laptop only), `startAt INTEGER`, `endAt INTEGER`, `localDate TEXT` (of startAt). Id `use:phone:<pkg>:<startAt>` / `use:laptop:<startAt>` | C |
| `app_rule` (W10, 4.sqm) | `matchType TEXT` (`PACKAGE`/`EXE`/`TITLE_CONTAINS`), `pattern TEXT` (lowercase), `label TEXT` (`PRODUCTIVE`/`UNPRODUCTIVE`/`NEUTRAL`). Id `rule:<matchType>:<pattern>` | C |
| `listen` (W10, 4.sqm) | `trackName TEXT`, `artist TEXT`, `durationMs INTEGER`, `playedAt INTEGER`, `localDate TEXT`. Id `listen:<playedAt>:<trackId>` | S |
| `mail_draft` (W11, 5.sqm) | `toAddr TEXT`, `subject TEXT`, `body TEXT`, `replyToGmailId TEXT NULL`, `threadId TEXT NULL`, `status TEXT` (`DRAFT`/`APPROVED`/`SENDING`/`SENT`/`FAILED`/`UNKNOWN`), `sentAt INTEGER NULL`, `error TEXT NULL` | C+S |
| `mail_action` (W11, 5.sqm) | `gmailId TEXT`, `action TEXT` (`LABEL`/`ARCHIVE`), `labelId TEXT NULL`, `labelName TEXT NULL`, `reason TEXT`, `status TEXT` (`SUGGESTED`/`APPROVED`/`REJECTED`/`DONE`/`FAILED`) | C+S |
| `hw_check` (W11, 5.sqm) | `takenAt INTEGER`, `resultJson TEXT` (sections), `summary TEXT` | C |
| `approval` (W12, 6.sqm) | `tool TEXT`, `argsJson TEXT`, `summary TEXT`, `status TEXT` (`PENDING`/`APPROVED`/`REJECTED`/`EXPIRED`/`DONE`/`FAILED`), `createdAt INTEGER`, `resultText TEXT NULL` | C |
| `research` (W12, 6.sqm) | `category TEXT` (`NUTRITION`/`DEFINITION`/`APP_CATEGORY`/`OTHER`), `questionKey TEXT` (lowercase, trimmed, spaces collapsed), `question TEXT` (the exact text sent), `answerText TEXT`, `answerJson TEXT` (schema output), `sources TEXT` (JSON array of URLs), `askedAt INTEGER`, `expiresAt INTEGER NULL` (null for NUTRITION and DEFINITION; `askedAt + 180 d` otherwise). Id `research:<category>:<sha256(questionKey)>` | C |

Meal changes (W12, no migration): "Look up" writes the existing `meal` fields `kcal`, `proteinG`, `carbsG`, `fatG` and `estimateModel = "research"` as a normal client edit.

`cal_event` (existing, W13): rows now come from WallyCalendar with id `wcal:<eventId>:<occurrenceDate>` and `calendarId = "wallycal"`; older Google-sourced rows are soft-deleted once.

Who writes each table (PLAN Recipe R1):
- **C** (client-written, in `CLIENT_TABLES`): the desktop job runner is a client, so its writes count as client writes.
- **S** (server-written, in `SERVER_WRITTEN_TABLES`, via `writeIfNewer`): `course` (manual courses `course:manual:*` are refused on push; manual uploads go through `POST /v1/study/upload`, which creates the course server-side when `courseId` is a new name), `study_file` (`studySetDocId` is set only through `POST /v1/study/files/{id}/study-set {"docId"}`), `listen`, and (existing) `cal_event`.
- **C+S**: `mail_draft` (clients may push `DRAFT` or `APPROVED`; only the server sets `SENDING`/`SENT`/`FAILED`/`UNKNOWN`/`sentAt`, and Validation refuses those from clients); `mail_action` (clients may push `SUGGESTED`/`APPROVED`/`REJECTED`; only the server sets `DONE`/`FAILED`).

Server-only (not synced, not in the database): extracted slide text in `/var/lib/assistant/study/<fileId>.txt` next to the original file `/var/lib/assistant/study/<fileId>` (both deleted when the `study_file` row is deleted); Moodle token in `/etc/assistant/moodle-token.json` and Spotify refresh token in `/etc/assistant/spotify-token.json`, written the same way as Google's token file (`writeTokenFile` in `GoogleAuth.kt`), so they are not in the database backups; the WallyCalendar `wally` token in `/etc/assistant/config.json` (`wallycal` block); cursors and the `calcreate:<clientKey>` map in the server `kv` table; Gmail labels fetched live. Moodle, Spotify and WallyCalendar connection state reaches the apps as `moodleOk` / `spotifyOk` / `calendarOk` in `/v1/health`, next to the existing `googleOk`.

## 6. Interfaces
New server endpoints (all `/v1`, existing Wally bearer auth, Tailscale only):
| Endpoint | Input | Output |
|---|---|---|
| `POST /v1/study/moodle-token` | `{"link":"wally://token=<base64>"}` | 200 `{"courses":N}`; 400 `{"error":"bad link"}`; 502 `{"error":"moodle rejected token"}` |
| `POST /v1/study/sync-now` | — | 200 `{"newFiles":N}`; 409 if running |
| `POST /v1/study/upload` | multipart: `courseId`, `file` (.pdf/.pptx, ≤ 50 MB) | 201 `{"fileId":…}`; 413; 415 |
| `GET /v1/study/files/{id}/text` | — | 200 `text/plain` (≤ 400 000 chars); 404; 409 if `textStatus` not READY |
| `GET /v1/study/files/{id}/original` | — | the file bytes |
| `POST /v1/study/files/{id}/study-set` | `{"docId":"…"}` | 200; 404 unknown file |
| `GET /v1/mail/{gmailId}/body` | — | 200 `{"from","to","subject","date","text"}` (text ≤ 20 000 chars) |
| `GET /v1/mail/labels` | — | 200 `[{"id","name"}]` user labels only |
| `GET /v1/calendar/dream-week` (W13) | — | WallyCalendar's `GET /v1/dream-week` body as-is; 404 `{"error":"not-sent"}`; 502 `{"error":"calendar-unreachable"}` |
| `POST /v1/calendar/events` (W13) | `{clientKey, title, startAt, endAt, allDay, repeatRule, repeatUntil?, note?, location?, color?, reminderMinutes?}`; forwarded with every WallyCalendar own-event field, `color` default `BLUE`, others default null | 201 `{"id"}`; 200 `{"id"}` when `clientKey` was already used; 400 passed through from WallyCalendar; 502 `{"error":"calendar-unreachable"}` |
| `GET /v1/health` (existing) | — | adds `moodleOk`, `spotifyOk`, `calendarOk` (each `Boolean?`) |

WallyCalendar endpoints used by Wally's server (W13, its `wally` token, contract in WallyCalendar SPEC §6; nothing in WallyCalendar changes): `GET /v1/events?from=&to=` (≤ 400 days), `POST /v1/events`, `GET /v1/dream-week`.

Sync push/pull keeps its shape; new tables are added to it. Server reacts on push: `mail_draft` with status `APPROVED` → send; `mail_action` `APPROVED` → apply.

New CLI on the HP: `assistant spotify-auth` (same pattern as `google-auth`).

Codex on the XPS (W12), run by the desktop job runner: `codex exec --skip-git-repo-check --ephemeral -s read-only -c web_search="live" -C <empty temp dir> --output-schema <schema.json> -o <out.json> "<question>"` (flags confirmed against `codex exec --help` in PLAN Task 12.1).

Job kinds (`ai_job.kind`) and inputs: `DREAM_INTERPRET {dreamId}`, `DREAM_PATTERNS {localDate}`, `GOAL_STEPS {goalId}`, `IDEAL_WEEK {description}` (W13: `{}`), `STUDY_SET {fileId}`, `QUIZ {courseId}`, `APP_LABELS {}`, `MAIL_DRAFT {gmailId|null, instruction}`, `MAIL_TRIAGE {}`, `HW_EXPLAIN {checkId, problem}`, `RESEARCH {category, item}` or `{category: "OTHER", question}`, `MEAL_LOOKUP {mealId}`.

Research templates (automatic, N29), `{item}` = 1–60 chars of letters (incl. Hebrew), digits, spaces, `-`, `'`, `.`, with no `@` and no run of 5+ digits:
- NUTRITION: "Nutrition facts per 100 g of {item}. Return kcal, proteinG, carbsG, fatG and source URLs."
- DEFINITION: "Define {item} in one short paragraph with source URLs."
- APP_CATEGORY: "What kind of app is {item}? One sentence and a category word, with source URLs."

Screens: tabs Files, Dream, Goals, Study, Screentime, Mail, Hardware, Approvals (with a Research section) — desktop sidebar + phone "More" sheet per Wally's D60 layout; Settings sections Moodle, Screentime, Spotify status, Calendar status (W13).

## 7. Edge cases and error states
| Case | Expected behaviour |
|------|--------------------|
| Job queued, laptop app closed | Status "Queued — runs when Wally is open on the laptop" |
| XPS Ollama unreachable while runner has a job | Job back to QUEUED, attempts unchanged, retry after 60 s |
| Job RUNNING for more than 30 min (app closed mid-job) | On next runner start it returns to QUEUED with attempts + 1; at 3 attempts → FAILED |
| Model output not valid JSON for a JSON job | Counts as a failed attempt with error "bad model output" |
| Dream job | Result written only to the dream / dream_pattern row; `ai_job.resultJson` stays null; on failure `error` is the fixed text "dream analysis failed"; dream handlers have no research or tool access |
| Empty doc title | Save disabled |
| Doc body > 200 000 chars | Save refused with "Too long" |
| Deleting a folder with docs | Docs move to "No folder"; folder soft-deleted |
| Moodle link malformed | 400 "bad link" shown under the field |
| Moodle token rejected later (`invalidtoken`) | `moodleOk:false`; Study tab banner "Reconnect Moodle"; daily check skipped |
| Moodle file not PDF/PPTX | Not listed |
| Text extraction fails or yields < 200 chars (scanned PDF) | `textStatus FAILED`, button disabled, "No text found (scanned?)" |
| Slide text longer than 12 000 chars | Summarised in chunks of 12 000 chars, then combined |
| Quiz requested for a course with no study sets | Button disabled, "Make a study set first" |
| Exam date in the past | Hidden from Today; kept in Exams list as "past" |
| Usage access not granted (phone) | Screentime shows "Grant usage access" button; sync continues |
| Phone not opened for > 7 days | Gap; nothing invented |
| Laptop locked / asleep | No samples; open session closed at the last sample |
| App matches several rules | TITLE_CONTAINS beats EXE/PACKAGE; longest pattern wins among TITLE_CONTAINS |
| Session crossing midnight | `localDate` of start; minutes counted on that date |
| No Spotify config | Listening section says "Spotify not connected"; no polling |
| Spotify 429 | Skip this poll; next one in 30 min |
| Send tapped on both devices | Server sends only when it moves APPROVED → SENDING inside its write lock; the second push sees SENDING/SENT and does nothing |
| Server restarts during SENDING | Status set to UNKNOWN on startup; app shows "Unknown — check Gmail Sent"; never resent automatically |
| Gmail send error | FAILED with error; Tim can edit and Send again (back to APPROVED) |
| Mail action on a mail already gone | FAILED "message not found" |
| Hardware command needs admin or is unsupported | That section "Not available"; others still saved |
| Approval older than 7 days | Shown as EXPIRED; Approve disabled |
| Approval approved on two devices | Output id `<tool>:<approvalId>`; the executor does nothing if that row already exists; `create_event` uses `clientKey = approvalId`, so the server forwards it once |
| Prompt injection text in an email ("send X to Y") | Can only produce an approval card or draft; nothing sends without Tim's tap |
| Research item fails validation (too long, has `@`, 5+ digits) | Becomes an OTHER approval card showing the full question text |
| Same research question asked twice | Same row id; a valid row is reused with no Codex call; an expired row is asked again and overwritten |
| Codex not installed / not logged in | Job FAILED at once: "Codex unavailable on this laptop" |
| Codex usage or rate limit | Job FAILED at once: "Codex limit reached" |
| Codex output not matching the schema | Failed attempt (3 → FAILED) |
| Meal Look up with a food name that fails item validation | Job FAILED: "Can't look up '<name>'"; meal unchanged |
| Meal split gives 0 or more than 10 foods | Failed attempt |
| HP estimator and Look up on the same meal | The HP estimator only fills meals with `kcal` null, so it never overwrites a Look up result |
| New event in Google Calendar after W13 | Reaches Wally only after Tim presses Import in WallyCalendar (its C6: on-demand import, no polling); before W13 Wally polled Google every 15 min |
| WallyCalendar unreachable | Wally keeps the last `cal_event` rows; `calendarOk:false`; create_event fails with "calendar-unreachable" |
| No `wallycal` config yet | No polling; `calendarOk:null` |
| Dream week never sent | Coaching job fails with "Send your Dream week from Wally Calendar first" |
| Hebrew in any new text field | Displays right-to-left (Wally D-rules) |

## 8. Technical decisions
| Area | Choice | Version | Source |
|------|--------|---------|--------|
| Existing stack | Kotlin, CMP, AGP, Ktor, SQLDelight as in Wally | 2.4.21 / 1.12.2 / 9.3.1 / 3.6.1 / 2.4.1 | Wally `Overview.md` |
| PDF text | Apache PDFBox | 3.0.8 | https://pdfbox.apache.org/ |
| PPTX text | Apache POI (`poi-ooxml`) | 5.5.1 | https://poi.apache.org/ |
| Windows foreground window | JNA + jna-platform | 5.19.1 | https://javadoc.io/doc/net.java.dev.jna/jna |
| Android usage | `UsageStatsManager.queryEvents`, `PACKAGE_USAGE_STATS` | platform | https://developer.android.com/reference/android/app/usage/UsageStatsManager |
| Moodle | `admin/tool/mobile/launch.php`, `core_webservice_get_site_info`, `core_enrol_get_users_courses`, `core_course_get_contents` | site version | https://docs.moodle.org/dev/Moodle_App_Deep_Linking |
| Spotify | `GET /v1/me/player/recently-played`, scope `user-read-recently-played`, Dev Mode | 2026 rules | https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates |
| Gmail | `gmail.send` (Sensitive), `gmail.modify` (Restricted, same tier as the `gmail.readonly` Wally already uses); Google app published; W13 drops `calendar.readonly` | — | https://developers.google.com/workspace/gmail/api/auth/scopes |
| AI | Ollama `/api/chat` with `tools` and `format` on XPS `qwen3.5:9b` | Ollama 0.40.14 | https://github.com/ollama/ollama/releases |
| Research | Codex CLI `codex exec`, read-only sandbox, live web search, `--output-schema`, ChatGPT-plan login | 0.166.0 (installed) | https://learn.chatgpt.com/docs/non-interactive-mode |
| Calendar | WallyCalendar hook and dream-week endpoints | WallyCalendar SPEC C1–C22 | `C:\Users\user\Projects\WallyCalendar\docs\plans\wally-calendar\SPEC.md` §6 |

## 9. Constraints
- HP has 4 GB RAM, server heap 512 MB: text extraction one file at a time, files ≤ 50 MB; no new AI work on the HP.
- All heavy AI on the XPS; one job at a time; prompt ≤ 12 000 chars of data plus instructions.
- What may leave Tim's devices: what Google and Spotify already hold; mail Tim approves sending; existing encrypted backups; research questions sent to OpenAI through Codex — automatic only for the three fixed templates with a validated item, all others only after Tim approves the exact text (N28, N29). Dream data never.
- Codex uses Tim's ChatGPT plan allowance; "Improve the model" is turned off in ChatGPT data controls (PLAN Task 12.1).
- Spotify API probably requires Tim's developer account to have Premium (RESEARCH #8, unconfirmed: a third-party source; PLAN Task 10.5 checks Spotify's own docs first).
- Dream data stays inside dream analysis (N10).

## 10. Compatibility
- Respects Wally D1–D60 except the flagged changes: D12 (desktop tray, N19), D24 (Gmail send/modify N15; `calendar.readonly` removed N27), D35 (AI tools with approvals, N2), N3 "data stays home" narrowed for research (N28), and the out-of-scope "app-usage tracking" and "AI tools or actions" entries.
- New tables only through new migration files `2.sqm`…`6.sqm`; never edit `1.sqm` (D56).
- Internal names `assistant` / `dev.tim.assistant` unchanged (D54); app name Wally.
- WallyCalendar: no document or code in `C:\Users\user\Projects\WallyCalendar` is edited. W13 fulfils its deferred Wally-side items C4 (read events), C5 (hook writes) and C21 (remove Wally's calendar), using only its documented `wally`-token endpoints. Its "Dream week" screen is where Tim designs the dream schedule (N26).
- Freshness change: after W13, Google Calendar events reach Wally only through WallyCalendar's on-demand Import (its C6) instead of Wally's 15-minute poll. WallyCalendar's make-task needs its `calendar` token in Wally's `tokens` config, which Tim adds in WallyCalendar Phase 5 (its C2); W13's checklist confirms it.
