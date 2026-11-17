# Wally brain ladder — Spec (Phases 18–19)

Project: `C:\Users\user\Projects\assistant`. Decisions: `DECISIONS.md` (B1–B35). Research and sources: `RESEARCH.md`.

## 1. Goal
Phase 17 moved chat to the HP server and found that the HP's 2-core processor cannot run a capable model at a usable speed. This plan makes Wally's chat fast and trustworthy with the laptop off, for Tim only, without sending his data anywhere new. Messages climb a ladder: plain code handles clear commands at once; a small meaning-matching model on the HP recognises reworded questions about Tim's day and answers them from his data (it never saves anything); the laptop's model answers when the laptop is on; the phone's own model answers when it is off; and the HP's small model, restricted to filling in one fixed form, is the last resort. The brains act as one assistant: one memory on the HP feeds every brain, and one short personality text opens every brain's instructions. Phase 18 builds the HP rungs and corrects the phone chat screens to the canvas; Phase 19 puts the model on the phone. Learning from Tim's use is a later phase (B35).

## 2. Success criteria
Each is checked by the named test, command or manual step.

Phase 18
1. A clear English command ("I spent 35 shekels on lunch") is saved and answered with no model call, laptop on or off. Test: `LadderTest`.
2. On 60 held-out messages plus 18 held-out guard messages, all written by the tester after the coders finish, the router and sorter cause 0 wrong saves and 0 wrong cards, with the real embedding model. Test: `RouterHoldoutTest` + `SorterHoldoutTest`.
3. At least 45 of the 60 are handled by code with the expected action, arguments or data answer and no generative model. A correct pass-up does not count toward the 45. Same tests.
4. On the HP, the server log line `ladder rung=code ms=N` shows N ≤ 1,000 and `ladder rung=sorter ms=N` shows N ≤ 2,000 for each checklist message. Checked from `journalctl` in the Task 18.9 checklist.
5. No held-out message is identical (lower-cased, punctuation removed) to, or shares 6 consecutive words with, any seed, development sentence, prompt file or coder-written test. Test: `HoldoutLeakTest`.
6. Anything the router and sorter are not sure about reaches a model and is never acted on by code. Test: `LadderTest` (ambiguous, negated, near-miss, past-dated and two-command inputs).
7. With the laptop off, the HP last resort answers one message with one model call, returns valid fixed-shape JSON 10 of 10 times, gives a real reply on 3 of 3 conversation messages and the right action with its fields on at least 3 of 4 adds, and takes a median of at most 60 s on calls whose data differs every time. Manual (Tim): `ops/hp-brain-check.py`, recorded in `reports/phase-18-measure.md`.
8. The HP last resort never runs an action outside the B10 list and never writes without the server validating the fields. Test: `DecisionValidatorTest`.
9. Every edit shows a yes/no card; adds save at once with Undo; several name matches show chips and tapping one finishes the action; no match says so; no reply ever shows an id. Test: `LadderTest`, `DecisionValidatorTest`.
10. Dream wall: there is no seed for `add_dream`, no seed contains a dream word, the sorter returns "no match" for any message that mentions dreams, and the fixed-shape prompt carries dream text only under the existing A18 rule. Test: `SeedStoreTest`, `SorterTest`, `IntentRouterTest`, `DreamWallServerTest`.
11. The Phase 17 lite request is gone: no `LITE_TOOL_NAMES`, no `wally-persona-lite.md`, no `ops/model-eval.py`. Command in Task 18.6.
12. The phone chat screens match the canvas frames `ChatMain`, `ChatList`, `ChatCard`, `ChatMemory`: all 16 listed differences fixed and every further difference found in the frame-by-frame check fixed. Manual (Tim) against screenshots, Task 18.9 checklist.
13. Every model brain's instructions start with the same `wally-voice.md` text. Test: `OneVoiceTest` (laptop persona and HP last resort in Phase 18; the phone brain is added in Phase 19).

Phase 19
14. With the laptop off and the phone model allowed, a question the code cannot handle is answered by the phone's model: first word within 3 s and a short answer complete within 10 s, both with the model already loaded (the one-time load after 3 idle minutes is recorded but not counted). "First word" is the moment the first word of the reply text is visible, not the first character the model emits. Test: `PhoneBrainEvalTest` (on the phone) and Manual (Tim).
15. At least 16 of 20 held-out prompt packs get the right decision or the right data value. Test: `PhoneBrainEvalTest`.
16. The phone model's output can only be the fixed shape; the server validates it exactly as it validates the HP last resort. Test: `PhoneTurnTest`, `DecisionValidatorTest`.
17. Below 20% battery, in power saving, with the switch off, or with no model file, the phone does not run the model and the message is answered by the HP last resort. Test: `PhoneBrainGateTest`, `ChatControllerTest`.
18. If the phone model fails or times out (60 s), the message is answered by the HP last resort. If the app is closed mid-answer, the turn is closed with the Phase 17 "interrupted" text within 210 s (180 s expiry plus one 30 s server tick). Nothing is saved twice and no queued message is lost. Test: `PhoneTurnTest`, `ChatControllerTest`.
19. Phone-model replies appear word by word. Manual (Tim).
20. Privacy: the Settings test question is answered in airplane mode; with Wi-Fi on and Tailscale off, 10 test questions do not change Wally's data-usage figure; the app's merged permissions are unchanged. Manual (Tim) and Task 19.4.
21. Ten phone-model messages in ten minutes, starting above 50% and unplugged, cost at most 5% battery. Manual (Tim), measured in Task 19.1 and again in Task 19.8.

## 3. Scope
### In scope
- Phase 18: HP measurement gate; guards, slot parsers and code router; sorter with hand-written seed examples held in memory; ladder in `ChatRunner`; fixed-shape `ModelDecision`; server-side validation; rebuilt HP last resort; one shared personality text; removal of the Phase 17 lite request and its eval; held-out tests and leak test; phone chat UI fix; release 1.16.0. No database change.
- Phase 19: phone measurement gate; model file copy guide; LiteRT-LM in `androidApp`; phone turn contract and routes; battery gate; streaming; Settings "Phone brain" card with a test button; on-device eval; privacy check; release 1.17.0. No database migration (one new state value and two new queries).
- Planning side (done 2026-11-12): Wally Voice renumbered to Phases 20–21.

### Out of scope (deferred)
| Item | Owned by |
|------|----------|
| Learning from Tim's use (the sorter learning phrasings from what any brain got right, judged by his Yes and Undo) and showing that learning in Settings (B35) | A later phase, planned on its own after Phase 19 |
| Waking the laptop from the HP (B24) | Not planned |
| Answer-later queue with a notification (B25) | Not planned |
| Chat with no connection to the HP (B26) | Not planned |
| Router rules in Hebrew (B7) | Not planned |
| Logging something for a past day through the code rungs ("spent 35 yesterday") | Not planned: those messages go to a model |
| Phone model on the laptop app or for the voice box | Not planned (voice uses rungs 1, 2, 3, 5) |
| In-app model download (B15) | Not planned |
| Laptop chat and Settings screen changes (`LaptopChat`, `BrowseSettings`, `LaptopSettings` frames) | Not planned; checked only, in the Phase 18 checklist |
| Wally Voice | Phases 20–21 |

## 4. Users and flows
One user: Tim, on the phone or the laptop.

Flow A, clear command (any time): Tim sends "add task call the dentist tomorrow". The HP router matches one intent with every required field, runs the existing Phase 17 tool code, and replies "Added: call the dentist, due tomorrow." with the Undo button. No model runs. Under 1 s.

Flow B, reworded question: "what have I got on today then". The router finds no clear match. The sorter embeds the sentence, finds its three nearest seed examples are all `ask_today`, and code answers from Tim's data. Under 2 s. The sorter is read-only (B33): a reworded command that would save something, such as "lunch set me back 35" or a reworded "add a task", is passed up to a model.

Flow C, unclear message, laptop on: passed to the laptop model exactly as in Phase 17 (full tool list).

Flow D, unclear message, laptop off, sent from the phone (Phase 19): the phone said "my model is available". The HP replies with a prompt pack (instructions, Tim's compact data, the message, the fixed-shape schema). The phone's model writes the decision; if it is an answer Tim sees it appear word by word. The phone sends the decision to the HP, which validates it, runs the action if there is one, stores the reply and returns the final message.

Flow E, last resort: laptop off and no phone model (battery rule, laptop app, voice box, or Phase 18 before Phase 19 exists). The HP's small model fills the same fixed shape in one call. After 10 s the typing row shows "Thinking on the home server. This can take about a minute."

Flow F, edit: "mark the physics homework as done" (router) or "that lunch was 45, not 35" (model). The server finds the target by name. One match: a yes/no card. Several: up to 5 chips, and tapping one opens the card with no further model call. None: a sentence saying so.

Flow G, outside the list with the laptop off: "That needs the laptop to be on."

## 5. Data
| Entity | Fields | Where | Lifetime |
|--------|--------|-------|----------|
| Seed examples | `server/src/main/resources/intent-seeds.jsonl`: `{"text": "...", "intent": "..."}`, at least 12 for each of 14 intents (every intent except `add_dream`) | Server resource | Edited by coders only |
| Seed vectors | one float array per seed | Server memory only; embedded in the background at every server start | Until the server stops |
| `wally-voice.md` (new, Phase 18) | lines 1–7 of today's `wally-persona.md`, moved unchanged | Server resource | Edited by coders only |
| `chat_message.card_json` extras | new field `brain` = `code`, `sorter`, `xps`, `phone` or `hp` (a message whose only extra is `brain` is still stored); new field `pending` = `{tool, args}` kept with "which one?" chips issued by code or the validator (B34) | Existing column | With the message |
| `chat_message.request_state` | new value `PHONE_PENDING` (Phase 19) next to the Phase 17 values; the turn's `expiresAt` is kept in the reply's extras | Existing column | Until the phone result arrives or 180 s pass |
| Phone model file | one `.litertlm` file | Phone: `/sdcard/Android/data/dev.tim.assistant/files/models/` | Until Tim deletes it or uninstalls the app |
| Phone setting `phoneBrain.enabled` | Boolean, default true | Phone local `kv` | Permanent |

Intent names: `create_task`, `add_spending`, `add_meal`, `add_checkin`, `add_journal`, `add_dream`, `mark_task_done`, `move_task`, `move_exam`, `find`, `ask_today`, `ask_tasks`, `ask_events`, `ask_spending_week`, `none`.

`ModelDecision` (shared, `@Serializable`): `kind: String` ("answer" | "action" | "needs_laptop"), `reply: String? = null`, `action: String? = null`, `target: String? = null` (name of an existing item, for edits and find), `args: Map<String, String> = emptyMap()`.
Allowed `action` values: `create_task`, `add_spending`, `add_meal`, `add_checkin`, `add_journal`, `add_dream` (only when the message mentions dreams), `find`, `update_task`, `update_exam`, `update_meal`, `update_spending`, `update_checkin`, `update_journal`, `update_goal`, `update_doc`.

## 6. Interfaces
| Interface | Input | Output |
|-----------|-------|--------|
| `POST /v1/chat/{id}/message` (changed in 19) | `SendMessageRequest` + `phoneBrain: Boolean = false` | 200 `SendMessageResponse` as today; 202 `PhoneTurnDto` when rung 4 is chosen (the same 202 again if the same `messageId` is resent with `phoneBrain = true` while the turn is pending); 503 for a different message of that chat while a phone turn is pending (the client keeps it queued) |
| `POST /v1/chat/{id}/phone-result` (new in 19) | `PhoneResultRequest(turnId, messageId, decision: String, firstTokenMs: Long? = null, tokensPerSecond: Double? = null)` | 200 `SendMessageResponse`; 409 when the turn is expired, already finished or has no reply row; 404 when the chat no longer exists or the turn belongs to another chat; 400 on bad input or when `turnId` is not `"r:" + messageId`. The two speed fields are only written to the server log |
| `PhoneTurnDto` (new in 19) | — | `turnId: String`, `messageId: String`, `system: String`, `user: String`, `schema: JsonObject`, `expiresAt: Long` |
| `GET /v1/chat/brain` (unchanged route) | — | `"xps"` or `"hp"`; the client shows the slow line only for `"hp"` and never when it is running its own model |
| Phone Settings "Phone brain" card (19) | switch; "Test phone brain" button | status line (model name and size, or "No model file"), test reply and tokens per second |
| `ops/hp-brain-check.py` (18) | `python3 hp-brain-check.py` on the HP | embed timing, probe ranking, RAM, last-resort timing and JSON validity |
| `ops/phone-model.md` (19) | — | Tim's steps: download, `adb push`, check |
| Phone chat screens (18) | — | match `ChatMain`, `ChatList`, `ChatCard`, `ChatMemory` |

Phone chat differences found in Tim's screenshots of 2026-11-12 (app 1.15.0) against the frames:
| # | Screen | Now | Canvas |
|---|--------|-----|--------|
| D1 | Main | Large hub title "Chat" and "Synced just now" above the chat | No hub title on the Chat hub; the chat header is the top of the screen |
| D2 | Main | "Chats" text link | 48 dp icon button, three-line menu icon, label "Open chat list" |
| D3 | Main | "New chat" text link | 48 dp icon button, pencil icon, label "New chat" |
| D4 | Main | Chat name centred | Chat name left-aligned beside the menu icon, 17 sp medium, "Wally" 12 sp under it |
| D5 | Main | No line under the header | 1 dp divider `#E4E7E1` under the header |
| D6 | Main | Square outlined text field plus a separate "Send" text button | One white pill (radius 28 dp, min height 56 dp, 1 dp border `#E4E7E1`, soft shadow) holding the text area and a round 44 dp green button with an up-arrow icon |
| D7 | Main | Placeholder "Message Wally" | "Message Wally…" |
| D8 | Main | Composer touches the edges differently | 12 dp from each side and 12 dp above the bottom bar |
| D9 | List | Drawer starts under the hub title and stops above the bottom bar | Drawer covers the full height from the top of the screen, over a scrim `rgba(22,32,26,0.45)` that also covers the bottom bar |
| D10 | List | Drawer about 346 dp wide, green-tinted background, square corners | 320 dp wide, white `#FFFFFF`, right corners rounded 24 dp |
| D11 | List | "Chats" large with the count beside it | "Chats" 22 sp medium at the left, "N of 10" 12 sp at the far right |
| D12 | List | Weekly report with a green dot, no tint, no divider | Pin icon, row background `#DCEFE3`, then a 1 dp divider before the other chats |
| D13 | List | Selected chat tinted green with white corner artefacts | Selected chat `#EFF2EE`, radius 14 dp, no artefacts |
| D14 | List | Row text larger than the frame | Name 15 sp medium, preview 13 sp, time 12 sp, each on one line with an ellipsis |
| D15 | List | The "11th chat" note sits at the bottom above the buttons | The note sits directly under the last chat row, 12 sp |
| D16 | List | "Memory" and "New chat" side by side, no icons | Stacked: "Memory" text button (48 dp, bulb icon) above "New chat" (52 dp, full width, plus icon) |

## 7. Edge cases and error states
| Case | Expected behaviour |
|------|--------------------|
| Negation ("I didn't spend 35", "don't add") | Blocked: neither the router nor the sorter runs; a model answers |
| Near-miss ("lunch will cost 35", "Dana paid for me", "I got a 35 shekel refund", "I owe Dana 35") | Blocked |
| An ordinary sentence that shares a command's words ("I had guests for dinner", "I ate nothing for breakfast", "I had a dream job once") | Not saved: the meal rule accepts only "log/add …" or "I ate … for …", the dream rule only "I dreamt/dreamed …", and absence words such as "nothing", "no", "almost" block both code rungs |
| Looks like a spending but is not Tim's, not money or not shekels ("Dana paid 35 for lunch", "I spent 35 minutes on lunch", "I spent 35 dollars on lunch") | The router's spending rule does not match (it needs the message to start with "I spent/paid" or "<thing> cost me", and an amount that is a bare number or in shekels); the sorter never saves; a model answers |
| A reworded command that would save something ("lunch set me back 35") | Passed up to a model: the sorter only answers questions |
| Two commands or two money amounts in one message, more than one sentence, or over 200 characters (so a journal entry of several sentences is written by a model, not by code) | Blocked |
| A request to save a different kind of thing than its words suggest ("add lunch with Dana at 1" is a calendar plan, "I spent 500 on groceries this week" is a total, "remind me to take my pills at 9pm" needs a time) | The router's rules do not match; a model answers |
| A spending whose words fit two categories, or none ("taxi to the restaurant") | Not saved by code; a model asks or decides |
| A message about another time ("I spent 35 on lunch yesterday", "check-in for Monday…", "how much did I spend last week", "how much did I spend this month", "what's on next week", "what's on at the weekend", and the same in Hebrew) | The sorter is skipped and the router's log adds and `ask_*` rows do not match, so nothing is saved under the wrong date and no question is answered for the wrong day; a model answers. Tasks and exams keep their own date fields ("add task … on Friday" still works) |
| A question ("what have I got on today then?") | Not blocked, but a question can only reach the `ask_*` intents of the router and the sorter, never an add or an edit |
| Required field missing ("I spent money on lunch") | No match; a model asks one question |
| Text contains Hebrew letters | Router skipped; sorter per B7 |
| Message mentions dreams | Of the router's rows only `add_dream` may match (so a dream is never saved as a journal entry or anything else by code); the sorter is skipped; dream text enters a model prompt under the existing A18 rule |
| Sorter finds an intent outside B33 (anything but the four `ask_*` intents) | Passed up to a model |
| Embedding call fails or takes over 2 s, or the seeds are not loaded yet | Sorter: no match; the ladder continues |
| Ollama is down while seeds are being embedded at start | The server starts normally; the sorter stays off and seeding is started again by the 30 s ladder tick |
| The tool code answers a code-rung action with an error | Nothing is written; the message is passed up to a model |
| Check-in for a day that already has one | Existing Phase 17 behaviour: `update_checkin` card |
| Chip tapped, pending action stored with the chips | Code completes that action with the chosen id |
| Chip tapped after a find by code or the validator | Code replies "Found it: <name>." |
| Chip tapped, no pending action stored (chips issued by the laptop model) | Laptop model if it is the one answering; otherwise "That needs the laptop to be on." |
| An edit of today's meal, spending, check-in or journal names something that matches no entry ("change the taxi to 45") | Chips with today's entries of that kind, even when there is only one; tapping one opens the card. A blank or generic name ("that", "it") means the newest entry |
| Edit target has several matches / none | Chips (up to 5) / a sentence listing what exists |
| Model returns JSON that fails validation, an `answer` with no text, or an `answer` that looks like it contains an id | "I didn't get that. Can you say it another way?"; nothing is written |
| Model names an action outside the list | Treated as `needs_laptop` |
| Message longer than 600 characters reaches the HP last resort | "That one is too long for me right now. It needs the laptop on." |
| The HP model times out (170 s) or Ollama fails | 503 with the existing "brain is not reachable" text; the message stays queued; nothing is written |
| Laptop model fails after a good probe, before any tool ran | The ladder continues: rung 4 if the request has `phoneBrain = true`, else rung 5. A half-run tool loop is never rerun (Phase 17 rule) |
| Phone result arrives twice, or after the turn was closed | 409; the stored reply stands; the client reloads the chat and, if the chat holds no reply for that message, sends it again with `phoneBrain = false` |
| Phone result with a `turnId` that does not belong to the `messageId`, or to another chat | 400, or 404 |
| Phone result arrives after `expiresAt` but before the tick | The server closes the turn with the "interrupted" text in the same lock and returns 409 |
| Phone turn expires with no result | The 30 s ladder tick closes it with the "interrupted" text, under the chat's lock; an unfinished reply with no text is never listed by `GET /v1/chat/{id}`, so no empty bubble is shown |
| The 202 is lost and the phone resends with `phoneBrain = true` | The same turn is returned again (same `turnId`); after expiry the server answers with rung 5 |
| Same `messageId` resent with `phoneBrain = false` while `PHONE_PENDING` | The server continues to rung 5 once, then stores the reply; if rung 5 fails with a timeout the reply row is removed so the next retry starts fresh |
| A different message for that chat while a phone turn is pending | 503; the client keeps it in the send queue, without showing the offline banner, and retries after the next sync |
| Phone at 19% battery, or power saving on | `phoneBrain = false` is sent; rung 5 answers |
| Model file missing or unreadable | Settings shows "No model file"; `phoneBrain = false` |
| App closed while the phone model is writing | Turn expires after 180 s; the next open shows the Phase 17 "interrupted" text |
| Old 1.15.0 app talking to the new server | Works: it never sends `phoneBrain`, so it never receives 202 |

## 8. Technical decisions
| Area | Choice | Version | Source (link) |
|------|--------|---------|---------------|
| Sorter embedding model | `embeddinggemma` (622 MB) in Ollama on the HP, text prefixed `task: classification \| query: ` unless Task 18.1 measures a better score without the prefix; `keep_alive: -1` on every embed call | Ollama 0.40.14 (already installed) | https://ollama.com/library/embeddinggemma ; RESEARCH §3 |
| Sorter rule | Accept only if the 3 nearest seeds share one intent, the top cosine score ≥ `SORTER_MIN_SCORE`, and the best score of any other intent is at least `SORTER_MIN_MARGIN` lower. Start values 0.80 and 0.08; Task 18.4 raises them until the coder's development set has 0 wrong accepts | — | DECISIONS B4, B5 |
| HP last resort | `llama3.2:3b` through the server's `OllamaClient.chat(..., format = schema)`, `num_ctx` 8192 (the same value every HP caller sends, so Ollama never reloads the model between callers), temperature 0 | already on the HP | `server/.../ai/OllamaClient.kt` line 48 |
| Phone library | `com.google.ai.edge.litertlm:litertlm-android` | 0.19.1 | https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android |
| Phone model | Gemma 4 E4B, then Gemma 4 E2B (B14) | file from `litert-community` | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm (the E2B file's page is not confirmed; Task 19.1 looks it up only if E4B misses its marks) |
| Constrained output on the phone | `ConversationConfig(enableResponseFormat = true)` + `ResponseFormat.json(schema)` | ≥ 0.16.0 | https://github.com/google-ai-edge/LiteRT-LM/issues/1662 |
| Everything else | Kotlin 2.4.21, Ktor 3.6.1, SQLDelight 2.4.1, Compose Multiplatform 1.12.2 | unchanged | `gradle/libs.versions.toml` |

## 9. Constraints
- HP: 2 cores, 8 GB RAM, server heap `-Xmx512m`, one generation at a time (`generationLock`); the embedding call does not take that lock. Free RAM stays ≥ 500 MB with both models loaded.
- No personal data leaves Tim's devices. The model file download carries no personal data. No cloud model for chat.
- Dream wall: A18 rule unchanged; the seed file and reports stay dream-free.
- Phone: model runs only in the foreground app process; never from `SyncWorker` or `ReminderReceiver`.
- Agents never edit `Overview.md`, `phases.md`, `scripts/`, `.claude/`; Overview changes are given to Tim as exact lines.
- Any SSH command outside the Overview's allowed list is run by Tim from a written step.

## 10. Compatibility
- Respects: ServerWriter for every server write; `ToolBox` allow-list at dispatch (C7); server-authorised Undo; ApprovalSink and `/v1/approvals/{id}`; message-id replay; sync push fences; 10 chats + memory; reminders; Codex on the HP; schema version 8 and 34 tables (unchanged); never editing `C:\Users\user\Projects\WallyCalendar`.
- The laptop model path (rung 3) keeps the full Phase 17 tool list. Its persona text is unchanged in content: lines 1–7 of `wally-persona.md` move into `wally-voice.md` and are put back in front of the rest when the prompt is built (B30).
- Changes to locked decisions: listed in `DECISIONS.md`, approved by Tim at each phase's approval step.
- Phase 17 is closed (B23); its pending manual checks are repeated in the Phase 18 checklist.
