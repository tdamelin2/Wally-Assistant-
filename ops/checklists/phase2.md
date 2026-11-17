# Phase 2 checklist: Charts tab (PLAN Task 2.4)

Do this on the installed laptop app (Wally 1.1.0) and on the phone app, with the server stopped (turn it off first, or turn on airplane mode on the phone). The charts read only what is stored on the device, so they must work offline. Use the entries you already have; add a few made-up ones if a chart is empty.

Screen labels used below: bottom tab `Charts`; the four chart titles `Check-in scores, last 30 days`, `Spending by category, this month`, `Spending per month, last 6 months`, `Tasks done per week (weeks start Sunday), last 8 weeks`; the legend names `Mood`, `Energy`, `Sleep`, `Stress`.

Before you start, hand-count from the Log lists (write the numbers on paper):
- For the check-in chart: the check-in dates of the last 30 days and their four scores.
- For the spending charts: the total per category for this month, and the total for each of the last 6 months.
- For the tasks chart: how many tasks you finished in each of the last 8 weeks (a week starts on Sunday; a task counts on the day you ticked it done).

Checks:
- [ ] The Charts tab opens with the server stopped. The top bar says "Offline" (or similar) and nothing crashes.
- [ ] The four charts are visible and the page scrolls down to the last one (on the phone too).
- [ ] Check-in chart: one coloured line per score, the legend names `Mood`, `Energy`, `Sleep`, `Stress`. A day without a check-in is a gap in the line (the line breaks). A day with a check-in but no neighbours is a single dot. The scale on the left is 1 to 5.
- [ ] Check-in chart values match your hand-count for at least three different days.
- [ ] `Spending by category, this month`: seven bars (Food, Transport, Shopping, Bills, Fun, Health, Other); the text under the chart shows each total like `Food ₪12.50`. The totals match your hand-count. A category with no spending shows `₪0.00`.
- [ ] `Spending per month, last 6 months`: six bars, the current month last. The text under the chart shows each month's total like `2026-11 ₪12.50` and matches your hand-count. An entry dated the last day of a month belongs to that month, not the next one.
- [ ] `Tasks done per week`: eight bars, the current week last. The text under the chart lists each week's start date (month-day, a Sunday) and count; they match your hand-count, including tasks finished more than 7 days ago.
- [ ] Save a new spending entry (Today > `Spending`): going back to `Charts` shows the new total without restarting the app.
- [ ] Delete a spending entry in Log > `Spending`: its amount disappears from the charts.
- [ ] With no entries at all (or a brand-new test database) the charts show empty axes and zeros, not an error.
