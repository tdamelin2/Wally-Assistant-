# Checklist: the new look (5 hubs, chips, amber colours, no repeated labels)

Run it on BOTH apps: the phone app and the installed laptop app. For every screen below, look at it four times: phone light, phone dark, laptop light, laptop dark (change dark mode in the phone or Windows settings). Tick a line only when it really passed; write "skipped" next to a line you did not run.

What "good" means for each screen: nothing is unreadable, nothing is cut off, and no word shows up twice on the same screen (for example a heading that repeats the name of the button you just tapped).

The 5 hubs (the big buttons: bottom bar on the phone, left side on the laptop):
- [ ] Today: phone light, phone dark, laptop light, laptop dark
- [ ] Log: phone light, phone dark, laptop light, laptop dark
- [ ] Insights: phone light, phone dark, laptop light, laptop dark
- [ ] Chat: phone light, phone dark, laptop light, laptop dark
- [ ] Browse: phone light, phone dark, laptop light, laptop dark

The chips (the small round buttons under the hub, only Log, Insights and Browse have them). For each chip: phone light, phone dark, laptop light, laptop dark.
- [ ] Log > Check-ins
- [ ] Log > Spending
- [ ] Log > Journal
- [ ] Log > Meals
- [ ] Log > Tasks
- [ ] Log > Dreams
- [ ] Insights > Charts
- [ ] Insights > Health
- [ ] Insights > Screentime (the day button now reads `This day`, not `Today`)
- [ ] Insights > Goals
- [ ] Browse > Files
- [ ] Browse > Study
- [ ] Browse > Mail
- [ ] Browse > Hardware
- [ ] Browse > Settings

The + button:
- [ ] On Today the + opens the new task form.
- [ ] On Log > Check-ins, Spending, Journal, Meals, Tasks and Dreams the + opens the form for exactly that type.
- [ ] There is no + anywhere else (Insights, Chat, Browse).

Resizing (laptop):
- [ ] Drag the window narrower and wider across the phone-size line (about 600 dp, roughly a narrow phone-like window). The same hub and the same chip stay selected; nothing jumps back to Today.

Today:
- [ ] Today shows no mail and no calendar events (mail is in Browse > Mail).
- [ ] Order is: the red Google banner (only when Google is disconnected), `Briefing` (only when there is one for today), `Tasks`, `Exams`.
- [ ] With no exams, `Exams` still shows with `No exams soon`. With no tasks it shows `No open tasks`.
- [ ] When Google is disconnected, the red banner `Google disconnected - run google-auth on the server` still shows on Today.

Look and colours:
- [ ] The main accent colour is amber, matching the app icon.
- [ ] Chart colours (Insights > Charts) are readable in dark mode; check red against orange, they are close in dark mode. If you cannot tell them apart, write it down.

## Fluid UX (Wally 1.12.0)

- [ ] SC-1: On a task row (Today or Log > Tasks), swipe it right. The task is marked done and a `Done` bar with `Undo` shows. Press `Undo` within 5 seconds and the task is back as open. Check it on the phone (finger swipe) and on the laptop (mouse drag).
- [ ] SC-2: Open the app on the laptop and leave it open and idle for 11 minutes. Make a change on the other device (for example add a task on the phone). After the 11 minutes the change appears on the laptop without pressing anything. Do it the other way round too.
- [ ] SC-3: Close the phone app and leave the phone charging with Wi-Fi on for 20 minutes. Then run `adb logcat -s SyncWorker`: it shows a line `sync done at ...` with a time earlier than when you reopen the app (or the `Synced` label time in Settings is earlier than the moment you reopen it).
- [ ] SC-4 phone: In every form with a date or time (task due date, meal, spending, dream, goal deadline, exam) you can set the date and time by tapping `Pick`; no typing is needed. The date reads in your phone's own date style.
- [ ] SC-4 laptop: The same fields show dates as MM-dd-yyyy. Typing `11-10-2026` is accepted; typing `13-01-2026` or `02-30-2026` shows a red hint and the Save button stays grey. `Pick` opens a calendar. An optional date has `Clear`.
- [ ] Animations feel smooth: switching chips cross-fades, rows slide into place when the list changes, ticking a task is a short smooth check, nothing flickers.
- [ ] Pull down on a list (Today, Log, Insights lists, Files, Study, Mail) starts a sync and shows the spinner.
