# Checklist Phase 20: Wally handles more messages by itself - Wally 1.18.0

What this is for: you check that Wally answers day questions and logs other-day entries by itself, asks you a short question (with buttons) when a message is unclear, and that the new "Ask model" and "Ask Codex" buttons work. Do the setup first: `ops/deploy-phase20.md`. Tick a line only when it really passed; write "skipped" next to a line you did not run. Use only made-up test entries and delete them at the end.

The builder reads the server's log after each group of messages to check the speed: code answers in 1,000 ms or less, the sorter in 2,000 ms or less, a question with buttons in 2,500 ms or less. You do not need to do that part.

## Part 1: your real messages (AC-16)

This part checks the rules against your own old chat messages. The messages stay on your laptop and nobody opens them except you. Do the steps in this order.

- [ ] Step 1, you: save your last messages from the home server to the laptop. This prints only "saved N messages" (AC-16):

```
powershell -ExecutionPolicy Bypass -File ops\export-real-messages.ps1
```

- [ ] Step 2, the builder: runs the real-messages test once. It writes the file `logs\real-messages\review.txt` (AC-16). Wait until the builder says it is done.

- [ ] Step 3, you: open the review file in Notepad (AC-16):

```
notepad logs\real-messages\review.txt
```

- [ ] Step 4, you: read the lines above the first line that says "(goes to a model)", at most the first 100. Each line shows your message and what Wally would do with it. Where what Wally did is NOT what you meant, change `[ ]` to `[x]` at the start of that line. Then save the file and close Notepad (AC-16).

- [ ] Step 5, the builder: runs the real-messages test again. It must print "marked wrong: 0" and no line starting "regressed:". Tell the builder in your own words what kind of sentence each marked line was and what you meant, so the rule gets fixed (AC-16).

## Part 2: one message for each situation (AC-12)

Type each message in the chat on the laptop app, then repeat a few on the phone. "No model" means the answer appears at once, without the words appearing one by one.

- [ ] A, a day question: send "how much did I spend on food last month". Wally answers at once with a sentence like "Last month you spent ... on food." (AC-12)
- [ ] B, another day: send "spent 35 on lunch yesterday". Wally says it put that on a card. The card names the full date, for example "Add spending 35, food (lunch) for Sunday 15 November". Nothing is saved yet. Tap Yes: it is saved under yesterday's date. Delete it afterwards. (AC-12)
- [ ] C, a missing piece: send "spent 35". Wally asks "Which category?" with one button for each spending category and "Something else". Tap Food. The usual confirmation appears with Undo. Tap Undo. (AC-12)
- [ ] C2, the same by typing: send "spent 35" again, then type just "food". The same confirmation appears. Tap Undo. (AC-12)
- [ ] D, a suggestion: send "lunch set me back 35". Wally asks "Did you mean: Log spending ...?" with the buttons "Yes" and "Something else". Tap "Something else": the message goes to a model and the model answers. Then send it again and tap Yes: it is saved with Undo. Tap Undo. (AC-12)
- [ ] E, a guard word: send "I need to call the dentist tomorrow". Wally asks if you meant adding a task, with "Yes" and "Something else". Tap "Something else". Then send "I need to vent about today": no question appears, a model answers. (AC-12)
- [ ] F, plain conversation: send "I'm tired today, any ideas?". No question appears; a model answers. (AC-12)
- [ ] G, Ask model: type "add task buy milk" but press "Ask model" instead of Send. A model handles it, not the quick rules. If a task was made, delete it. (AC-12)
- [ ] H, Ask Codex: type "best protein sources for vegetarians" and press "Ask Codex". The reply shows a finished card "Ask Codex: ..." and the text "Asked Codex. The answer will arrive here in a minute or two." (AC-12)
- [ ] I, laptop off: quit Ollama on the laptop (tray icon, then Quit) and turn on the phone's power saving. Send "lunch set me back 35". The home server's model proposes one suggestion with "Yes" and "Something else", and nothing is saved until you tap Yes. Tap "Something else": Wally says "That needs the laptop to be on." Then start Ollama again and switch power saving off. (AC-14)

## Part 3: the three buttons (AC-13)

- [ ] Phone: next to Send there are two round buttons, "Ask model" and "Ask Codex". Both work, and a button does nothing when the text box is empty. (AC-13)
- [ ] Laptop: under the text box there is a row with the two buttons. Press Alt and Enter together for "Ask model", and Alt, Shift and Enter together for "Ask Codex". Plain Enter still sends normally. (AC-13)
- [ ] Narrow the laptop window: the buttons wrap to a second row and are still all visible. (AC-13)

## Part 4: Codex over time (AC-13)

- [ ] Offline Ask Codex: on the phone, switch on airplane mode. Type "how long does it take to learn Hebrew" and press "Ask Codex". The message waits in the outbox. Switch airplane mode off. It is sent as an Ask Codex question (the card and the "Asked Codex" text appear), not as a normal message. (AC-13)
- [ ] The answer arrives: keep the chat open after pressing "Ask Codex". Within about 5 minutes a new message from Wally appears with the answer, without you doing anything. (AC-13)
- [ ] Leave and come back: press "Ask Codex" on a new question, then leave the Chat screen for a different screen. When Codex is done, open the chat again: the answer is there. (AC-13)

## Part 5: laptop off and the phone model unavailable (AC-14)

- [ ] Quit Ollama on the laptop and switch the phone's power saving on. Send one unclear message such as "that thing for the plumber, 120". The home server proposes one suggestion. Nothing is saved until you tap Yes. Afterwards start Ollama again, switch power saving off, and delete anything that was saved. (AC-14)
