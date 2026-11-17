# Checklist W9: Study (Moodle files, cards, quiz, exams) - Wally 1.7.0

Run it on BOTH apps: the phone app and the installed laptop app (`Wally-1.7.0.msi`). Do the Moodle setup first: `ops/moodle.md`. Use the Settings `Sync now` button whenever a step says Sync. Tick a line only when it really passed; write "skipped" next to a line you did not run. The AI parts need the XPS on with Ollama running (see `ops/ollama-xps.md`).

Release (do this first):
- [ ] The server was deployed BEFORE the apps were installed (`powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1`).
- [ ] Both apps show version 1.7.0.
- [ ] Entries saved before the upgrade (check-ins, journal, spending, meals, docs, dreams, goals, tasks) are all still listed on the laptop and on the phone.
- [ ] Backup and restore test: the helper on the HP was refreshed (see `ops/backup-setup.md`, "Update the restore helper after a schema change"), a fresh backup was made and step 7 of that guide prints all twenty-two rows (`course`, `study_file`, `card`, `quiz`, `exam` at the end) and `PASS`.

Connect Moodle (SPEC section 2 #9), on the laptop in Chrome:
- [ ] Settings > Moodle: "Open Moodle login" is enabled. The hint line under it says: In Chrome: F12 > Network > launch.php > location.
- [ ] Capture the link with F12 > Network (Preserve log) > `launch.php` > Response Headers > `location`, paste it in "Paste Moodle link", press Connect. It shows the number of courses (about 11).
- [ ] Paste something wrong (for example `hello`): the field shows "bad link" and nothing changes.
- [ ] Open the Study tab. The line "Turn on the courses you want Wally to check." shows once, and every Moodle course has a "Check files" switch (all off). Courses you switched off sit under "Other courses (N)".
- [ ] Switch ONE course on. Press Sync. Within a minute the course lists its PDF and PPTX files (name and a status line). No file is listed twice.
- [ ] Press "Check now" (Settings > Moodle) twice in a row: nothing new appears the second time.
- [ ] Switch the course off: its files stay listed. Switch another course on: its files appear after the check.
- [ ] Next morning (after 06:00) a file added in Moodle since the day before appears without pressing anything.
- [ ] Only PDF and PPTX files show up (a Word or video file in the same course is not listed).

Upload (SPEC section 2 #10):
- [ ] Study tab > upload a small PDF into a new course name (for example `My notes`). It appears under that course. Upload a second file with the same course name in other letter case: it goes under the same course.
- [ ] Upload a PPTX: it shows READY like the PDF after a few seconds.
- [ ] Upload a text file renamed to `x.pdf`, or any `.docx`: it is refused with "Only PDF or PPTX".
- [ ] Upload a file bigger than 50 MB: it is refused with "Too large (max 50 MB)".
- [ ] Cancel the file picker (phone and laptop): nothing happens, no error.
- [ ] Scanned file: upload a PDF that is only pictures. Its status says "No text found (scanned?)" and "Make study set" is disabled for it.
- [ ] Delete an uploaded file (confirm): it disappears on this device and, after Sync, on the other one. A Moodle file has no delete button.

Study set and cards (SPEC section 2 #11):
- [ ] Press "Make study set" on a READY file. The status line shows it queued, then done (the XPS must be on; the app must be open on the laptop).
- [ ] A summary doc appears in Files in the folder `Study - <course name>`, and "Open summary" opens it.
- [ ] "Cards (N)" shows between 10 and 40 cards. Tap a card to flip it. Press "Knew it" on some and "Didn't" on others.
- [ ] Offline marks: turn on airplane mode on the phone, mark three more cards, turn it off, Sync. Open the same cards on the laptop after its Sync: the marks are the same.
- [ ] Press "Make study set" on the same file again: the card count may change but there is still one summary doc and one set of cards (no duplicates).
- [ ] Hebrew: a card with Hebrew text shows right to left.

Quiz (SPEC section 2 #12):
- [ ] A course without a study set shows "Practice quiz" disabled with "Make a study set first".
- [ ] A course with a study set: "Practice quiz" makes 10 multiple-choice questions (wait for the XPS), shown one at a time.
- [ ] Answer all ten. The score (0 to 10) shows at the end and is saved.
- [ ] Offline score: take a quiz with airplane mode on, finish it, turn it off, Sync. The same score shows on the other device in the past quizzes list.
- [ ] Rotate the phone or resize the laptop window below and above 600 dp in the middle of a question: the question and the chosen answer stay.
- [ ] Delete a past quiz: it disappears on both devices after Sync.

Exams (SPEC section 2 #13):
- [ ] Exams list > "+" with a title and date (course and time optional). It appears sorted by date.
- [ ] The next three exams show on Today with "today", "tomorrow" or "in N days".
- [ ] An exam dated yesterday shows "past" and is not on Today. Edit one, delete one: both sync to the other device.

Two devices and errors:
- [ ] Marks and scores made on the phone show on the laptop and the other way round, after Sync on both.
- [ ] Make the school reject the login (for example sign out of Moodle everywhere), press "Check now": Study shows the "Reconnect Moodle" banner and the daily check stops until you connect again.
- [ ] Stop the HP server, press "Check now": a short error shows ("Needs the server") and the app does not hang.
