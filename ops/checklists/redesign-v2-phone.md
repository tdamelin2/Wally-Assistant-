# Checklist: the new green phone look (Wally 1.13.0)

Run it on the phone app. Open each screen next to the matching frame in `docs/plan/wally-redesign-v2/canvas/` (the file name is in brackets). Text in the canvas frames is made-up sample data, so only compare layout, colours and wording of labels and buttons. Tick a line only when it really passed; write "skipped" next to a line you did not run. Chat is not part of this checklist (it is rebuilt in Phase 17).

## Differences from the canvas, for you to judge
- [ ] Files and Mail have a + in the bar area instead of the "New doc" and "New email" buttons at the top of the canvas frame. Is that fine?
- [ ] Headings read like "Today, Nov 11" instead of the bare word "Today" (the app does not repeat a word that is already on a button). Is that fine?
- [ ] The Files heading reads "Documents" (not "Files", which is the chip name). Is that fine?

## Today and the + picker
- [ ] Today (`Main`): date and "Synced" line on top, briefing card, tasks, exam tiles; pull down to sync.
- [ ] The + on Today (`AddSheet`): "What do you want to log?" with six tiles (Task, Check-in, Spending, Journal, Meal, Dream) and Cancel.

## Log
- [ ] Check-ins (`LogHistoryLink`): chips above the bar, today and yesterday as cards with dots, "See all check-ins".
- [ ] Check-in history (`CheckinHistoryDots`): month button at the top, five-dot meters.
- [ ] New check-in (`CheckinAdd`): scores start at 3, optional note, date today.
- [ ] Spending (`SpendingWeek`): this week by category, today's rows, "See all spending".
- [ ] Spending history (`SpendingHistory`).
- [ ] New spending (`SpendingAdd`): amount, category tiles, note, date.
- [ ] Journal (`JournalMain`): today and yesterday as cards, "See all entries".
- [ ] Journal history (`JournalHistory`).
- [ ] Write a journal entry (`JournalAdd`): full screen, Save at the bottom; close it with text typed and open it again: the draft is still there.
- [ ] Meals (`MealsMain`): today's total, today's meals, "See all meals"; "Look up" only on failed rows.
- [ ] Meals history (`MealsHistory`).
- [ ] New meal (`MealsAdd`): Meal or Snack, what you ate, date and time, Cancel and Save.
- [ ] Tasks (`TasksMain`): grouped by Overdue, Today, This week, Later, No date; swipe right = done, swipe left = delete.
- [ ] Completed tasks (`TasksHistory`): done tasks by the day finished, month button.
- [ ] New task (`TasksAdd`): title, due chips (None, Today, Tomorrow, Pick date), no goal option.
- [ ] Dreams (`DreamsMain`): recent dreams, "See all dreams".
- [ ] Dream history (`DreamsHistory`): month counts, dreams by week; no pattern summaries anywhere.
- [ ] Write a dream (`DreamsAdd`): full screen, mood on waking, lucid checkbox, tags; the draft is kept.
- [ ] A dream opened (`DreamDetail`): read first, interpretation, "Interpret again", Edit button.

## Insights
- [ ] Charts (`InsightsCharts`): green charts in cards.
- [ ] Health (`InsightsHealth`): last-night tiles, sleep and steps charts, workouts.
- [ ] Screentime (`InsightsScreentime`): All, Laptop, Phone and This day, 7 days; banner, minutes per label, top apps.
- [ ] Goals (`InsightsGoals`): goal cards with step progress, Dream week card, "New goal".
- [ ] Label apps (`ScreentimeLabel`): Phone and Laptop, labelling, suggestions, rules (swipe left to delete, then Undo).

## Browse
- [ ] Files (`BrowseFiles`): search, folders as chips, documents, + for a new document.
- [ ] Study (`BrowseStudy`): course cards with exam countdown, study set, quiz and summary buttons.
- [ ] All exams (`StudyExams`): upcoming with countdown, past, Add exam; tap = edit, swipe left = delete (then Undo).
- [ ] Mail (`BrowseMail`): unread, inbox suggestions with Approve and Reject, drafts.
- [ ] Hardware (`BrowseHardware`): describe a problem, past checks.
- [ ] Settings (`BrowseSettings`): grouped cards.

## Success criteria SC-1 to SC-11
- [ ] SC-1: the whole app is Forest green in light mode.
- [ ] SC-2: the phone always shows the phone layout (bottom bar), whatever the screen width or rotation.
- [ ] SC-3: the bottom bar reads Today, Log, Chat, Insights, Browse, and chips sit directly above it on Log, Insights and Browse.
- [ ] SC-4: the + on Today opens the picker with six types; on each Log chip it opens only that type's form.
- [ ] SC-5: each Log chip's main screen shows recent items and a "See all ..." button that opens a month history.
- [ ] SC-6: a half-written journal entry and a half-written dream are still there after closing the screen and restarting the app, until you Save or Clear.
- [ ] SC-7: tapping a dream opens it to read with its interpretation; there is no "Find patterns" anywhere.
- [ ] SC-8: task screens have no goal option; a new task starts with no due date.
- [ ] SC-9: "Look up" shows only on failed meal estimates and inside a meal's edit screen.
- [ ] SC-10: Screentime offers All, Laptop, Phone and This day, 7 days; Label apps offers Phone and Laptop.
- [ ] SC-11: every frame above matches the app.

## Extra checks
- [ ] Phone in system dark mode still shows the green light theme with a readable status bar.
- [ ] The laptop app is still usable with the phone-style Log screens (not redesigned until Phase 16).
