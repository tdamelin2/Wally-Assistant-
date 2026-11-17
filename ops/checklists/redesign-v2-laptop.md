# Checklist: the new green laptop look (Wally 1.14.0)

Run it with `.\gradlew :desktopApp:run`. Tick a line only when it really passed; write "skipped" next to a line you did not run. This file grows as the laptop screens are rebuilt; the lines below cover the keyboard and the mouse.

## Keyboard and mouse (Task 16.1)
- [ ] Right after the app opens (nothing clicked, no text field in use) press N: the "What do you want to log?" picker opens.
- [ ] Press Esc: the picker closes.
- [ ] On the Tasks chip, hover a task row with the mouse: its Done, Edit and Delete icons appear, and they go away when the mouse leaves.
- [ ] Open Files and press Ctrl+F: the cursor jumps into "Search titles and text".
- [ ] In a text field, pressing N types the letter n and does not open the picker.
- [ ] In Chat, type a question and press Enter: it is sent as before.
- [ ] Open a form in a pop-up window (for example "Add goal" or a form opened from Today) and press Esc: the pop-up closes.
- [ ] In a multi-line field (Journal text, a Note) pressing Enter adds a new line and does not save.
- [ ] In a single-line field of a side form (for example the task Title) pressing Enter saves the form; Esc cancels it.
- [ ] In the journal or dream writer press Ctrl+Enter: the entry is saved.
- [ ] Press Enter twice quickly in a side form: only one entry is saved.

## Today and the Log chips, part 1 (Task 16.2)
- [ ] LaptopToday: the heading reads like "Wednesday, 11-11-2026" with "Synced just now" under it; New (with its N hint) is at the top right; the briefing shows in full; Tasks card says "N open"; Exams shows tiles with course, MM-dd-yyyy date and time; hovering a task shows Done, Edit and Delete and clicking Edit opens the pop-up form.
- [ ] LaptopCheckins: Check-ins chip shows today and yesterday cards with four meters each; clicking a card opens it in the side panel (date read-only); the side form says "New check-in", Cancel with Esc, "Save check-in" with Enter; "See all check-ins" still opens the history.
- [ ] LaptopLog (Spending): "This week" card and today's rows; hover shows Edit and Delete; the side form says "New spending" and "Save" shows the amount and category.
- [ ] LaptopMeals: estimated total, rows with status; "Look up" only on a row that could not be estimated; hover shows Edit and Delete; side form "New meal" with "Save meal"; the date and time can be typed as MM-dd-yyyy and HH:mm.
- [ ] LaptopTasks: groups Overdue, Today, This week, No date; hover shows Done and Delete; side form "New task" with "Save task"; deleting the task that is open in the side panel resets the panel to "New task".

## Journal and Dreams (Task 16.2, part 2)
- [ ] LaptopJournal: the Journal chip lists Today and Yesterday with the time and two lines per entry and "See all entries"; the right pane says "New entry" with the date and time, a big text area, "Draft kept if you leave" and Save with a Ctrl+Enter hint; type, switch to another chip and back: the text is still there; click an entry: it opens in the pane for editing; press Ctrl+Enter several times quickly: only one row is added.
- [ ] LaptopDreams: the Dreams chip groups dreams under This week and Last week ("Last night" for today's), each with two lines, Lucid, tags and either "✓ Interpreted" or an Interpret button; "See all dreams" opens the history; the right pane is "New dream" with mood on waking 1-5, Lucid, tags, the draft note, "Save & interpret" and "Save" (Ctrl+Enter); Save & interpret saves the dream and starts one interpretation (pressing it twice does not queue a second one).
- [ ] LaptopDreamOpen: click a dream: the right pane shows its date with Edit, mood, Lucid, tags, the full text and the Interpretation card (with "Interpret again") or an Interpret button; Edit switches the pane to the editor of that dream and Save keeps the new-dream draft untouched.

## Insights (Task 16.3)
- [ ] LaptopInsights: the Charts chip shows the four cards (check-ins 30 days with legend, spending by category, spending per month, tasks done per week) in two columns; make the window narrow and they stack into one column.
- [ ] LaptopHealth: "From Garmin · synced HH:mm" line; tiles Sleep last night, Steps yesterday and Resting HR (a missing value shows a dash, never 0; an older heart rate shows its date); sleep and steps charts for 30 days; workouts; "Days" lists the last 7 days, newest first.
- [ ] LaptopScreentime: All/Laptop/Phone and This day/7 days on top; Minutes per label, Top apps and Listening on the left; the "Label apps" panel on the right has a Phone/Laptop pill, "N apps not labelled yet" with Productive/Unproductive/Neutral buttons, Suggest labels with Accept, and rules with Change and a hover Delete (Undo appears); changing the filters on the left keeps the panel as it is.
- [ ] LaptopGoals: goal cards on the left (title, why, "x of y steps", "No steps yet" with Plan steps, the Dream week card with "Coach me"); the shell's New opens "New goal"; the selected goal on the right shows its title, Edit, why, "Steps · x of y done" with its tasks (done ones ticked) and "Plan more steps".

## Browse (Task 16.4, part 1)
- [ ] LaptopBrowse (Files): left column All docs, each folder, No folder, + New folder and Approvals · N; middle the doc list (+ New doc, title, folder · edited date; hovering a row shows Delete and Undo appears); right the open doc (title, Edit, the three-dots menu with Move, Rename, Delete, and the text); Edit opens the editor in the right column; with unsaved changes, clicking another doc, a folder, + New doc or Approvals asks "Discard your changes?"; the search box top right filters as you type and Ctrl+F jumps into it, also while a doc is open; Approvals opens in the right column.
- [ ] LaptopStudy: left "Moodle · checked HH:mm", Courses with "Exam in N days" or "No exam", Upload file; middle the selected course ("N files · last checked", the "Check Moodle for new files" switch, Practice / Quiz / Summary tiles with counts that open cards, a quiz or the summary in this column, Course files with Open and Make study set, hover Delete on uploaded files); right All exams with "+ Exam", countdown tiles showing the course and MM-dd-yyyy date and time, and Past; clicking an exam edits it in the exam form.

## Mail, Hardware and Settings (Task 16.4, part 2)
- [ ] LaptopMail: "Organise inbox" on the left top and the shell's New (with N) top right; left column "Wally suggests · N" with Approve and Reject, "Unread · N" (sender, time, subject) and "Drafts · N"; clicking an unread mail shows its subject, "Draft reply", "From <sender> · MM-dd-yyyy HH:mm" and the full text loaded from the server in the right pane ("Loading the email…" first); with the server unreachable it says you appear to be offline and "Try again" works; clicking a draft opens its editor in the right pane (Save and Send are buttons, Enter never sends, Ctrl+Enter never sends).
- [ ] LaptopHardware: left "Run check", the "What is going wrong?" box with Explain and "Past checks"; right the selected check with its explanation in full, its details and Delete (Delete removes that check and the right pane shows the next one); typing a problem and pressing Explain asks for one explanation of the selected check.
- [ ] LaptopSettings: three cards. Sync (status, Last sync, Sync now, Server URL, Device token, Test connection), Connections (Moodle, Spotify, Screentime) and Laptop only (Start with Windows, Keep running in tray when closed, XPS Ollama URL); each setting appears once, there is no Reminders card, and the phone's Settings page looks as before.

## Whole redesign (Task 16.5)
- [ ] Shortcuts when not typing (SC-13): with no text field in use, N opens the picker (or the screen's New), Esc closes it and Ctrl+F jumps to the Files search.
- [ ] Enter in multi-line fields (SC-14): in Journal text, a Note and the mail draft Body, Enter adds a line and never saves; in a single-line field of a side form Enter saves.
- [ ] Narrow window (SC-15): make the window narrower than 900 dp: the side panel moves under the list; half-type a form or a draft, widen the window again: what you typed is still there.
- [ ] Enter still sends in Chat.
- [ ] Esc closes a dialog (for example "Discard your changes?" or a form opened from Today).
- [ ] A dirty doc asks before leaving: edit a doc in Files (or a summary in Study) without saving, then click another rail icon or chip: "Discard your changes?" appears; "Keep editing" stays and Discard leaves. The same for an edited mail draft.
- [ ] The phone app still opens and its Log and Browse screens look unchanged.