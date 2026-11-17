# Checklist Phase 19: Wally answers on the phone itself - Wally 1.17.0

What this is for: you check that the phone can chat by itself (with a small AI model inside the phone) when the laptop is off, that your private data stays on the phone in the airplane test, and that the battery holds up. Do the setup first: `ops/deploy-phase19.md`. The model file must already be on the phone (`ops/phone-model.md`, section 8). Tick a line only when it really passed; write "skipped" next to a line you did not run.

"Laptop off" means: quit Ollama on the laptop (tray icon, then Quit). Do not shut the laptop down. When you finish that line, start Ollama again.

- [ ] Model card: open the chat settings on the phone. A card shows the phone model's name and its size. Write them here: ______
- [ ] Privacy A: switch on airplane mode, then switch Wi-Fi off. Open Settings > Phone brain and press Test phone brain once. A greeting appears from the phone model, with nothing else connected. Switch both back on afterwards.
- [ ] Privacy B: switch Wi-Fi on and Tailscale off. Press Test phone brain (Settings > Phone brain) 10 times in a row. Each press gets an answer, and the number of open tasks stays the same as before. Switch Tailscale back on afterwards.
- [ ] Laptop off: quit Ollama on the laptop. Send one ordinary conversation message and one question about your data. The words appear one by one; the first word shows within 3 seconds and the whole answer is complete within 10 seconds. Write the times here: ______ Then start Ollama again.
- [ ] If you have no lunch logged, first log "lunch 35 shekels" (answer Yes). Then send "that lunch was 45, not 35". A card shows for you to confirm the change (the Yes and No buttons). Tap No, so nothing is changed. Afterwards delete the test lunch.
- [ ] Power saving: switch the phone's power saving on and send a message that needs the model, such as "I feel tired this week, any advice?" (not a list command). The home server (HP) answers instead, and the slow line shows while you wait. Switch power saving off afterwards.
- [ ] Battery: unplug the phone while it is above 50%. Send 10 messages in 10 minutes. The battery goes down by 5% or less. Start and end percentages: ______
- [ ] Close the app in the middle of an answer, wait 4 minutes, then open it again. The unfinished reply shows an "interrupted" text, and nothing is saved twice (look in Tasks and Spending for doubles).