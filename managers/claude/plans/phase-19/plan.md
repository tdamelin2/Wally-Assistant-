# Phase 19: Phone brain | Claude plan
Status: Done | Started: 2026-11-13 12:39 | Finished: 2026-11-13 12:43
Helpers: 2 coders - server (rung 4, PHONE_PENDING, phone-result, tick) and apps (LiteRT-LM engine, ChatController flow, Settings card) are structurally separate with the shared DTO contract between them; matches PLAN "Coders: 2".

## Summary
Follow wally-brain PLAN Tasks 19.1-19.8 with the PLAN's ownership (A = shared/androidApp/desktopApp/build files, B = server/ops/reports, tester = 19.7).
Build in gated steps, because two device checks can stop the phase:
- Step 1 (gate, 19.1): manager records "Permissions before" (19.1 step 3a) BEFORE the library is added; coder-b writes ops/phone-model.md (Gallery test, two sample prompts, model download + adb push, second copy route, bold uninstall warning) and STOPs; Tim runs the Gallery test and pushes the model; coder-b writes reports/phase-19-measure.md from Tim's numbers; coder-a adds litertlm 0.19.1 (arm64-v8a) and builds (AC-3). Marks checked by the manager (AC-1, AC-2); a miss on E4B then E2B stops and asks Tim (PLAN 19.1 step 3 options).
- Step 2: A 19.2 contract (DTOs, SendOutcome, phoneResult, chat.sq queries, PhoneBrain/Gate/ReplyStream, fallback resend) || B 19.3 step 2 (PromptBudget.PHONE, phone-brain.md, per-brain system text, LiteContext PHONE extras, OneVoiceTest phone case).
- Step 3: B rest of 19.3 (rung 4, PHONE_PENDING, replay rules, phone-result, tick, detail filter, PhoneTurnTest) || A 19.4 (LiteRtPhoneBrain, AppServices member, eval harness, smoke packs). Then the manager's early phone check (19.4 step 8 smoke run, AC-7) + permission comparison; a schema refusal stops and asks Tim.
- Step 4: A 19.5 + 19.6 (DeviceState, ChatController flow, streaming bubble, Settings card) then A release lines 1.17.0 / versionCode 13.
- Step 5: tester 19.7 (port, 20 held-out, PhonePackExportTest, leak test, Phase19AcceptanceTest); manager runs the on-phone eval (AC-9).
- Step 6: B 19.8 (deploy guide, checklist, version lines, report); deploy, install with adb install -r, launch screencap, Tim's checklist (AC-12..15), report (AC-16).

## Proposed split
Coders: 2 - backend and apps are separate parts with a fixed DTO/route contract (SPEC §6).
Part A: shared contract, Android engine + eval harness, chat flow, Settings card, release lines | Folders: shared/, androidApp/, desktopApp/, gradle/, settings.gradle.kts, build.gradle.kts
Part B: server rung 4 and phone-result, tick, prompts, ops guides, reports | Folders: server/, ops/, reports/
Contract:
- `SendMessageRequest(..., phoneBrain: Boolean = false)`; `PhoneTurnDto(turnId: String, messageId: String, system: String, user: String, schema: JsonObject, expiresAt: Long)`; `PhoneResultRequest(turnId: String, messageId: String, decision: String, firstTokenMs: Long? = null, tokensPerSecond: Double? = null)` - all in SH/model/ChatDtos.kt (Part A writes, Part B reads).
- `POST /v1/chat/{id}/message`: 200 SendMessageResponse | 202 PhoneTurnDto | 503 ErrorResponse("Wally is still answering") while another message's turn is pending.
- `POST /v1/chat/{id}/phone-result`: 200 SendMessageResponse | 400 (bad body, turnId != "r:"+messageId) | 401 | 403 calendar | 404 chat gone / other chat | 409 not pending / expired / no row.
- `turnId = "r:" + messageId`; expiresAt = now + 180_000; phone limit 2,000 chars; only the bearer token named "phone" in config.tokens gets 202.
- chat.sq: `selectPhonePendingReplies`, `deleteMessageById` (Part A writes, Part B uses). No migration (schema 8, 34 tables).
- `decisionSchema(dreams)` and `parseDecision` stay in shared Decision.kt (Phase 18) and are used by both sides plus the harness.
Work IDs:
P19-A-01 litertlm 0.19.1 in libs.versions.toml + androidApp (arm64-v8a), assembleDebug at minSdk 26 (19.1 step 5);
P19-A-02 DTOs per SPEC §6 + SendOutcome + ChatApi.sendMessage 200/202 + ChatApi.phoneResult (19.2 steps 1-2);
P19-A-03 ChatController.deliver PhoneTurn -> same messageId resend with phoneBrain=false; chat.sq two queries (19.2 steps 3-4);
P19-A-04 PhoneBrain/PhoneBrainStatus/PhoneBrainResult/NoPhoneBrain, PhoneBrainGate, ReplyStream + PhoneBrainGateTest, ReplyStreamTest, ChatControllerTest case (19.2 steps 5-7);
P19-A-05 LiteRtPhoneBrain (model file lookup, GPU->CPU, response format, streaming, single-thread dispatcher + Mutex, 180 s idle unload, onTrimMemory), AppServices.phoneBrain, WallyApp wiring (19.4 steps 1-6);
P19-A-06 PhoneBrainEvalTest harness + smoke-packs.json (3 packs, real decisionSchema) + androidTest runner deps (19.4 steps 7-8 code side);
P19-A-07 DeviceState/NoDeviceState + AndroidDeviceState + AppServices wiring (19.5 files);
P19-A-08 ChatController phone flow: gate on send, PhoneTurn -> generate 60 s -> phoneResult, fallbacks (null/timeout/Offline/5xx/409/404/other 4xx), 503 "still answering" retry in 30 s without offline flag, restart resend, slow-line suppression while the phone writes (19.5 steps 1-3);
P19-A-09 ChatParts streaming bubble for the open chat + ChatLogic/ChatLogicTest (19.5 step 4);
P19-A-10 Settings "Phone brain" card (switch kv phoneBrain.enabled, status line, battery line, Test button with reply/first word/tps) + Labels + LabelUniquenessTest (19.6);
P19-A-11 release 1.17.0 / versionCode 13 in androidApp and desktopApp (19.8 Part A, before 19.7);
P19-B-01 ops/phone-model.md: Gallery test with short (~500 token) and full-size (~7,000 char) sample prompts printed in the guide, four follow-ups incl. the 35 shekels line, 10-min battery run, HF download, adb push + run-as second route, bold uninstall warning (19.1 steps 1, 4);
P19-B-02 reports/phase-19-measure.md from Tim's numbers and the manager's permission output ("Permissions before"), chosen model named, marks table, ls -l output (19.1 steps 2-3a);
P19-B-03 PromptBudget.PHONE, phone-brain.md (<=1,500 chars, same rules as hp-last-resort.md), DecisionPrompt takes the brain's system text (voice + blank line + file), LiteContext PHONE extras (7 days events/exams, 12 open tasks, today's meals/spending with amounts, week spending total), OneVoiceTest phone case (19.3 step 2);
P19-B-04 rung 4 in ChatRunner (new TurnResult.PhoneTurn carrying the prompt; conditions of 19.3 step 1; phone token name passed from routes into ChatService.send as a defaulted parameter) (19.3 step 1);
P19-B-05 ChatService: start turn under the chat lock (user row, PHONE_PENDING reply row, expiresAt in extras, 202) + replay rules incl. RUNNING switch, 60 s margin, row delete on rung-5 Unavailable(wrote=false), 503 still answering, close-and-continue after expiry (19.3 steps 3-4);
P19-B-06 phone-result route + ChatService/ChatRunner path: state to RUNNING, parseDecision -> DecisionValidator.apply with a ServerToolExecutor for the turn, finish with brain=phone, speed fields on the log line only (19.3 step 5);
P19-B-07 closeExpiredPhoneTurns under the chat lock + its own unconditional 30 s Scheduler in Main after ChatService is built; ChatStore.detail hides unfinished blank assistant rows (19.3 steps 0, 6, 7);
P19-B-08 PhoneTurnTest + ChatRoutesTest/DecisionValidatorTest additions covering every case in 19.3 Verify;
P19-B-09 ops/deploy-phase19.md, ops/checklists/phase19.md, version 1.17.0 in phase1-e2e/phase4/phase6/nutrition checklists (19.8 steps 1-2);
P19-B-10 reports/phase-19.md: SC-14..SC-21 with evidence, eval line, permission comparison, Overview lines for Tim (19.8 step 3).
Tester (19.7): port version pins 1.16.0/12 -> 1.17.0/13; 20 held-out messages (6 adds, 5 edits, 6 data questions, 3 conversation) written without reading phone-brain.md / hp-last-resort.md; PhonePackExportTest -> tests/acceptance/build/phone-eval.json; HoldoutLeakTest extended; Phase19AcceptanceTest (non-manual criteria).

## Findings
- F1 Line numbers in the PLAN moved after Phase 18: ChatController.deliver is at ChatController.kt:305-349 (typing check :316, 5xx -> offline :346); ChatApi.call :89-101, sendMessage :126-135; ChatService replay branch :87-97. Task text should cite functions, not lines.
- F2 Slow line: deliver() launches api.brain() in parallel (ChatController.kt:319-322) and sets slowBrain on "hp". With the laptop off the server answers "hp", so the slow line would show while the phone model writes (SPEC §6 says never). A-08 must skip that probe when it sends phoneBrain=true and show the slow line only on the phoneBrain=false fallback resend.
- F3 The "ladder tick" exists only when an embed model is configured (Main.kt:191-194) and is created before ChatService (Main.kt:197). closeExpiredPhoneTurns gets its own Scheduler("phone turns", LADDER_TICK_MS) after chatService, unconditional, so expiry works even with the sorter off.
- F4 LiteContext is SRV/chat/LiteContext.kt, not SRV/chat/ladder/ (PLAN 19.3 Files).
- F5 DecisionPrompt.system is hard-wired to hp-last-resort.md (DecisionPrompt.kt:25); B-03 makes the system text depend on the brain (HP vs PHONE) without changing the HP output (OneVoiceTest keeps the HP case).
- F6 HpLastResort.run couples the model call and DecisionValidator.apply (HpLastResort.kt:55-68), and ServerToolExecutor is built inside ChatRunner.turn (ChatRunner.kt:104). The phone-result path needs a ChatRunner function that builds the executor for (chatId, replyId) and applies a raw decision with the same "say again" on unparsable output; HpLastResort should reuse it so both brains validate identically.
- F7 TurnResult gains a PhoneTurn case; ChatRunner.run's rung log (:94-97) and ChatService.turn's when (:101) must handle it. The 202 start logs nothing; the one `ladder rung=phone ms=… firstTokenMs=… tps=…` line is written by the phone-result path (PLAN 19.3 step 5).
- F8 Length rules: MAX_MESSAGE_CHARS 8,000 (ChatService.kt:18); HP too-long at 600 (HpLastResort.kt:17); phone at 2,000. A 601-2,000 char message with the laptop off is the main gain of rung 4.
- F9 Token identity: config.tokens already has the name "phone" (ops/config.example.json:6); chatRoutes receives only the calendar token (Main.kt:327, ChatRoutes.kt:38). B-04 passes the token name; the laptop token never gets 202.
- F10 Version catalog already has androidx-testExt-junit 1.3.0 and androidx-testRunner 1.7.0 (libs.versions.toml:12-13, 35-37); androidApp has no testInstrumentationRunner yet. Version now 1.16.0 / 12 (androidApp/build.gradle.kts:36-37).
- F11 ChatRunner comments call the laptop "rung 4" (ChatRunner.kt:112); PLAN numbers the laptop rung 3 and the phone rung 4. B-04 fixes the comment.
- F12 Lesson (P15): no gate runs MainActivity; this phase edits WallyApp (Application), not MainActivity. After every phone install the manager launches via adb and takes a screencap.
- F13 Lesson: adb lists the S25 twice; use `adb -s <mdns serial>`; never connectedDebugAndroidTest (deletes the model file and local DB).
- F14 Lesson: big coder steps hit turn limits; A's work is split into steps of 3-4 Work IDs (A-01; A-02..04; A-05..06; A-07..09; A-10..11).

## Risks
- LiteRT-LM 0.19.1 at minSdk 26 or the constrained-output API on Gemma 4 may not work: AC-3 build and the step-3 smoke run (AC-7) are early checks with PLAN-defined stop options (raise minSdk / newest building version; fixed-object args schema applied to HP too; stop).
- Speed marks (>=15 tok/s, <=3 s first word on 7k chars) may miss on E4B: retry E2B, then Tim's options (smaller PHONE budget, accept measured time, stop).
- Race bugs between the tick, a late phone result and the rung-5 resend: all three take the per-chat lock and re-read state; PhoneTurnTest covers each ordering.
- Held-out 16/20 on a 2-4B phone model may miss: pass marks are never lowered mid-phase (PLAN locked); a miss is a fix round on prompt/schema, or Tim's call.
- Battery/privacy checks are manual and time-gated; record skipped/pending checks honestly (Lessons).
- Library might add a manifest permission: compared against "Permissions before" in step 3; a change stops and asks Tim.

## Questions
| ID | Blocking | Question | Suggested answer |
|----|----------|----------|------------------|
| P19-Q-CLA-01 | yes | Does Tim approve the Phase 19 locked-decision changes: A19 "no local fallback model" -> phone model; dream wall rule kept but the prompt may be read by the phone model; Tech stack gains LiteRT-LM 0.19.1 + Gemma 4 model file; canvas BrowseSettings gains a "Phone brain" card? | Ask Tim at approval (phases.md Phase 19 Notes; PLAN "Changes to locked decisions") |
| P19-Q-CLA-02 | yes | Does Tim have (or will he make) a Hugging Face account to accept Gemma's terms and download the .litertlm file (several GB) on the laptop? | Escalate; suggest a free account with his Google login (phases.md Depends on) |
| P19-Q-CLA-03 | no | Release version | 1.17.0 / versionCode 13 (androidApp/build.gradle.kts:36-37 is 1.16.0/12; phases.md 19.8) |
| P19-Q-CLA-04 | no | Who edits Overview.md? | Exact lines in reports/phase-19.md; Tim pastes them (P18-Q-CLA-03) |
| P19-Q-CLA-05 | no | Where does the expiry tick run, given the ladder Scheduler exists only with an embed model? | Own unconditional Scheduler after ChatService (Main.kt:191-197) |
| P19-Q-CLA-06 | no | "Laptop off" in AC-13/AC-14 while the manager runs on the laptop? | Quit Ollama on the laptop for the check, restart after (P18-Q-CLA-04) |
| P19-Q-CLA-07 | no | Who runs the adb/am instrument steps? | The manager over wireless adb; Tim turns on Wireless debugging and keeps the phone unplugged, above 20%, power saving off |

## Helper notes
none (no helpers)
