# Nutrition estimates checklist (PLAN Task 6.5)

Every 15 minutes the home server (the HP) asks its small AI model for a calorie guess for up to 5 meals that have none yet, and writes the guess next to the meal. You see it as "≈ … kcal" in the Meals list after you Sync. Run this on BOTH apps (phone and the installed laptop app, Wally 1.18.0), after `ops/ollama-hp.md` is done. Use the Settings tab `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run.

How long the backlog takes: the server does 5 meals every 15 minutes, which is 20 meals per hour. Count your meals first, then wait for meals divided by 20 hours.

Backlog:
- [ ] Count the meals in the Meals list (all of them, since you started logging). Meals: ______ . Hours to wait = meals / 20 = ______ .
- [ ] After that time, Sync. Every meal shows "≈ … kcal" with protein, carbs and fat. Meals still without a guess: ______ (should be 0; a meal whose text confuses the model may stay empty, write it here).
- [ ] The numbers look reasonable (for example "two eggs and toast" is a few hundred kcal, not 5 and not 5000).

A new meal:
- [ ] Log a new meal on the phone, for example "pasta with tomato sauce". Sync. It shows "nutrition pending".
- [ ] Within 30 minutes (the server runs every 15 minutes), Sync again. The meal shows "≈ … kcal". Minutes it took: ______ .
- [ ] Sync the laptop app: the same meal shows the same guess there.

Editing a meal:
- [ ] Edit the text of that meal (for example "pasta with cheese") and save. It shows "nutrition pending" again (the old guess is gone).
- [ ] Within 30 minutes and after Sync, it shows a new guess for the new text.

Chat:
- [ ] In the Chat tab ask `What did I eat yesterday?` The answer lists yesterday's meals (and may mention the calories).

The server:
- [ ] While the briefing is being written at 7:00, the meal guesses wait (only one AI job at a time). After the briefing, the guesses continue.
- [ ] `ssh hp`, then `free -h`: available memory stays above 300 MB while a guess is running.
