# Connect the server to Wally Calendar

What this does for you: every 15 minutes the home server (the HP) asks Wally Calendar for your events and keeps a copy (Chat, the morning briefing and Dream week coaching use it; Wally's Today does not list events, Wally Calendar is the place to look at them). The server can also hand a new event to Wally Calendar (this is for the AI helper in a later phase). If Wally Calendar is off, Wally keeps the events it already has, and the Settings line "Wally Calendar: not reachable - Wally keeps the saved events" tells you.

You do this once. Each command goes in its own box: copy it exactly. Never paste a key into a chat or into a file in this project.

Two different keys are involved. Do not mix them up:

- `tokens.calendar` in `/etc/assistant/config.json` is the key Wally Calendar uses to call Wally (its "Make Wally task" button). It already exists. Keep it.
- `wallycal.token` (new) is the key Wally uses to call Wally Calendar. It is the `wally` key from Wally Calendar's own config.

## 1. Find the two values

1. The Wally Calendar key. Open its config on the server and look for the line with `"wally"` inside `"tokens"`. Copy the long value after it (the text between the quotes):

```
ssh -t hp sudo nano /etc/wallycal/config.json
```

Leave nano with Ctrl+X (do not change anything there).

2. The server's Tailscale address (four numbers separated by dots, for example 100.64.1.2):

```
ssh -t hp tailscale ip -4
```

The address of Wally Calendar is then `http://<those four numbers>:8081`.

## 2. Add the `wallycal` block to Wally's config

```
ssh -t hp sudo nano /etc/assistant/config.json
```

Add this block at the end, before the final `}`. The line before it must end with a comma `,`. Put your two values in place of the capital words:

```
  "wallycal": {
    "url": "http://PASTE-THE-TAILSCALE-ADDRESS:8081",
    "token": "PASTE-THE-WALLY-KEY-HERE"
  }
```

Check these things:

- `tokens` still has a `calendar` line. Do not remove it (the Make Wally task button needs it).
- The very last line of the file is a single `}`.
- A `google` block is fine. If it still has a `calendarIds` line you can delete it; the server ignores it. If you delete it, the line before it must not end with a comma.

See `ops/config.example.json` for the whole shape. Save with Ctrl+O, Enter, then leave with Ctrl+X.

## 3. Check the file and restart

This prints nothing when the file is correct. Otherwise it prints only a line and column number (never your secrets), so go back and fix that spot:

```
ssh -t hp "sudo python3 -m json.tool /etc/assistant/config.json > /dev/null"
```

Restart the server:

```
ssh -t hp sudo systemctl restart assistant
```

## 4. Check that it works

Wait one minute, then look at the server journal:

```
ssh -t hp sudo journalctl -u assistant -n 40 --no-pager
```

You should see a line starting `wallycal poll ok: occurrences=`. If you see `wallycal poll failed: http_401` the key is wrong (step 1, copy it again). `wallycal poll failed: ConnectException` or a timeout means the address or port is wrong or Wally Calendar is off.

In Wally, press `Sync now` in Settings. The Settings line should read "Wally Calendar: connected", and the server journal (`ssh -t hp sudo journalctl -u assistant -n 50 --no-pager`) shows `wallycal poll ok`. Ask Chat "what's on my calendar tomorrow?" to see your events.

## Good to know

- On the first start of the new server, the old Google calendar copies are removed once, so nothing shows twice. Google events appear again only after you press Import in Wally Calendar.
- If you change the Wally Calendar key later, change `wallycal.token` too and restart the server.
- Gmail is a separate thing: see `ops/google-setup.md`.
- The `calendar` key (the one Wally Calendar uses for "Make Wally task") can only make tasks. Since Wally 1.10.0 the server refuses it for everything about email: it cannot read a mail, list labels, or save, send or approve an email draft (the answer is "forbidden"). So even if that key leaked, nobody could send mail with it.
- Since Wally 1.11.0 the same key is also refused for approvals, research answers and AI jobs (they never come from Wally Calendar).
- Creating events from Chat (Wally 1.11.0): the server never sends the same event twice. Before it asks Wally Calendar to make an event it writes a note (calattempt:<card id>). If Wally Calendar answers, the note is removed. If the answer is lost (timeout, the HP restarted, a half answer), the note stays, and approving that card again answers "calendar-unreachable - check Wally Calendar before asking again" without sending anything. So: when you see that text, open Wally Calendar and look. If the event is there, you are done. If it is not, ask Chat for the event again (that makes a new card). A refused connection (Wally Calendar is off) or an error from Wally Calendar about the event itself (400) removes the note, so nothing is blocked: the event was certainly not made, and you can ask Chat again.
