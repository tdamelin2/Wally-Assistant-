# Phase 6 checklist: adaptive layout (PLAN Phase 5 Tasks 5.1-5.3, D60)

File name is phase5.md because it follows the PLAN task numbers (Tasks 5.x). Install the new versions first: the laptop app `Wally-1.4.0.msi` and the phone APK. Use made-up test entries and delete them at the end. Compare the placement with `docs/plan/ui-layout-guide.html`.

Laptop, wide window (600 dp or more, a normal maximised window):
- [ ] A menu runs down the left side: `Wally` at the top, a `New entry` button, then `Today`, `Log`, `Charts`, `Health`, `Chat`, and at the bottom the sync status and `Settings`.
- [ ] The page content sits in the middle and does not stretch across the whole screen.
- [ ] Click the menu icon next to `Wally`. The menu shrinks to icons only. Click it again and it opens.
- [ ] Collapse the menu, close the app completely and open it again. The menu is still collapsed. Open it again and restart: it stays open.
- [ ] Click `New entry`. A small menu lists `Check-in`, `Spending`, `Journal`, `Task`, `Meal`. Today no longer has the five buttons.
- [ ] Choose each of the five in turn: the form opens as a pop-up over the page, with `Cancel` and `Save` at the bottom right. `Cancel` closes it without saving.
- [ ] Settings opens from the bottom of the menu and shows the server URL, token and `Sync now`.
- [ ] The sync status in the menu says `Synced HH:mm`. Clicking it starts a sync. No coloured bar is shown while everything is fine.
- [ ] Log: click the body of an entry (all five Log tabs). It opens in the edit form. The old `Edit` and `Delete` text buttons are gone.
- [ ] Log: the three-dot menu on a row offers `Delete`; it asks to confirm. Clicking the three dots or a task checkbox does not also open the edit form.
- [ ] Today: tapping a task opens it for editing; its checkbox marks it done without opening the form.
- [ ] Today has no events (removed in 1.9.0; use "Make Wally task" in Wally Calendar instead). Mail does not open for editing.
- [ ] Open a form, type something, then make the window narrower than 600 dp and wider again. What you typed is still there.
- [ ] Charts and Health show all their text and charts without being cut off.

Laptop, narrow window (drag the window narrower than 600 dp):
- [ ] The menu disappears. A title bar shows the page name, a sync icon and a gear. Five tabs sit at the bottom and a round `+` button floats above them.
- [ ] The round `+` button opens the same five-entry menu. Each form opens full screen with a back arrow on the left and `Save` on the right.
- [ ] The gear opens Settings, and the bottom tabs still switch pages. Nothing is cut off, including Settings buttons, Health day lines and the Charts legend.

Phone:
- [ ] Title bar with the page name, sync icon and gear; five tabs at the bottom; round `+` button. It matches picture 3 of the layout guide.
- [ ] The `+` menu lists `Check-in`, `Spending`, `Journal`, `Task`, `Meal`, and each opens full screen (back arrow left, `Save` right) with the keyboard not hiding the field you type in.
- [ ] Tap an entry in Log to edit it. The three-dot menu has `Delete` (with confirm). (Today has no events and no `Add as task` since 1.9.0.)
- [ ] Settings opens from the gear and is reachable at any time.
- [ ] Sync icon: a cloud with a tick when synced. Tapping it syncs.
- [ ] Turn on airplane mode and save an entry: the red `Offline - N waiting` bar appears under the title bar. Turn it off and sync: the bar disappears.
- [ ] Nothing is cut off at phone width on Today, Log, Charts, Health and Settings.
- [ ] Rotate the phone to landscape (if it allows): the layout switches without losing a half-typed form.

Hebrew right-to-left (laptop and phone):
- [ ] Save a journal entry in Hebrew. It shows right-to-left in the list, and typing Hebrew in a form field also runs right-to-left, both in the pop-up and full screen.

Cleanup:
- [ ] Delete the test entries and sync both devices.
