# AI tool-calling probe (Phase 13, Task 12.1)

What this is: 22 fixed prompts sent to the XPS Ollama with the full tool registry (`ai/tools/Tools.kt`, 24 tools: 13 read, 9 write, 2 research). One model turn per prompt; the first tool call is checked against the expected tool and the registry's argument validator. No tool is run and no personal data is in any prompt.

Re-run: `set WALLY_TOOL_PROBE=1` (PowerShell: `$env:WALLY_TOOL_PROBE='1'`), then `.\gradlew :shared:jvmTest --tests "*ToolProbe*"` (writes `shared/build/tool-probe/results.md`).

## Result (final run)
Ollama version: 0.40.15  Model: qwen3.5:9b
Date: 2026-11-10T12:19:06.7001239
Registry: 24 tools, one turn per prompt, 120 s total
Score: 19/20 (prompts 1-20), 2/2 (event prompts 21-22)

| # | Prompt | Expected tool | Actual tool | Args valid | OK |
|---|---|---|---|---|---|
| 1 | What tasks do I have open? | get_tasks | get_tasks | yes | yes |
| 2 | How am I doing on my goals? | get_goals | get_goals | yes | yes |
| 3 | Find my notes about the physics lab. | search_docs | search_docs | yes | yes |
| 4 | Draft an email to dana@example.com telling her I will be ten minutes late to the meeting. | draft_email | error: HTTP 500 | - | NO |
| 5 | What courses am I studying and how are my quizzes going? | get_study | get_study | yes | yes |
| 6 | When is my next exam? | get_exams | get_exams | yes | yes |
| 7 | How much time did I spend on my phone this week? | get_screentime | get_screentime | yes | yes |
| 8 | What music have I been listening to lately? | get_listening | get_listening | yes | yes |
| 9 | Do I have any unread emails? | get_unread_mail | get_unread_mail | yes | yes |
| 10 | How did my last laptop hardware check look? | get_hw_checks | get_hw_checks | yes | yes |
| 11 | What did I look up earlier about hummus? | get_research | get_research | yes | yes |
| 12 | What did I eat in the last 3 days? | get_log | get_log | yes | yes |
| 13 | How much money did I spend this week? | get_log | get_log | yes | yes |
| 14 | What is on my calendar this week? | get_events | get_events | yes | yes |
| 15 | Add a task to buy milk tomorrow. | create_task | create_task | yes | yes |
| 16 | Write me a note called Packing list with towels, a charger and a passport. | create_doc | create_doc | yes | yes |
| 17 | Set a goal for me: run a 10k by 2027-03-01. | create_goal | create_goal | yes | yes |
| 18 | Add my linear algebra exam on 2026-12-15 at 09:00. | add_exam | add_exam | yes | yes |
| 19 | Mark the laptop program tiktok.exe as unproductive in my screen time. | set_app_rule | set_app_rule | yes | yes |
| 20 | How many calories are in hummus? | research | research | yes | yes |
| 21 | Schedule a dentist appointment next Tuesday at 15:00 for one hour. | create_event | create_event | yes | yes |
| 22 | Add a weekly event called Football every Sunday from 10:00 to 11:30 starting 2026-11-15, until 2027-01-31. | create_event | create_event | yes | yes |

## All three runs

| Run | Prompts 1-20 | Event prompts 21-22 | Misses |
|---|---|---|---|
| 1 | 18/20 | 2/2 | #4 and #19: HTTP 500 from Ollama |
| 2 | 19/20 | 2/2 | #4: HTTP 500; #18: answered in text without a tool |
| 3 (above) | 19/20 | 2/2 | #4: HTTP 500 |

Gate: at least 16/20 tool calls. Passed in all three runs.

## Notes

- Prompt 4 (draft_email) fails every time with HTTP 500 when the full registry is sent: Ollama answers `{"error":"expected element type <function> but have <parameter>"}`. That is Ollama failing to parse the model's own tool-call text, not a bad argument. The same prompt with only the draft_email tool works 3 out of 3. The chat loop must treat a 500 like this as a failed turn (message to Tim, no crash) and may retry once; a shorter registry description for draft_email is the other lever.
- Prompts 19 (set_app_rule) failed once with the same 500 in run 1 and passed in runs 2 and 3.
- Prompt 18 once got a plain-text answer instead of a tool call (run 2).
- The probe checks the tool name and that the arguments pass the validator (and the label / kind for prompts 12, 13, 19). It does not check that "next Tuesday" resolved to the right calendar date.
- The registry has 24 tools (13 read + 9 write + 2 research), not 26 as the Work ID text says; the Contract list adds up to 24.
## Step 3a changes (2026-11-10, Tim's change request; probe not yet rerun)
- Registry is now 36 tools (13 read, 21 write, 2 research). New add tools: add_meal, add_spending, add_checkin, add_journal. New update tools (one record by id; given fields replace, others stay; no deletes): update_task, update_goal, update_doc (body REPLACE or APPEND), update_meal, update_spending, update_checkin, update_journal, update_exam. queue_job now allows STUDY_GUIDE {courseId}. The tools were not grouped; if the final probe shows qwen3.5:9b struggling with 36 tools, group the update tools (one update_log with kind) - each kind keeps its own validator.
- Ask, do not guess: no required argument has a default; a missing one returns "Missing <field> - ask Tim" and the system prompt tells the model to ask. create_event needs end or durationMinutes (all-day: dates only).
- Update approvals are exactly-once through the local receipt `approval-applied:<approvalId>`; a target that is gone ends the approval FAILED "This <thing> no longer exists".
- Research routing: a general nutrition or word-meaning question (not about Tim's own log) that the model answers without calling `research` is sent back once with an instruction to call it (needsResearchTool in ToolChatLoop.kt).
- Still to do in the probe rerun: add a meal, add spending, update a task, an event without a length (expect a question), a nutrition question (expect research), a well-known explanation (expect a direct answer), a niche number (expect research_question).

## Step 3b probe rerun (2026-11-10 18:29, final registry)
Ollama 0.40.15, qwen3.5:9b on the XPS, 36 tools, the real tool system prompt (ChatContext.toolSystemPrompt), one turn per prompt, 177 s.
- Old 20 prompts: 19/20 (only #4 draft_email failed: the known Ollama HTTP 500 with the full registry, as in the first probe). Event prompts 21-22: 2/2.
- New prompts 23-30: 6/8. Passed: add a meal, add spending, update a task (it first calls get_tasks to find the id), nutrition question -> research, "why is the sky black at night" answered directly, add a journal entry.
- Missed #26 (an event with no length): the model called create_event with a length it invented instead of asking. The validator cannot see an invented value; the "Missing end or durationMinutes - ask Tim" rule only fires when the argument is absent. Watch for this in the manual test.
- Missed #29 (a niche fact, population of a town): the model called `research` instead of proposing `research_question`. The `research` call passed the validator (categories NUTRITION, DEFINITION, APP_CATEGORY), so a niche fact can reach Codex through `research` without an approval card; only a research-memory lookup or a queued job happens, and the answer comes from Codex. The system prompt rule is the only guard here; worth a look in the manual test.
- Grouping rule from the 18:22 note (old < 16/20 or new < 6/8): not triggered (19/20 and 6/8), so the update tools stay separate.
