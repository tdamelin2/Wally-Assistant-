# Checklist Phase 18: the new chat brain and phone chat screens - Wally 1.16.0

What this is for: you check with your own eyes that Wally answers common questions fast (without waiting for a model), that the phone chat looks like the design pictures, and the Phase 17 checks that were still open. Do the setup first: `ops/deploy-phase18.md`. Tick a line only when it really passed; write "skipped" next to a line you did not run, and "pending" next to a line that needs a day or a Sunday to happen.

"Laptop off" means: quit Ollama on the laptop (tray icon, then Quit). When you finish that section, start Ollama again.

Who does what: you send the messages from the phone. Right after each group, Wally's builder reads the server log (`ssh hp sudo -n journalctl -u assistant -n 100 --no-pager`) and copies the `ladder rung=... ms=...` lines into the report. You do not run that command. The marks: a line with `rung=code` must show ms of 1000 or less; a line with `rung=sorter` must show ms of 2000 or less.

## Release
- [ ] Both apps show version 1.16.0 (Settings).
- [ ] The phone and the laptop both show `Connected` under Settings > `Test connection`.
- [ ] Everything saved before the upgrade is still listed on both apps.
- [ ] `ssh hp "sudo -n systemctl status assistant --no-pager"` shows `active (running)` (the builder checks).

## Speed of the new brain (laptop on; builder reads the log after each group)
- [ ] Group 1: send 5 clear commands from the phone (a new task, a new check-in, and similar plain requests of your own). Each one is done at once with an Undo row or a card. Builder: the 5 `ladder rung=code ms=...` lines are all 1000 or less. Lines copied into `reports/phase-18.md`: ______
- [ ] Group 2: send 3 questions about your data in your own words, for example "what have I got on today then". Each gets a real answer from your data. Builder: each `ladder rung=...` line is 1000 or less for `code` and 2000 or less for `sorter`. Lines copied: ______
- [ ] "I spent 35 on lunch yesterday" is NOT saved by itself (look in Spending: nothing new appears from this message). It goes to the model, which answers or asks.

## Laptop off (slow path)
- [ ] Quit Ollama on the laptop (tray icon, then Quit).
- [ ] Send one ordinary conversation message (not a command). The HP answers it, and the line "Thinking on the home server. This can take about a minute." shows while you wait.
- [ ] Ask for one edit of an existing item (for example move a task to another day). A card shows with a Yes and a No button. Tap No, so nothing is changed.
- [ ] Create two tasks with similar names, then ask to change one of them by that shared name. Two pick chips show; tapping one opens the card for that task.
- [ ] Start Ollama on the laptop again.

## Phone chat screens against the design pictures (D1 to D16)
The builder took pictures of the phone (`logs/p18-chat-*.png`: main chat, open drawer, a card, an Undo row, chips, Memory). Put each next to its design frame and compare by eye. Write what differs next to the line.
- [ ] D1 Main: no big "Chat" title and no "Synced just now" line above the chat; the chat header is the top of the screen.
- [ ] D2 Main: the chat list opens from a round three-line menu button (label "Open chat list").
- [ ] D3 Main: a pencil button starts a new chat (label "New chat").
- [ ] D4 Main: the chat name is on the left beside the menu button, with "Wally" in small letters under it.
- [ ] D5 Main: a thin line sits under the header.
- [ ] D6 Main: the message box is one white rounded pill with a round green button with an up-arrow inside it.
- [ ] D7 Main: the placeholder reads "Message Wally…".
- [ ] D8 Main: the pill is a little away from both sides and from the bottom bar.
- [ ] D9 List: the drawer covers the full height from the top of the screen, and the dark cover also dims the bottom bar. Tapping the dark cover or pressing Back closes it.
- [ ] D10 List: the drawer is white with rounded right corners.
- [ ] D11 List: "Chats" is at the left and "N of 10" at the far right.
- [ ] D12 List: the weekly report is pinned at the top with a pin icon and a light green row, then a thin line before the other chats.
- [ ] D13 List: the selected chat has a light grey rounded background with no odd corners.
- [ ] D14 List: names, previews and times each fit on one line (long ones end in "...").
- [ ] D15 List: the note about the 11th chat sits directly under the last chat.
- [ ] D16 List: "Memory" (with a bulb) is above "New chat" (full width, with a plus), stacked, not side by side.

## Extra differences found and fixed in the frame-by-frame check (D17 to D30)
Compare each with the same frame. Phone only.
- [ ] D17 Offline banner (card frame): warm yellow strip with a crossed Wi-Fi icon, not red.
- [ ] D18 Chat bubbles: Wally's bubbles are white with a thin line; your bubbles are green; the list has room above the first message.
- [ ] D19 Typing bubble: white with three green dots.
- [ ] D20 Undo button: rounded button with a small undo arrow and a countdown.
- [ ] D21 Pick chips: green outline, white fill, rounded.
- [ ] D22 Queued message (send one with the phone offline, then reconnect): a slightly faded bubble with a small clock inside it.
- [ ] D23 Card box: white, rounded, with a green line while it waits for your answer; a grey line once it is done or rejected.
- [ ] D24 Card header: a small calendar icon beside the title, in green.
- [ ] D25 Card content (an edit): the item's name, then "From" (old value, crossed out) and "To" (new value), side by side.
- [ ] D26 Card buttons: "No" (outlined) and a wide filled "Yes" button.
- [ ] D27 Memory header: a back arrow ("Back to chats") and "Memory".
- [ ] D28 Memory intro: a short grey explanation under the header; the whole screen scrolls.
- [ ] D29 Memory notes: white rounded cards with a name, a date and up to 3 lines of text; tapping one expands it.
- [ ] D30 A note still being written shows faded with a small spinner and "Summarizing…"; the empty Memory screen says "Nothing remembered yet".

## Three frames Phase 18 does not rebuild (look and compare only)
A difference found here is written down for a later phase. It is not fixed now.
- [ ] `LaptopChat` (the laptop chat screen) against its frame. Differences: ______
- [ ] `BrowseSettings` (phone Settings) against its frame. Differences: ______
- [ ] `LaptopSettings` (laptop Settings) against its frame. Differences: ______

## Phase 17 checks still open (copied from `reports/phase-17.md`; do them in daily use)
Write the date and "passed" or what went wrong next to each.
- [ ] SC-5 (laptop off): ask "what's on today?" from the phone with Ollama quit on the laptop. A real answer comes within 3 minutes.
- [ ] SC-6: "Make a study guide on <your course>" never asks for an ID, and shows chips when more than one course matches.
- [ ] SC-8: start chat number 11; the oldest chat is archived and its summary shows in Chat > Memory within 2 minutes.
- [ ] SC-9: check-in reminder arrives within 15 minutes of the set time when the check-in is missing; none when it is done; none between 23:00 and 07:00; at most 6 a day. Needs one day.
- [ ] SC-10: with the laptop off, ask for research from the phone; it finishes. Builder: the log shows a `codex exec` line.
- [ ] SC-11 (real use): a dream is never quoted unless you used a dream word, never shows in reports, never goes to Codex.
- [ ] SC-12: five real chats, each 1 to 4 sentences unless you asked for more, shows a real value when it matters, and never shows an ID. Fill in the table in `reports/phase-17.md`.
- [ ] Weekly report: the Sunday 19:00 report shows up. Pending, Sunday 2026-11-15.
- [ ] AC-16 (for the three frames above): see the three lines under "Three frames".
