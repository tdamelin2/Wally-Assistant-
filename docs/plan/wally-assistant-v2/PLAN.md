# PLAN — Wally Phase 14 edits + Phase 17 chat redo

Track: Large (2 phases). Project: `C:\Users\user\Projects\assistant`. Read `SPEC.md` and `DECISIONS.md` first.
Commands are PowerShell on the XPS. HP commands go through `ssh hp` per Overview rules (ask Tim for anything not on the allowed list).

---

# Phase 14 — redesign (existing Tasks 1–6) + fluid UX (new Tasks 14.7–14.11)

Existing Tasks 1–6: `docs/plans/wally-redesign/PLAN.md`, unchanged, done first.

## Task 14.7 — Swipe and Undo
- Files: new `shared/src/commonMain/kotlin/dev/tim/assistant/ui/SwipeRow.kt`; the list screens that show tasks (find with `Select-String -Path shared\src\commonMain\kotlin\dev\tim\assistant\ui\*.kt -Pattern 'LazyColumn'`); `App`-level `SnackbarHost`.
- Steps:
  1. `SwipeRow(onComplete, onDelete, content)` wraps Material 3 `SwipeToDismissBox`: StartToEnd = complete, EndToStart = delete. Rows without a "complete" meaning (meals, spending, journal) get delete only.
  2. Delete is held, not written: the row disappears from the list at once and a snackbar "Deleted — Undo" shows for 5 s; the delete is written (and synced) only when the snackbar expires or another action replaces it. Undo just shows the row again; nothing was written, so sync can't win against it. Complete works the same way (held 5 s, then written).
  3. One `SnackbarHostState` at the app root, passed down; a new snackbar replaces the old one (the old action is committed).
  4. Pure logic in `UndoBuffer` (holds one pending action, `undo()`, `commit()`), in the same file.
- Verify: `.\gradlew :shared:jvmTest --tests "*UndoBufferTest*"` (undo writes nothing; expiry writes the delete once; second action commits the first; a sync during the 5 s does not push the pending delete).
- Done when: the test passes and Tim's manual SC-1 check passes on both devices.

## Task 14.8 — Animations and haptics
- Files: `SwipeRow.kt`, list screens, `ShellScreen`/hub host from Task 2.
- Steps:
  1. List rows: `Modifier.animateItem()` on each `LazyColumn` item with a stable `key = row.id`.
  2. Check-off: checkbox scales 1→1.2→1 (`animateFloatAsState`, 150 ms) and the title gets a strikethrough fade before the row leaves.
  3. Hub and chip content: `AnimatedContent` with `fadeIn(150) togetherWith fadeOut(150)`.
  4. Haptics: `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` on complete and delete (no-op on desktop).
  5. Add a new `object Motion` holding all durations to `Theme.kt` (the file itself is created by redesign Task 1, which runs first).
- Verify: `.\gradlew build`; `Get-ChildItem shared\src\commonMain -Recurse -Filter *.kt | Select-String -Pattern '\bitems\(' -Context 0,2` lists every `items(` call with the next 2 lines; the executor inspects each hit and confirms `key =` appears in it (multi-line calls make a one-line filter unreliable).
- Done when: build passes and Tim's checklist line "animations feel smooth, no flicker" passes on the phone.

## Task 14.9 — Pull-to-refresh and sync label
- Files: `shared/.../ui/SyncBar.kt`, list hub screens.
- Steps: wrap each list hub in `PullToRefreshBox(isRefreshing, onRefresh = { scope.launch { syncEngine.sync() } })`. Extend the existing `syncBarText(status, nowMs)` in `SyncBar.kt` so under 1 min it reads `Synced just now`; keep its other texts.
- Verify: `.\gradlew :shared:jvmTest --tests "*SyncBarTest*"` (add the test file if missing: <1 min → "Synced just now", 5 min → existing minutes text, Offline → pending count).
- Done when: test passes; pulling down on the phone syncs.

## Task 14.10 — Auto-sync
- Files: `shared/.../data/SyncEngine.kt`; `androidApp/.../SyncWorker.kt` (new); `androidApp/build.gradle.kts` (add `androidx.work:work-runtime-ktx:2.12.0` — new dependency, see DECISIONS change table); app entry points (Android `MainActivity`, desktop `desktopApp/src/main/kotlin/main.kt`).
- Steps:
  1. `SyncEngine.startForegroundTimer(scope)`: loop `delay(10.minutes); sync()` while the app is in the foreground; cancelled on stop (Android `Lifecycle` ON_STOP; desktop runs while the window is open).
  2. Android: `PeriodicWorkRequestBuilder<SyncWorker>(15, MINUTES)` with `NetworkType.CONNECTED`, enqueued as unique work `wally-sync` with `KEEP`.
  3. `SyncWorker.doWork()` runs one sync (Phase 17 adds notification handling here). The worker can't use MainActivity's `AppServices`, so the app keeps **one `AppServices` per process**: a lazy holder on the Android `Application` class (`WallyApp`, new or existing) that both `MainActivity` and `SyncWorker` use (`AppServices` is in `shared/src/commonMain/kotlin/dev/tim/assistant/ui/AppServices.kt`). That way there is one `SyncEngine` and its `syncLock` stops two syncs running at once. The worker logs `Log.i("SyncWorker", "sync done at <time>")` after each run (used by SC-3).
- Verify: `.\gradlew :shared:jvmTest --tests "*SyncTimerTest*"` (virtual time: syncs at 10 and 20 min, stops when cancelled); `adb shell dumpsys jobscheduler | Select-String dev.tim.assistant` shows the job.
- Done when: SC-2 and SC-3 pass manually.

## Task 14.11 — Date and time pickers, MM-dd-yyyy on laptop
- Files: new `shared/.../ui/DateField.kt`; `expect val usesLaptopDates: Boolean` (true in `jvmMain`, false in `androidMain`); every screen with a date or time `TextField` (find with `Select-String -Pattern 'localDate|"date"|YYYY|yyyy'` in `shared/src/commonMain/.../ui`).
- Steps:
  1. `DateField(value: LocalDate?, onChange)`: read-only text + calendar icon opening Material 3 `DatePickerDialog`. On the laptop the text is editable and parsed with a kotlinx-datetime `LocalDate.Format { monthNumber(); char('-'); day(); char('-'); year() }` (check the day function name in Wally's kotlinx-datetime version; older name `dayOfMonth()`).
  2. `TimeField` the same with `TimePicker` (24-h on both).
  3. `formatDate(date)` used for every displayed date: MM-dd-yyyy on the laptop; on Android the phone's medium locale format.
  4. Storage format is unchanged (ISO `yyyy-MM-dd`).
- Verify: `.\gradlew :shared:jvmTest --tests "*DateFormatTest*"` (round-trip `11-10-2026`; rejects `13-01-2026` and `2026-11-10`); `.\gradlew build`.
- Done when: SC-4 holds manually.

## Task 14.12 — Phase 14 release additions
- Add to `ops/checklists/redesign.md` (created by redesign Task 5; if it's missing, create it): SC-1…SC-4 lines and "animations smooth". Overview: replace the sync sentence with "syncs on open, after each save, every 10 min while open, every 15 min in the background (phone), and on pull-down".
- Also update the Phase 14 Notes in `phases.md`: "No new dependencies" → "One new dependency: androidx.work 2.12.0 (Task 14.10)". Overview: remove "Android background workers" from Out of scope (N19 change).
- Verify: `.\gradlew check`; checklist passes on both devices, light and dark.
- Done when: both pass and Tim approves the Overview/phases.md edits.

**Phase 14 gate:** `.\gradlew check` passes and `ops/checklists/redesign.md` passes on phone and laptop. Phase 14 needs nothing from Phase 17.

---

# Phase 17 — chat redo on the HP

## Task 17.1 — HP RAM, model choice, Codex install (test first)
- Files: new `server/src/test/resources/model-eval.jsonl`, `scripts/model-eval.py`, `reports/phase-17-model.md`; HP config file read by `server/.../Config.kt` (`briefingModel`).
- Steps (**Tim runs** every `ssh hp` command here; none are on Overview's allowed list):
  1. After the RAM upgrade: `ssh hp free -h` shows ~8 GB.
  2. `ssh hp ollama pull qwen3:4b`; `ssh hp ollama pull llama3.2:3b`.
  3. `model-eval.jsonl`: 20 prompts — 8 tool calls with expected tool name + required args, 6 name-resolution, 6 plain replies checked against the persona (no IDs, 1–4 sentences, mentions a data value given in the prompt). `model-eval.py` (Python 3 standard library only; copied with `scp` and run by Tim as `ssh hp python3 model-eval.py <model>`) sends each to `http://127.0.0.1:11434/api/chat` on the HP with Wally's tool list and `"think": false`, and scores exact tool name + required args, and the persona checks.
  4. Timing: one full tool round for "what's on today?" with a real-size context (today summary + 20 messages + 10 memory notes, ~3,000 tokens): model call → `get_tasks` result → final answer. Record total seconds for the whole round.
  5. Keep the higher score, tie → qwen3:4b. Set the HP config `briefingModel` to the chosen model and redeploy the server, then remove `qwen3:1.7b`. Set `OLLAMA_KEEP_ALIVE=30m`, `OLLAMA_CONTEXT_LENGTH=8192` in the HP Ollama service override.
  6. Install Codex CLI 0.167.0 on the HP the same way Phase 13 installed it on the XPS (npm `@openai/codex@0.167.0`, installing Node LTS first if missing); the server runs as `User=assistant` (`ops/assistant.service`), so log in as that user: `sudo -u assistant codex login --device-auth` (otherwise the service's Codex home has no login). Record `which codex` for the config's `codexPath`. Confirm `codex exec --help` lists the flags `CodexResearch` uses.
  7. On the XPS, `ollama list` confirms the chat model name used by the existing `CHAT_MODEL` constant (`qwen3.5:9b`); `Brain` reads that same constant.
- Verify: `reports/phase-17-model.md` has both scores, generation and prompt-reading tokens/s, the round timing, free RAM (`free -h`) while the server, Ollama (8k context) and a Codex run are all active, the chosen model, the XPS model check and the Codex check; `ssh hp ollama list` shows only the chosen model; the briefing is run once on demand right after the switch (with the server's existing manual trigger if it has one; otherwise Tim temporarily sets the briefing time 2 minutes ahead in the HP config and restarts) and its result is recorded in `phase-17-model.md`.
- Done when: the chosen model scores ≥ 14/20 **and** the timing is ≤ 30 s. If either fails, or free RAM is under 500 MB while the server, Ollama and a Codex run are all active, stop and ask Tim (options: smaller context, llama3.2:3b, or XPS-only for tool calls).

## Task 17.2 — Chat on the server's existing database, and the brain
- Files: `server/.../chat/Brain.kt`, `server/.../chat/ServerWriter.kt` (new); `server/.../Main.kt`; `server/.../ai/OllamaClient.kt`.
- Steps:
  1. The server already opens the full shared `AssistantDb` (`Db.kt`). **Reads:** `ReadTools` is built on `Repository(LocalStore(serverDb))` (`LocalStore` is just `class LocalStore(val db)`, so this works on the server); use it only for reads. **Writes:** never through `Repository`/`LocalStore` (those write `syncState = PENDING` rows no pull ever sees). Every chat write — approval rows, auto adds, undo, research cache — goes through `ServerWriter.write(row)`, which calls `SyncStore.writeIfNewer` inside `sdb.writeLock.withLock { db.transaction { … } }` exactly like `BriefingService.kt:95`, so the row gets a seq and clients receive it at the next pull.
  2. `ChatClient` (`shared/.../ai/ChatClient.kt`) hard-codes `put("model", CHAT_MODEL)` in its three request builders. Add `model: String = CHAT_MODEL` and `numCtx: Int = CHAT_NUM_CTX` parameters to `chatTurn`, `complete` and `send`, and pass them through `ToolChatLoop`. A per-request `num_ctx` overrides `OLLAMA_CONTEXT_LENGTH`, so this matters: `Brain.pick()` returns `(baseUrl, model, numCtx)` = XPS `qwen3.5:9b` with 16384, or the HP model with 8192 (the size 17.1 measures).
  2b. Server wiring in `Main.kt`: move `generationLock` out of the `if (ollamaConfig != null)` block so the chat routes can receive it; add `ollama.xpsUrl` to `Config.kt` and the HP config file (the XPS Tailscale address, port 11434).
  2c. `trimHistory` cuts chat history to 2,000 characters. On the server, replace it with "last 20 messages, trimmed to 6,000 characters total, oldest dropped first" (a `maxChars` parameter on `trimHistory`).
  3. `Brain.pick()`: GET `http://<XPS tailscale>:11434/api/tags` with a 2-s timeout → XPS `qwen3.5:9b`; else the HP model. HP chat runs `withLock` on the existing `generationLock` from `Main.kt` (they wait). Briefing and nutrition use `tryLock()` and skip when it's busy, so a long chat only moves the briefing to its next scheduler tick; that is accepted. Summarize, idea and report jobs (17.3, 17.7) also `tryLock()` and retry next tick. XPS runs don't take the lock.
- Verify: `.\gradlew :server:test --tests "*ServerWriterTest*" --tests "*BrainTest*"` (`ServerWriter.write(approvalRow)` and `ServerWriter.write(taskRow)` each appear in a client pull; XPS timeout → HP and the request body sent to the HP carries the HP model name (not `qwen3.5:9b`) and `num_ctx` 8192; with chat holding the lock, the briefing's `tryLock` fails and it runs on the next tick).
- Done when: tests pass.

## Task 17.3 — Chats, messages, memory (server)
- Files: `server/.../chat/ChatStore.kt`, `ChatRoutes.kt`; new `shared/src/commonMain/sqldelight/dev/tim/assistant/db/chat.sq` (tables `chat`, `chat_message`, `memory_note`, `outbox_notification`) and a migration with the next free number after the latest `.sqm` in `shared/src/commonMain/sqldelight/migrations/` at build time (the server applies it through `prepareSchema` in `Db.kt`). They live in the shared schema because the server uses it; the tables stay empty on clients (never synced). Update `SchemaMigrationTest`.
- Endpoints (bearer token as today): `GET /v1/chat/list`, `POST /v1/chat` (new), `GET /v1/chat/{id}`, `POST /v1/chat/{id}/message` (returns the final reply as one JSON response; `ToolChatLoop` is non-streaming, so no streaming in this phase — the client shows a typing indicator while waiting), `DELETE /v1/chat/{id}`, `POST /v1/chat/{id}/undo/{actionId}`, `GET /v1/memory`. (Card yes/no is not a chat endpoint: it is `POST /v1/approvals/{id}`, built in Task 17.4.)
- Steps: creating chat 11 archives the chat with the oldest `last_used_at`. Archive/delete = enqueue a `summarize` job that runs through `Brain.pick()` (XPS first; on the HP it `tryLock`s `generationLock` and retries every 30 s) and writes a 3–6 sentence `memory_note` then deletes the chat. Title = first 6 words of the first message. The "Weekly report" chat (17.7) is pinned: it doesn't count toward the 10 and is never archived; each week's report is appended to it. Tapping an `idea` notification opens a new chat that starts with the idea as Wally's first message.
- Verify: `.\gradlew :server:test --tests "*ChatStoreTest*" --tests "*ChatRoutesTest*"` (10 max; 11th archives LRU; delete → note created → chat gone; unknown token 401).
- Done when: tests pass.

## Task 17.4 — Tools, name resolution, approvals
- Files: `shared/.../ai/tools/Tools.kt` (add `mode: auto|card` to each WRITE tool; add the new tools below), `shared/.../ai/tools/ResolveTool.kt` (new), `server/.../chat/ChatRunner.kt` (new; builds the `ToolBox` with server reads and runs `ToolChatLoop`), `server/.../chat/ServerToolExecutor.kt` (new).
- **Tool list (exact).** Before editing, run `Select-String -Path shared\src\commonMain\kotlin\dev\tim\assistant\ai\tools\Tools.kt -Pattern 'ToolKind\.'` and confirm every existing tool appears below; if one doesn't, stop and ask Tim. Read tools stay as they are: `get_tasks`, `get_goals`, `get_exams`, `get_events`, `get_log`, `get_study`, `get_screentime`, `get_listening`, `get_research`, `get_unread_mail`, `get_hw_checks`, `search_docs`, `read_doc`. Write tools and their mode:

  | Tool | Mode | Status |
  |------|------|--------|
  | `create_task`, `add_checkin`, `add_meal`, `add_spending`, `add_journal` | auto | existing |
  | `add_dream` | auto | **new** (only offered when the message mentions dreams, A18) |
  | `update_task`, `update_checkin`, `update_meal`, `update_spending`, `update_journal`, `create_goal`, `update_goal`, `add_exam`, `update_exam`, `create_doc`, `update_doc`, `create_event` | card | existing |
  | `set_app_rule` (screentime classification) | card | existing |
  | `queue_job` (STUDY_GUIDE / QUIZ and its other existing kinds) | card | existing, kept; now also accepts a course/doc **name** resolved through `find` |
  | `suggest_mail_action` (label/archive) | card | existing, kept; accepts a mail resolved through `find` |
  | `draft_email` | card | existing, kept: it only saves a draft; chat has no tool that sends |
  | `research` (`ToolKind.RESEARCH`: NUTRITION, DEFINITION, APP_CATEGORY templates) | auto | existing (A3/N29) |
  | `research_question` (free text) | card | existing (A3/N29) |
  | `find(kind?, query)` | read | **new** |

  No `make_study_guide`, `make_quiz`, `mail_label` or `mail_archive` tools: `queue_job` and `suggest_mail_action` already do this. Hardware stays read-only (`get_hw_checks`): the checks run on the laptop (JNA). No delete tool and no send tool exist; Settings and Files have no tools.
- **Mail actions from the server (behaviour change, in DECISIONS):** today an approved suggestion is stored as `SUGGESTED` and Tim acts on it later in the Mail tab. From now on, saying yes in chat or Approvals *is* Tim's decision, so the action runs at once. Update the `suggest_mail_action` tool description from "Only a suggestion; Tim decides later" to "Tim approves on the card; the label/archive then happens in Gmail". When an approved `suggest_mail_action` runs, the `mail_action` row is written through `ServerWriter` with its real columns (`gmailId, action, labelId, labelName, reason, status = "APPROVED"`), then `gmailWake` is called (as `SyncStore`'s push path does at about line 91, after its APPROVED check), so the Gmail worker runs it at once.
- **Auto path:** `ServerToolExecutor.runAuto(tool, args)` validates with the existing `Tools.validate`, writes the row through `ServerWriter`, saves `(actionId → rowId, table)` in the chat message's `card_json`, and returns `actionId`.
- **Card path — only the server executes approvals.** A `card` tool writes a normal `approval` row through `ServerWriter` (so Files > Approvals still lists it). Refactor the existing `ApprovalExecutor` to take a small `ApprovalSink` interface (write row, create event, wake Gmail) instead of `Repository` + `SyncClient`, keeping its existing logic (`eventTimes`, `approvalReceiptKey`, `approvalOutputId`, `approvalUsesReceipt`). Today `apply()`/`applyUpdate()` write straight to the database through `repo.runLocalApproval(id, t, effect: (AssistantDb) -> String)`, producing `PENDING` rows with no seq, which no pull would ever see. In the refactor, every effect returns `SyncRow`s instead: the target row, the approval status row (DONE / FAILED / ALREADY_DONE), and the receipt marker (today a kv key; on the server written as part of the approval row or a synced row, so it survives restarts). The server sink writes them all through `ServerWriter` in one `writeLock` transaction; `approveResearch` becomes a sink method that calls `ServerCodex` (17.6). For `create_event` the server calls the duplicate-safe `createEvent(clientKey, body)` service function used by `WallyCalRoutes.kt` (the `calcreate:<clientKey>` replay), with `clientKey = approvalId`. The sink also has `labelName(labelId)`, implemented with the server's Gmail labels, which `approveMailAction` uses today through `client.mailLabels` to fill `labelName` for LABEL actions. `POST /v1/approvals/{id}` (`yes`/`no`) runs it; the chat card and the Files > Approvals buttons both call this endpoint, and the clients no longer execute approvals locally. Approving needs a connection to the HP (same as chat, A19). An approval already done or rejected returns 409 and does nothing.
- Study guide/quiz jobs still run on the XPS job runner. With the laptop off, chat says "Queued — I'll make it when your laptop is on" and the job waits; SC-6 is only about finding the course without an ID.
- Steps:
  1. Add `mode` to every write tool and add `add_dream` and `find` to `Tools.kt` per the table.
  1a. **Open up `ToolChatLoop` for the server.** Today `ToolChatLoop.ask` builds its own system prompt (`ChatContext.toolSystemPrompt`, which says a write tool "only proposes… never say saved") and always sends `Tools.ollamaTools()`. Add parameters `systemPrompt: String` and `tools: List<…>` (defaults = today's values, so clients are unchanged). The server passes the persona prompt (17.5), which says auto actions are saved, and passes a tool list without `add_dream` unless `mentionsDreams(message)`. Add `actionId: String?` and `choices: List<Choice>?` to `ToolResult`, and collect them into `ToolChatOutcome` so the route can return them.
  1b. **Route writes away from `Repository` on the server.** Today `ToolBox(read, write: WriteTools, research)` sends every WRITE call to `WriteTools.propose`, which calls `repo.createApproval` (a client-only PENDING row). Change `ToolBox` to take a `WriteHandler` interface (`suspend fun handle(name, args): ToolResult`). Clients keep `WriteTools` as their handler; the server uses `ServerToolExecutor`, which validates referenced ids against the server DB (the checks `WriteTools` does via `repo.goalById` and similar), then takes the auto or card path.
  2. `find(kind?, query)`: case-insensitive contains + token match over names of courses, study docs, goals, tasks, files, events; returns up to 5 `{kind, id, name}`. The chat message carries `screen` (current NavTab), added to the prompt as "Tim is looking at: <screen>".
  3. `queue_job` and `suggest_mail_action` take a name or a `find` result; when `find` returns more than 1, the reply includes a `choices` card (chips); tapping sends the chosen id back as the next message. IDs never appear in reply text.
  4. Auto writes return an `actionId`; the client shows Undo for 30 s (longer than the 5-s list snackbar on purpose: you're reading the reply first). Undo writes the same `SyncRow` with its `deleted` column set to 1 and a new `updatedAt` through `ServerWriter`, so it wins over the insert at sync.
  5. Write `ServerToolExecutor` (auto path), the `ApprovalSink` refactor + `/v1/approvals/{id}` route (card path), and `ChatRunner`. Point the Files > Approvals buttons at the route.
- Verify: `.\gradlew :shared:jvmTest --tests "*ResolveToolTest*"` and `.\gradlew :server:test --tests "*ToolPolicyTest*" --tests "*ServerToolExecutorTest*"`. ToolPolicyTest checks the exact write-tool list and modes above, no delete/send tool, no Settings/Files tool. ServerToolExecutorTest (every case goes in through `ToolBox.call`): auto `create_task` row appears in a client pull; undo removes it in a later pull; `yes` on an approval writes the target row; `create_event` calls a fake WallyCal client; a second `yes` returns 409 and writes nothing; an approved `suggest_mail_action` writes an `APPROVED` mail_action row and calls `gmailWake`; `research` with a template runs without an approval and `research_question` creates one; an approved `update_task` appears in a client pull, and so does the approval's DONE status; one `find` match → no chips; two → chips. The existing approval tests (`ApprovalTest.kt` under `shared/src/*Test*/`; find it with `Get-ChildItem shared\src -Recurse -Filter ApprovalTest.kt`) are ported to a fake `ApprovalSink` with the same cases, and pass: `.\gradlew :shared:jvmTest --tests "*ApprovalTest*"`. An undo test: after undo, the pulled `SyncRow` for that task has `deleted = 1`.
- Done when: tests pass.

## Task 17.5 — Personality and context
- Files: `server/src/main/resources/wally-persona.md` (system prompt); `shared/src/commonMain/kotlin/dev/tim/assistant/ai/ChatContext.kt`. The server builds context with this shared class over its `AssistantDb` (`ChatContext.build(db, …)` already takes that DB); `ChatRunner` adds the persona, chat messages and memory notes.
- Steps: persona text per A10 (name, warm, 1–4 sentences, use concrete data, ask one question when unsure, never list raw IDs). Context = today's summary (existing `ChatContext`), last 20 messages of the chat, the 10 newest `memory_note` summaries. Dream rows added only if `mentionsDreams(message)` (words: dream, dreams, dreamt, dreamed, nightmare, חלום, חלומות).
- Verify: `.\gradlew :shared:jvmTest --tests "*ChatContextTest*" --tests "*DreamWallTest*"` and `.\gradlew :server:test --tests "*DreamWallServerTest*"` — DreamWallTest rewritten: no dream text without a dream word; dream text present with one. DreamWallServerTest (existing) extended: server chat prompt follows the same rule; report and Codex inputs never contain dream text.
- Done when: tests pass.

## Task 17.6 — Codex on the HP
- Files: new `server/.../research/ServerCodex.kt`, which uses the existing `shared/src/jvmMain/.../research/CodexResearch.kt` (the server depends on `:shared`) and writes its output through `ServerWriter`, never `Repository`; new `server/.../research/ServerMealLookup.kt`.
- How research runs today: `desktopApp/src/main/kotlin/main.kt` (lines ~106–120) registers two XPS job handlers, `ResearchJobs(services.repo, codex, clock)` and `MealLookupJob(services.repo, model, codex, clock)`; requests go through `ResearchService.request()`/`queueApproved()` (`Research.kt`), which create `ai_job` rows of kind `RESEARCH_JOB_KIND` via `Repository`.
- **Codex must be found on Linux.** `findCodexExecutable()` in `CodexResearch.kt` only looks for Windows names (`codex.cmd`, `codex.exe`, the `codex-win32-x64` npm path), so on Debian it returns null and every job fails. `ServerCodex` constructs `CodexResearch` with `findExecutable = { config.codexPath ?: "/usr/bin/codex" }` (new `codexPath` in `Config.kt`, set from 17.1's `which codex`).
- **Pickup tick:** add `Scheduler("research", …)` in `Main.kt` that every 30 s claims synced `RESEARCH` and meal-lookup `ai_job` rows. The laptop's `resetStaleJobs` may still re-queue a server-owned job it sees as RUNNING too long; that is harmless (the server claims it again with `writeIfNewer`, so it runs once).
- New path: `ServerCodex` runs template `research` calls directly and runs approved `research_question` calls from the 17.4 sink. It also claims any `RESEARCH` `ai_job` rows that arrive by sync (queued by a client before the update), using `writeIfNewer` on the job row so a job runs once. Everything it writes (job status, `research` cache row) goes through `ServerWriter`; it never calls `ResearchService` on the server.
- Meal lookup moves too (A3: Codex leaves the XPS): `ServerMealLookup` does what `MealLookupJob` does, using the HP model via `Brain.pick()` and `ServerCodex`, and claims meal-lookup `ai_job` rows the same way. When a lookup fails it sets that job to FAILED through `ServerWriter`, so the Phase 15 `mealStatus` rule (Look up only on failed meals, redesign v2 U16) still sees the failure on both devices. Add this case to `ServerCodexTest`.
- **Stop the laptop from failing server jobs.** `JobRunner.tick()` (`shared/.../ai/jobs/JobRunner.kt`) claims the oldest QUEUED job of any kind (`ai_job.sq` `selectOldestQueued` has no kind filter) and fails kinds it has no handler for. Add `selectOldestQueuedExcept` to `ai_job.sq` and make `JobRunner` skip the server-owned kinds in `CODEX_JOB_KINDS` (`ApprovalLogic.kt`, plus the meal-lookup kind if it isn't in that set). Add to `JobRunnerTest`: a RESEARCH job and a meal-lookup job stay QUEUED on the client.
- `ServerMealLookup` uses the server's `OllamaClient` with the model from `Brain.pick()`, and on the HP `tryLock`s `generationLock` like `NutritionEstimator` (retry next tick if busy).
- Remove from `desktopApp/src/main/kotlin/main.kt`: the `ResearchJobs` and `MealLookupJob` registrations and the `codex` construction. Remove the client `ApprovalExecutor.approveResearch` (replaced by the server sink method from 17.4). Clients still create research `ai_job` rows through `ResearchService` as today; the server picks them up.
- Steps: same templates and approval rules (N28–N31). At most one Codex process at a time (its own `Mutex`, separate from `generationLock` because Codex uses no local RAM-heavy model). Output cached in the existing `research` table through `ServerWriter`. Dream text never in the Codex input (checked in DreamWallTest).
- Verify: `.\gradlew :shared:jvmTest --tests "*CodexResearchTest*"` and `.\gradlew :server:test --tests "*ServerCodexTest*"` (fake process: template runs auto, others create an approval; second request waits for the first; a template `research` call from chat shows its `research` row in a client pull; a `RESEARCH` `ai_job` pushed by a client is run once and its DONE status reaches a client pull; a meal-lookup job is run by `ServerMealLookup`; `ServerCodex` passes the configured `codexPath`, not the Windows lookup). Plus a real smoke run by Tim on the HP: one template research request from the phone produces a `research` row (`journalctl -u assistant` shows the `codex exec` run); manual SC-10.
- Done when: both pass.

## Task 17.7 — Notifications
- Files: `server/.../notify/NotifyScheduler.kt` (named so it isn't confused with `google/Scheduler.kt`), `NotifyRoutes.kt` (`GET /v1/notifications?since=`, `GET`/`PUT /v1/notify-settings`); `androidApp/.../SyncWorker.kt`, `Notifier.kt`, `ReminderReceiver.kt`; `androidApp/src/main/AndroidManifest.xml` (add the `POST_NOTIFICATIONS` permission and the `ReminderReceiver` entry); Browse › Settings card "Reminders" (quiet hours, reminder types; redesign v2 U21) on both devices: phone `BrowseSettings.dc.html`, laptop `LaptopSettings.dc.html` (the fourth card, next to Sync, Connections and Laptop only, which Phase 16 built). The laptop can edit these settings because they live on the server; it still shows no notifications.
- Fetch details: the phone stores the last fetched outbox id in its local `kv` (`notify.cursor`) and calls `/v1/notifications?since=<id>`; the server sets `delivered_at` when it returns a row. Every notification gets a `chat_id`: check-in reminder and morning list → a new chat started by Wally with that text; heads-up → a new chat about that item; idea → a new chat (17.3); weekly report → the pinned report chat.
- Settings storage: check-in time (default 21:00) and quiet start/end (23:00/07:00) are stored on the server in its `kv` table (keys `notify.checkinTime`, `notify.quietStart`, `notify.quietEnd`). The app's Settings section reads and writes them with `GET`/`PUT /v1/notify-settings`; the local `kv` table is not used for these.
- Steps:
  1. Server scheduler (every minute): check-in reminder if no check-in today at the set time; 07:30 due list; 30-min heads-up per timed task/exam/event; up to 2 `idea` messages/day at 12:00 and 17:00 generated by the model from the last 7 days (screentime classification question when unclassified apps exist); weekly report Sunday 19:00 into a "Weekly report" chat (dream-free).
  2. Rules in one pure function `allowed(kind, at, sentToday)`: quiet hours block; cap 6/day. Everything with a known time (check-in reminder, 07:30 list, heads-ups) is queued at least 1 hour ahead with a future `fire_at`, so the phone has it before it's due and schedules it locally. The check-in reminder is cancelled if a check-in exists at fire time: `ReminderReceiver` first runs one sync (with a 10-s timeout, using `goAsync()`), then checks the phone's DB, so a check-in made on the laptop counts. If the sync fails, it decides from the local DB.
  3. The phone's decision is a pure function in `shared/.../notify/NotifyPlan.kt`: `plan(row, now, checkinDoneToday) → ShowNow | ScheduleAt(t) | Skip` (Skip for a check-in reminder when today's check-in exists, or a row older than 24 h). Tested by `.\gradlew :shared:jvmTest --tests "*NotifyPlanTest*"`.
  The laptop never calls `/v1/notifications` and has no notifier; notifications are phone-only.
  4. Phone: create notification channel `wally` ("Wally reminders", default importance) at app start. After each sync fetch new outbox rows; `fire_at <= now` → show now; future → `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, fire_at, pendingIntent)` (works in Doze, needs no exact-alarm permission; Android may shift it by a few minutes) to a `BroadcastReceiver` that shows it. Request `POST_NOTIFICATIONS` once at first launch after the update. Tap opens the chat id.
- Verify: `.\gradlew :shared:jvmTest --tests "*NotifyPlanTest*"` and `.\gradlew :server:test --tests "*NotifySchedulerTest*" --tests "*NotifyRulesTest*" --tests "*DreamWallServerTest*"` (quiet hours, cap 6, no duplicate per day, queued ≥ 1 h ahead, Sunday report, report has no dream text).
- Done when: tests pass and SC-9 holds over one day.

## Task 17.8 — Client chat UI
- Files:
  - Rewritten: `shared/.../ui/ChatScreen.kt`, `shared/.../ai/ChatController.kt`, `shared/.../ai/ChatClient.kt`.
  - Deleted: `shared/.../ai/ChatHistory.kt` and `ChatHistoryTest.kt`.
  - Memory screen inside the Chat hub, opened from the chat list's "Memory" button (no new NavTab, no Browse chip; redesign v2 U20). Laptop: the chat list column with a hide toggle.
  - Design source: canvas "Wally App Redesign" (https://claude.ai/artifact/RqdTz93wzV9CnRj6kBB4qP; exported copy in `docs/plan/wally-redesign-v2/canvas/`: `ChatMain`, `ChatCard`, `ChatList`, `ChatMemory`, `LaptopChat` `.dc.html`), Phone row 8 (conversation, yes/no card + offline, chat list, Memory) and Laptop row 3 (Laptop · Chat).
- Steps: chat list drawer (up to 10, swipe-to-delete with confirm text "Wally will remember a summary"); typing indicator while waiting for the reply; choice chips; card yes/no; Undo chip on auto actions; offline banner "Can't reach Wally right now. Your message will send when you're back online."; a message sent while offline is stored in the local send queue (`kv` key `chat.outbox`, JSON list of {chatId, text, createdAt}), shown greyed with a clock icon, and sent in order after the next successful sync (A26); remove the direct XPS call and per-device history (`ChatHistory.kt` deleted, its test replaced). Chat list (phone drawer, laptop column): "N of 10", pinned Weekly report, chats with last message and time, Memory button, New chat (phone: at the bottom of the drawer, plus the New chat icon in the conversation header; laptop: "+ New chat" at the top of the list, as in `LaptopChat`). The Memory screen shows `/v1/memory` summaries as cards (chat name, archive date, 3 lines, tap for full text; "Summarizing…" while a summary is pending). Laptop: the list is always visible with a hide toggle (icon + Ctrl+B). Ctrl+B goes through the Phase 16 shortcut flow: add `Shortcut.ToggleChatList` to `shortcutFor` (Ctrl+B, also while typing) and a `ShortcutTest` case. Enter sends, Shift+Enter adds a line: the composer handles this itself with `onPreviewKeyEvent` on its own text field. This is the one deliberate exception to redesign v2 SC-14 ("Enter adds a new line in multi-line fields"); do not change `shortcutFor`'s Enter rule or its existing `ShortcutTest` cases, so every other multi-line field keeps Enter = new line. The composer uses the Phase 16 `WallyTextField` wrapper, so N typed into it is text, not a shortcut. Phone: Undo shows its 30 s countdown; the composer sits right above the bottom bar.
- Verify: `.\gradlew :shared:jvmTest --tests "*ChatControllerTest*" --tests "*ShortcutTest*" --tests "*ShellLogicTest*" --tests "*LabelUniquenessTest*"`; `.\gradlew build`.
- Done when: tests pass; SC-5…SC-8 pass manually; Tim confirms the phone and laptop chat screens match `ChatMain`, `ChatCard`, `ChatList`, `ChatMemory` and `LaptopChat` (`.dc.html` in `docs/plan/wally-redesign-v2/canvas/`): layout, spacing, colours and wording; the Settings "Reminders" card matches `BrowseSettings.dc.html` (phone) and `LaptopSettings.dc.html` (laptop).

## Task 17.9 — Docs, report
- Files: `Overview.md`, `reports/phase-17.md`.
- Update `Overview.md` per the "Changes to locked decisions" table in DECISIONS.md (after Tim approves), including removing "saved chat history", "AI writes without an approval card" from Out of scope, updating the Tech stack (Codex 0.167.0, HP model, androidx.work), and adding to the Ollama constraint: "the HP server also calls the XPS Ollama over Tailscale for chat".
- **Earlier acceptance tests.** `tests/acceptance` is part of the build (`settings.gradle.kts`), so `.\gradlew check` runs it. Update, don't delete: `Phase13AcceptanceTest.kt` (schema version and table count asserts, about lines 1007/1021/1053 → the new version and +4 tables; `ApprovalExecutor(repo, client, …)` constructions, about lines 142/269/945 → the `ApprovalSink` form), `Phase13TimChange*` (about line 60, same constructor change), `Phase13Step3dAcceptanceTest` (old `ChatController(…, ChatAnswerer, load, save)` → the new controller; cases about per-device history are retired, with a one-line note in the file). The change for each file goes in the task that causes it: schema in 17.3, `ApprovalSink` in 17.4, `ChatController` in 17.8. Before each of those tasks, run `Select-String -Path tests\acceptance -Recurse -Pattern 'ApprovalExecutor\(|ChatController\(|tables|schemaVersion'` to find any others.
- Write `reports/phase-17.md` marking SC-5…SC-12, including Tim's persona check (SC-12) on 5 real chats.
- Verify: `.\gradlew check`.
- Done when: check passes, the report marks every SC, and Tim approves the Overview edits.

**Phase 17 gate:** `.\gradlew check` passes and `reports/phase-17.md` marks SC-5…SC-12 pass.

## Planning-side note
Wally Voice now follows this phase as Phases 18–19 (renumbered 2026-11-11 with the redesign v2 phases 15–16; see `docs/plans/wally-redesign-v2/` U4).

## Coverage
| SC | Task(s) |
|----|---------|
| SC-1 | 14.7 |
| SC-2, SC-3 | 14.10 |
| SC-4 | 14.11 |
| SC-5 | 17.1 (timing), 17.2, 17.8 |
| SC-6 | 17.4, 17.8 |
| SC-7 | 17.4, 17.8 |
| SC-8 | 17.3, 17.8 |
| SC-9 | 17.7 |
| SC-10 | 17.1, 17.6 |
| SC-11 | 17.5, 17.6, 17.7 (reports) |
| SC-12 | 17.1 (persona prompts), 17.5, 17.9 |

---

## Execution checklist
- [ ] Phase 14 Tasks 1–6 (redesign PLAN) verified
- [ ] 14.7 UndoBufferTest + SC-1
- [ ] 14.8 build + key check + smooth-animation check
- [ ] 14.9 SyncBarTest + pull-to-refresh on phone
- [ ] 14.10 SyncTimerTest + jobscheduler + SC-2, SC-3
- [ ] 14.11 DateFormatTest + build + SC-4
- [ ] 14.12 check + checklist both devices, both themes + Overview/phases.md edits approved
- [ ] Phase 14 gate
- [ ] 17.1 model report, score ≥ 14/20, timing ≤ 30 s, briefingModel switched, Codex on HP
- [ ] 17.2 ServerWriterTest + BrainTest
- [ ] 17.3 ChatStoreTest + ChatRoutesTest
- [ ] 17.4 ResolveToolTest + ToolPolicyTest + ServerToolExecutorTest + ApprovalTest (ported)
- [ ] 17.5 ChatContextTest + DreamWallTest + DreamWallServerTest
- [ ] 17.6 CodexResearchTest + ServerCodexTest + JobRunnerTest + SC-10
- [ ] 17.7 NotifyPlanTest + NotifySchedulerTest + NotifyRulesTest + DreamWallServerTest + SC-9
- [ ] 17.8 chat tests + build + SC-5…SC-8
- [ ] 17.9 check + phase-17 report + Overview approved
- [ ] Phase 17 gate
- [ ] Planning-side: Wally Voice renumbered to 16–17
