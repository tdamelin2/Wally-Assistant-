# Wally input handler — Spec (Phases 20–22)

Project: `C:\Users\user\Projects\assistant`. Decisions: `DECISIONS.md` (H1–H31). Builds on the Phase 18–19 brain ladder (in the project: `docs/plan/wally-brain/`). Phase 22 is the taught-shortcuts plan (`docs/plans/wally-shortcuts/`, L1–L17) with the changes in §11.

## 1. Goal
Wally should need an AI model far less often. Today, when plain code is not sure what a chat message means, the message goes to a model. After these phases code does more by itself (questions about other days, entries for another day, small corrections, calendar events, two commands in one message), and when a message is close to something code can do, Wally asks Tim with tap buttons instead of using a model. Tim also gets two buttons that send a message straight to a model or to Codex. The home server's model stops saving things by itself in chat. Finally Wally learns Tim's own wordings from his taps. For one user: Tim.

## 2. Success criteria
Phase 20
1. A question about another day or period from the H9 list is answered by code with the right figures and no model call. Test: `DayAnswersTest`, `PeriodsTest`, `IntentRouterTest`.
2. A spending for a named past day, and a meal for a past day with a stated time, produces a yes/no card that names the day, when the day is the first or last words of the message; nothing is saved before Yes; after Yes the row has that day's date. A day word in the middle of a message, a future day, a check-in and a journal note for another day are never logged by code. Test: `OtherDayLogTest`.
3. A clear command with one missing piece gets a question with the fixed values as buttons; tapping one saves the entry with Undo. Test: `InputHandlerTest`.
4. A message the sorter ranks close to a known command gets up to 3 suggestion buttons and "Something else"; each button's label states what its tap does, and the tap does exactly that. Test: `InputHandlerTest`, `SuggestLabelTest`.
5. A command-shaped message held back only by a "not a real event" guard word gets one suggestion and "Something else". A message with a negation word never gets a question. Test: `InputHandlerTest`.
6. A typed reply whose whole text equals a button label counts as the tap; any other message, including one that only contains a label word, drops the question and saves nothing. Test: `InputHandlerTest`.
7. "Something else" sends the original message to the model steps with no second question from the handler, also when the turn is handed to the phone model and retried. Test: `RouteTest`, `PhoneTurnTest`.
8. "Ask model" skips every code step; "Ask Codex" queues the text as research with no confirm step, refuses dream text, shows a saved answer at once, and the finished answer arrives as a message in the same chat. Test: `RouteTest`, `CodexRouteTest`.
9. The HP model never saves an add in chat except a dream entry: any other add becomes one "Did you mean…?" button; an edit is a yes/no card as before; answers, finds and dream entries are unchanged. The phone and laptop models are unchanged. Test: `DecisionValidatorTest`, `HpLastResortTest`.
10. Held-out marks H19: 0 wrong saves or cards, 0 mislabelled buttons, at least 64 of 80 finished with no model, 0 of 20 conversation messages get a question. Test: `HandlerHoldoutTest`.
11. Server time read from the log line: a code answer ≤ 1,000 ms, a sorter answer ≤ 2,000 ms (unchanged from Phase 18), a question ≤ 2,500 ms. Manual (Tim) with the manager reading the log.
12. Both composers show the three buttons; a message queued offline keeps its button choice. Test: `ChatControllerTest`, `LabelUniquenessTest`; Manual (Tim).
13. The unused Phase 13 client code of the audit's §1a is gone and `.\gradlew check` passes. Test: build; the grep in Task 20.1.

Phase 21
14. The two corrections of H21 each produce a card with the right new value; a stated old value that differs from the stored one, or a name that appears in none of today's spending entries, produces nothing from code. Test: `FixEntryTest`.
15. An event message per H22 produces a `create_event` card with the right title, day, time and length; without an event word, with a bare hour, or without a length, code does nothing. Test: `EventRuleTest`.
16. A two-part message where both parts are clear (a task or meal as first part only at "then" / "and then"; a journal note first is never split) runs both and the reply joins both answers with both Undo buttons; if either part is unclear nothing is saved by code; if the second part fails while saving, the first stays and the reply says so. Test: `SplitterTest`.
17. Held-out marks H24: 0 wrong saves or cards, at least 30 of 40 handled by code, 0 of 20 guard messages saved or carded. Test: `Phase21HoldoutTest`.

Phase 22
18. Every success criterion of `wally-shortcuts/SPEC.md` §2 holds as changed by §11 below (its criteria 6 and 10 are the two that change), with the version and numbering of H27.
19. The second time Tim taps the same suggestion for the same wording, the reply carries the shortcut offer; after Yes that wording runs by code with no question. Test: `ShortcutTeachingTest`.
20. A no-blank wording twice resolved to the same read-only question can be taught and then answers instantly; a question wording with a blank is never offered. Test: `ShortcutTeachingTest`, `ShortcutLadderTest`.

## 3. Scope
### In scope
Phase 20: period parser; day-question rules, seeds and answers; other-day logging on a card; the input handler (missing piece, suggestions, guard-word suggestion, typed answers, "Something else"); sorter ranking; the `route` field and the two buttons; the Codex route; HP suggest-only; removal of the unused Phase 13 client code; held-out tests; release 1.18.0.
Phase 21: two correction rules; the calendar-event rule; the two-command splitter; held-out tests; release 1.19.0.
Phase 22: the taught-shortcuts plan with tap teaching and question shortcuts; release 1.20.0.

### Out of scope (deferred)
| Item | Owned by |
|------|----------|
| Hebrew rules and Hebrew suggestions (H18) | Not planned |
| Importing Wally Trainer task packs (H29) | Wally integration plan |
| Moving the briefing, weekly report, chat summaries or calorie estimates off the HP model (H16) | Not planned |
| Any other cleanup from `reviews/cleanup-audit-2026-11-16.md` besides §1a | Not planned |
| Waking the laptop, answer-later, chat with no HP connection (H31) | Not planned |
| Wally Voice | Phases 23–24 |

## 4. Users and flows
One user: Tim.

Flow A, day question: "how much did I spend on food last month" → code answers "Last month you spent 412 ₪ on food." No model.

Flow B, another day: "spent 35 on lunch yesterday" → "I've put that on a card. Tap Yes to confirm." The card's summary shows the full date: "Add spending 35 ₪, food (lunch) for Sunday 15 November". Yes saves it under that date.

Flow C, missing piece: "spent 35" → "Which category?" with one button per spending category and "Something else". Tim taps Food (or types just "food") → one of the normal Phase 18 confirmations, with Undo.

Flow D, suggestion: "lunch set me back 35" → with one fitting action: "Did you mean: Log spending: 35 ₪, food (lunch set back)?" with the buttons "Yes" and "Something else". With two or three fitting actions: "Did you mean one of these?" with one button per action, each named by what it does, and "Something else". Tapping an action saves it with Undo. Tapping "Something else" sends the original message to a model.

Flow E, guard word: "I need to call the dentist tomorrow" → when the sorter ranks it close to "add a task": "Did you mean: Add task "call the dentist", due tomorrow?" with "Yes" and "Something else". "I need to vent about today" ranks far from every command and gets no question.

Flow F, conversation: "I'm tired today, any ideas?" → no question; the laptop or phone model answers.

Flow G, Ask model: Tim types "add task buy milk" and presses Ask model → the model handles it; code does not.

Flow H, Ask Codex: Tim types "best protein sources for vegetarians" and presses Ask Codex → the reply shows the finished card "Ask Codex: "best protein sources for vegetarians"" and "Asked Codex. The answer will arrive here in a minute or two." Later a new message from Wally holds the answer; while that chat stays open the app checks for it every 20 seconds for up to 5 minutes, and otherwise it is there the next time the chat is opened.

Flow I, laptop off and phone model unavailable: "lunch set me back 35" with the sorter down → the HP model proposes "Did you mean: Log spending: 35 ₪, food?" with "Yes" and "Something else". "Something else" replies "That needs the laptop to be on."

Flow J (Phase 21): "that lunch was 45, not 35" → the usual change card for today's spending whose note holds "lunch", with the new amount 45. "add meeting with Dana tomorrow at 2pm for 1 hour" → `create_event` card. "add task buy milk and then log lunch: salad" → both saved, one reply, two Undo buttons. ("add task buy milk and log lunch: salad", with a plain "and" after a task, is not split: the "and" may belong to the task.)

Flow K (Phase 22): Tim taps "Yes" on "Did you mean: Log spending: 35 ₪, food (lunch set back)?" for "lunch set me back 35", and days later for "lunch set me back 42". The second reply adds the offer "Want me to do this instantly from now on? …". After Yes, "lunch set me back 38" saves at once.

## 5. Data
No table and no migration in Phases 20 and 21 (schema 8, 34 tables). Phase 22 adds `shortcut` as in `wally-shortcuts/SPEC.md` §5.

`chat_message.card_json` (`MessageExtras`, `SRV/chat/ChatModels.kt`) gains three optional fields (the two below, and `fromHp: Boolean = false`, set on a pending phone reply that continues "Something else" from an HP suggestion, so the HP is not asked again):
- `options: List<PendingAction>`: one entry per suggestion button, in button order. `PendingAction(tool, args)` is the existing class. A read-only question is stored as `tool = "ask"` with `args = {intent, …slots}`.
- `sourceMessageId: String?`: the id of Tim's message the question is about.

Chips (`ChoiceDto(kind, id, name, token)`), new kinds:
| kind | id | name | Completes |
|------|----|------|-----------|
| `suggest` | the index into `options`, `"0"`, `"1"`, `"2"` | what the tap does, for example `Log spending: 35 ₪, food` | `options[index]` |
| `suggest` | `"else"` | `Something else` | sends the source message to the model steps |
| `slot` | `<argName>=<value>`, for example `category=Food` or `type=SNACK` | the value in plain words (`Food`, `Meal`, `Snack`) | the message's existing `pending` action plus that argument |

A turn that continues to the model steps through "Something else" stores `sourceMessageId` on its own reply too, so a retried phone turn and the HP step use the source message's text, not the button's label.

`SendMessageRequest` gains `route: String = "auto"`; values `auto`, `model`, `codex`. The chat outbox item on the client stores it.

Reply `brain` values added: `ask` (a question), `tap` (a turn finished by a suggestion or slot tap), `codex`. The log line gains the same values: `ladder rung=ask ms=…`.

`ai_job.inputJson` of a research job queued by the Codex route gains `"chatId"`.

## 6. Interfaces
| Interface | Input | Output |
|-----------|-------|--------|
| `POST /v1/chat/{id}/message` | `SendMessageRequest` with `route` | unchanged shapes; `route` other than the three values → 400 `invalid: route`; `route = codex` together with `choice` → 400 `invalid: route` |
| Phone and laptop composer | text, then one of three buttons: "Ask model", "Ask Codex", "Send" (icons with content descriptions on the phone; icon plus a text label on the laptop, with Alt+Enter for Ask model and Alt+Shift+Enter for Ask Codex; Enter, Ctrl+Enter and Shift+Enter are unchanged) | a sent message with `route` |
| Question reply | — | fixed sentences: "Which category?", "Was that a meal or a snack?", "Did you mean one of these?", "Did you mean: <label>?" |
| Suggestion labels | — | `Add task "<title>"` + `, due <day>`; `Log spending: <amount> ₪, <category>` (or without the category), followed by ` (<note>)` when a note will be saved; `Log meal: <text>`, `Log snack: <text>`, and `Log food: <text>` when meal or snack is not yet known; `Journal note: <first 40 characters>`; `Check-in: mood <n>, energy <n>, sleep <n>, stress <n>`; `Mark done: <task>`; `Show: <question in plain words>` |
| Codex route replies | — | "Asked Codex. The answer will arrive here in a minute or two."; "Codex is already working on that. Ask again in a few minutes to see the answer."; a saved answer's text; "I can't send dream text to Codex."; "That's too long for Codex (limit 2,000 characters)."; "Codex isn't set up on the home server." |

Day questions (Phase 20), each with its reply sentence when there is data and when there is none:
| Intent | Slots | Reply with data | Reply without |
|--------|-------|-----------------|---------------|
| `ask_spending` | period, category? | "<Period> you spent <total> ₪" + " on <category>." or ": <category> <sum> ₪, …" (top 4 categories) | "No spending logged <period>." |
| `ask_events` (extended) | day or period | as today, for any day; for a period one line per day that has events (at most 12 events) | "Nothing on your calendar <period>." |
| `ask_tasks` (extended) | day or period | "Tasks due <period>: …" (at most the existing `TASKS_MAX`) | "Nothing due <period>." |
| `ask_exam` | name? (no day or period) | "<Title> is on <day> at <time>." or the next 3 exams when no name is given | "I don't see an exam like that." |
| `ask_health` | metric (`sleep`, `steps`, `heart`), day or week | day: "You slept 7 h 20 min on <day>." / "<n> steps <day>." / "Resting heart rate <n> on <day>."; week: the average and the best and worst day | "No <metric> data for <period>." |
| `ask_meals` | what (`list`, `calories`), day | "<Day> you ate: …" / "About <n> kcal <day> (<k> of <m> meals estimated)." | "No meals logged <day>." |
| `ask_checkin` | day or week | day: the four scores; week: the four averages | "No check-in <period>." |

## 7. Edge cases and error states
| Case | Expected behaviour |
|------|--------------------|
| The sorter is off, not ready or times out | No suggestions; missing-piece and guard-word questions still work; other messages go to the model steps as today |
| A suggestion's values cannot all be read from the message (for example no amount) | That suggestion is not offered; with no suggestion left there is no question |
| The same action would appear twice among the suggestions | Shown once |
| A message longer than 200 characters, a Hebrew message, a message that mentions dreams | Never gets a suggestion (the dream add rule and the Hebrew sorter answers of Phase 18 are unchanged) |
| Tim ignores a question and sends something else | The question is dropped; the new message is handled normally; a later tap on the old buttons replies "That question isn't open any more." |
| Tim taps the same button twice, or the message is resent with the same id | The stored reply is returned; nothing is saved twice (existing message-id replay) |
| A slot tap whose value is not one of the offered values | "That question isn't open any more." and nothing is saved |
| A message sent with "Ask model" or reached through "Something else" | Never gets a handler question |
| "Something else" on an HP suggestion | Goes to the laptop or phone model if one is reachable at that moment; otherwise "That needs the laptop to be on." The HP model is not asked a second time |
| "Ask model" with the laptop off and no phone model | The HP model answers under the suggest-only rule |
| "Ask Codex" with text over the research limit or with dream words | The matching refusal sentence; nothing is queued (empty text is already refused with 400 before any route) |
| A tap or a typed answer on a later day than the message the question was about | "That question isn't open any more."; nothing is saved (an entry is never saved under a day Tim did not mean) |
| The top-ranked meaning is "conversation" | No question, whatever else ranks close |
| A question-form message | Never gets a suggestion that would save something |
| "paid 200 for the trip tomorrow", "spent 500 on stuff in total", "I ate too much" | No missing-piece question (the same conditions as the real log rules) |
| The HP model proposes an add with another day's date | Spending, or a meal with a time: a yes/no card that names the day. Anything else: "I didn't get that. Can you say it another way?" |
| The HP model proposes a non-dream add for a message that mentions dreams | "I didn't get that. Can you say it another way?"; nothing is stored in a button |
| An other-day entry written as "3 days ago" | Not handled by code (its number is read as a second amount by the Phase 18 guard); the message goes on |
| A reworded add that names another day ("lunch set me back 35 yesterday") | No suggestion button for it (a button would save under today); the message goes to a model |
| A Codex answer arrives while a question is open in that chat | The answer becomes the newest message, so the open question is closed |
| The HP model proposes a dream entry | Saved directly as in Phase 18; never turned into a button |
| "Ask Codex" when Codex reports its usage limit | The existing limit text arrives as the answer message |
| The chat is deleted before the Codex answer arrives | The answer is saved in research as today; no message is added |
| A day question names a period the parser does not know ("the summer") | Code does nothing; the message goes on |
| A weekday in a spending question ("on Friday") | The most recent past Friday; today if today is Friday |
| "this week" on a Sunday | Sunday only so far |
| An other-day add named by a bare weekday or with "for" before the day ("spent 35 on a gift for friday") | Code does nothing; the message goes on (only `yesterday`, `last <weekday>` or a past date counts) |
| An other-day add for a future day ("spending tomorrow") | Code does nothing; the message goes on |
| An other-day add with a date not yet reached this year ("paid 200 for the concert on 20/11" on 16 November), or more than 60 days back | Code does nothing; the message goes on. It is never read as last year's date |
| An other-day card for a day in another year (30 December, typed on 5 January) | The card shows the year: "… for Wednesday 30 December 2026" |
| The HP model proposes an add dated in the future or more than 60 days back | "I didn't get that. Can you say it another way?"; no card |
| A question or an add with two day phrases ("what's on today and tomorrow") | Not answered or suggested by code; the message goes on |
| A weekday in an events or tasks question on that same weekday ("what's on Friday" on a Friday) | Next week's Friday, the same day the task rules give; today is asked for with "today" |
| "Ask Codex" for a question Codex is already working on | "Codex is already working on that. Ask again in a few minutes to see the answer."; no second job |
| A message typed offline on an earlier day and sent today | No handler question and no other-day card for it ("yesterday" would name the wrong date); it goes on to a model. Unchanged from Phase 18: a plain clear log typed offline is saved under the day it is sent |
| A journal note with "and" plus something that looks like a second command ("journal: went shopping and spent 200 on shoes") | Phase 21 does not split it; no spending is saved by code |
| "The coffee was 18" with a coffee already logged today | Phase 21 does not treat it as a correction (it may be a second coffee); the message goes on |
| "I ate something weird and now my stomach hurts" | No "meal or snack?" question |
| A typed answer that equals a button label, on a later day than the question | "That question isn't open any more."; nothing is saved |
| An exam question with a day or period ("my exams next week") | Not answered by code on any path (rules or sorter) and never offered as a button; the message goes on |
| A bare word such as "sleep" or "schedule" | Not answered by the new rules (they need "my …" or a day or period) |
| An other-day card is approved twice or from two devices | One row (existing approval rule) |
| Old 1.17.0 app with the new server | Works: new chips show as ordinary chips; the two buttons are absent; `route` defaults to `auto` |
| Phase 21: a correction when two of today's entries match | The usual "Which one do you mean?" chips, then the card |
| Phase 21: a correction for an entry of another day | Code does nothing; the message goes on |
| Phase 21: an event whose start is not later than now | Code does nothing; the message goes on |
| Phase 21: two commands joined by a semicolon | Not split; handled as one command exactly as today |
| Phase 21: a two-part message where one part is an edit | Not split; the whole message goes on |
| Phase 21: a two-part message where one part would make a card (another day, an existing check-in) | Not split; the whole message goes on |
| Phase 21: a two-part message where the second add fails while saving, after both passed the check | The first stays saved with its Undo; the reply is its confirmation plus "I couldn't do the second part. Say it again on its own." |
| Phase 21: an event with no length | Code does nothing; the message goes on |
| Phase 21: an event with a length code cannot read exactly ("for an hour and a half"), a leftover day or repeat word ("the day after tomorrow", "every week") | Code does nothing; the message goes on |
| Phase 21: an event for a date that has just passed ("on 10/11" typed on 16 November) | Code does nothing; never a card for next year |
| Phase 21: a two-part message whose first part is a task or a meal, joined by a plain "and" | Not split (the "and" may belong to the task or meal text); it is split only at "then" / "and then" |
| Phase 21: a log plus a question ("spent 35 on lunch and what's on today") | Split: the spending is saved with the note "lunch" and the question is answered |
| Phase 21: "change my mood to 4" | A check-in correction, never a spending correction |
| Phase 21: "change the dinner to 8" or "the dinner should be 8" (a bare number, no old value) | Not a correction (it may mean 8 o'clock); the message goes on. "change the dinner to 8 nis" or "change the dinner spending to 8" is a correction |
| Phase 21: "my sleep was 4, not 3" | Not a correction (it may be about hours); the message goes on. "my sleep quality was 4, not 3" is a correction |
| Phase 21: a correction that names nothing logged today ("the coffee should be 18", no coffee today) | Goes to a model; no "Did you mean…?" question |
| Phase 21: a correction or an event typed offline on an earlier day | Code does nothing; the message goes on |

## 8. Technical decisions
| Area | Choice | Version | Source |
|------|--------|---------|--------|
| Stack | Kotlin, Ktor, SQLDelight, Compose Multiplatform | unchanged, as pinned in `gradle/libs.versions.toml` | project file |
| Embedding model | `embeddinggemma` in the HP's Ollama, already used by the sorter | unchanged | `docs/plan/wally-brain/DECISIONS.md` B5 |
| Questions and buttons | The Phase 18 chip and pending-action mechanism, extended (§5) | — | `SRV/chat/ladder/Ladder.kt`, `SRV/chat/ChatModels.kt` |
| Codex route | The existing `research_question` approval and `ServerCodex.approve` path, with the approval created and approved in one step | — | `SRV/codex/ServerCodex.kt` |

No new dependency and no new model in any of the three phases.

## 9. Constraints
- Every server write goes through `ServerWriter`. Every tool call goes through `ServerToolExecutor` with the one tool allowed.
- Dream wall: no suggestion, question, option or Codex text is built from a message that mentions dreams.
- No personal data leaves Tim's devices except through the Codex route, which sends only the text Tim typed and pressed "Ask Codex" on.
- One HP generation at a time (the existing generation lock); the sorter's embedding call keeps its 2,000 ms limit.
- Agents never edit `Overview.md`, `phases.md`, `scripts/`, `.claude/`; Overview changes are given to Tim as exact lines. Never edit `C:\Users\user\Projects\WallyCalendar`.

## 10. Compatibility
- Kept from Phases 17–19: `ServerWriter`, the tool allow-list, server-authorised Undo, `ApprovalSink`, message-id replay, the phone turn and its expiry rules, the fixed answer shape `ModelDecision`, one voice file opening every brain, the Phase 18 guards and the router's add and edit rows. Loosened on purpose, each listed in the changes table of DECISIONS.md: three ask rows are replaced by rows that take a period, a clearly past day at the edge of a spending or timed-meal message makes a card, and (Phase 21) a two-command message may be split.
- Changed locked decisions are listed in `DECISIONS.md` and approved by Tim at each phase's approval step.
- Phase 20 depends on Phase 19 being closed (H28). Phase 21 depends on Phase 20. Phase 22 depends on Phases 20 and 21.

## 11. Changes to the taught-shortcuts plan for Phase 22
The plan in `docs/plans/wally-shortcuts/` is carried out as written, with these changes only:
1. Numbering: its Phase 20 is Phase 22; its Tasks 20.1–20.7 are Tasks 22.1–22.7; it ships 1.20.0 (versionCode 16); it follows Phase 21; Wally Voice is Phases 23–24.
2. L7: a turn finished by a suggestion tap (`brain = tap`, chip kind `suggest`, not `else`) also teaches. The template is built from the source message's text (`sourceMessageId`), the result is the tapped option. Slot taps and HP suggestions teach in the same way when they end in exactly one automatic add. The shortcuts plan's rule "a turn that carried a chip teaches nothing" does not apply to these taps, and the row's `last_message_id` is the tap message's id, so Undo of the tapped save withdraws the use exactly as the shortcuts plan describes.
3. L5 and L8: a read-only question can be taught when the source message's template has no blank and the tapped option is `tool = "ask"`. The shortcuts plan's question refusal and its other-day refusal stay for the four add tools and do not apply to a question shortcut. Two uses are "the same result" when `intent` and every slot are equal as stored (the period slot is stored as its word, for example `last_month`, not as dates). A shortcut for a question runs the same answer function with the period resolved on the day it runs.
4. The `shortcut.tool` column also takes the value `ask`; `fixed_args` then holds `intent` and the slots. The Settings card shows it as "Question: <question in plain words>".
5. The ladder position is unchanged: the shortcut check runs after the chip step and the blocked check and before the router.
6. Pass marks L14 stay, with 5 of the 20 taught wordings and 8 of the 30 near-variations being question shortcuts.
7. A question wording that uses `today`, `tonight`, `tomorrow`, a weekday or a date cannot be taught (the shortcuts plan turns those words into a date blank, and a question shortcut has no blank). Teachable question wordings have no period word, or one of exactly `yesterday`, `last night`, `this week`, `last week`, `this month`, `last month`; a month name ("in October") is not teachable.
8. The offer and the confirmation for a question shortcut say "answer" and "show" instead of "save".
9. The HP model teaches only through the Yes tap on its suggestion, because after Phase 20 it never saves an add by itself.
