# Phase 3 checklist: meals (PLAN Task 2.2)

Do this on the installed laptop app (Wally 1.1.0) and on the phone app, with the server running and both apps set up in Settings. Use made-up meals.

Screen labels used below: Today button `Meal`; the meal form has the choices `Meal` and `Snack`, the field `What did you eat?`, the fields `Date (YYYY-MM-DD)` and `Time (HH:MM)`, and the buttons `Save` and `Cancel`; the Log tab `Meals` (the Log tab row scrolls sideways on the phone); row buttons `Edit` and `Delete`; Settings button `Sync now`.

- [ ] After the update, every older entry is still there on both apps (Log > `Check-ins`, `Spending`, `Journal`, `Tasks` show the same entries as before).
- [ ] Today shows a `Meal` button next to `Check-in`, `Spending`, `Journal` and `Task`.
- [ ] Phone: tap `Meal`, leave `Meal` selected, type `pasta with tomato sauce`, `Save`. Log > `Meals` shows `Meal · HH:MM · pasta with tomato sauce` and a second line `nutrition pending`.
- [ ] Press `Sync now` on the phone, then on the laptop: the pasta meal appears on the laptop in Log > `Meals` with `nutrition pending`.
- [ ] Laptop: tap `Meal`, choose `Snack`, type `apple`, `Save`. After `Sync now` on both apps the snack appears on the phone as `Snack · HH:MM · apple`.
- [ ] The time shown is the time you saved (Jerusalem time). The `Date` and `Time` fields start at today and now. A time like `25:99` or a date like `2026-13-45` shows an error message and `Save` stays disabled.
- [ ] An empty text disables `Save`.
- [ ] Edit the pasta meal (tap the row or `Edit`): change the text to `pasta with cheese`, `Save`. After `Sync now` on both apps the new text shows on both and it still says `nutrition pending`.
- [ ] Edit the snack and only change `Snack` to `Meal` (leave the time alone): after saving it shows as `Meal · ...` with the same time as before.
- [ ] Delete the snack with `Delete`: a confirm dialog appears; `Cancel` keeps it, `Delete` removes it. After `Sync now` it is gone on the other app too.
- [ ] A meal typed in Hebrew (for example `שניצל עם אורז`) displays right-to-left, in the list and in the form.
- [ ] Stop the server, save a meal on one app: the bar shows "Offline" and the row has a grey dot. Start the server and press `Sync now`: the dot goes away and the meal appears on the other app.
