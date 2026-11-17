# Google setup for Wally (Gmail)

What this does for you: the home server (the HP) reads your unread Gmail every 15 minutes, and Wally shows it on the Today screen. Since Wally 1.10.0 it can also do three more things, but only after you approve them in the Mail tab: send an email you wrote (or the AI drafted), put a label on a mail, and archive a mail (take it out of the inbox). It never sends anything on its own, and it never deletes mail.

If you set Google up before 1.10.0, do "Adding send and label access (Wally 1.10.0)" below once. Without it, an approved email shows "Failed: Google permission missing - run google-auth again".

Since Wally 1.9.0 your calendar events do NOT come from Google any more. They come from Wally Calendar (see ops/wallycal.md). A Google event reaches Wally only after you press Import in Wally Calendar.

You do this once. It takes about 20 minutes. Do the steps in order. Each command goes in its own box: copy it exactly.

Important: Wally gets its OWN Google project (we call it `wally-google`). Your backup tool (rclone) lives in a different project called `assistant`. Never revoke or delete anything in project `assistant`, or the nightly backup stops working.

Never paste the client secret into a chat, a file in this project, or an email. It only goes into the server config in step 5.

## 1. Create the project

1. Open https://console.cloud.google.com/projectcreate (signed in as your normal Google account).
2. Project name: `wally-google`. Click Create. Wait until it is created and make sure it is the selected project (name shown at the top).

## 2. Set up the consent screen (Google Auth Platform)

1. Open https://console.cloud.google.com/auth/overview and click Get started (or open Branding if it is already started).
2. Branding: App name `Wally`; User support email: your email; App home page `https://your-domain.example`; Privacy policy `https://your-domain.example/privacy.html`; Authorized domain `your-domain.example` (it is already verified in Search Console under the same Google account); Developer contact: your email. Save.
3. Audience: choose External. Then click Publish app so the status says In production. (If it stays in Testing, Google logs you out every 7 days.)

Write down in the "Fields to fill" section below: project name, and "Audience = In production".

## 3. Turn on the Gmail API

Open https://console.cloud.google.com/apis/library/gmail.googleapis.com and click Enable. (The Calendar API is not needed. If it is already on from an earlier setup, leave it or switch it off, it does not matter.)

## 4. Allow Gmail access and create the sign-in key

1. Open https://console.cloud.google.com/auth/scopes (the "Data access" page) and click Add or remove scopes.
2. Add exactly these three (search for each one): `https://www.googleapis.com/auth/gmail.readonly`, `https://www.googleapis.com/auth/gmail.send` and `https://www.googleapis.com/auth/gmail.modify`. Click Update, then Save. (An older `calendar.readonly` entry can stay on the consent screen; the server no longer asks for it.)
3. Write down which category Google shows for each scope (for example "Sensitive" or "Restricted").
4. Open https://console.cloud.google.com/auth/clients and click Create client.
5. Application type: Desktop app. Name: `wally-server` (never reuse the rclone client). Click Create.
6. Copy the Client ID and the Client secret somewhere private for the next step. Do not paste them into chat.

## 5. Put the key into the server config

Open the config file on the server:

```
ssh -t hp sudo nano /etc/assistant/config.json
```

Look near the end of the file for a line that says `"google": {`.

**If it is there:** replace the values of `clientId` and `clientSecret` with the ones from step 4. If the block still has an old line about calendars, you can delete it (the server ignores it now); remember the line before it then needs no comma at its end.

**If it is not there:** add the whole block yourself. The end of the file must look exactly like this (your own tokens block stays as it is, only the `google` block is new):

```
  "tokens": {
    ...your existing lines...
  },
  "google": {
    "clientId": "PASTE-CLIENT-ID-HERE",
    "clientSecret": "PASTE-CLIENT-SECRET-HERE"
  }
}
```

Check these three things:

- There is no lone `}` above the `"google"` line (the old closing brace of the file must be gone, or the file ends too early).
- The line just before `"google"` is `  },` (with a comma).
- The very last line of the file is a single `}`.

Tips for nano: right-click pastes text into the window. Press Ctrl+C to see the line number the cursor is on.

When done, save with Ctrl+O, Enter, then leave with Ctrl+X. See `ops/config.example.json` for the shape.

Before restarting, check that the file is valid. This prints nothing when it is correct. Otherwise it prints only a line and column number (never your secrets), so go back and fix that spot:

```
ssh -t hp "sudo python3 -m json.tool /etc/assistant/config.json > /dev/null"
```

Then restart the server:

```
ssh -t hp sudo systemctl restart assistant
```

## 6. Sign in once (the server cannot open a browser, so we tunnel)

Open a tunnel from your laptop to the server (keep this window open):

```
ssh -L 8765:127.0.0.1:8765 -t hp
```

In that same window, on the server, start the sign-in:

```
sudo -u assistant java -Xmx256m -jar /opt/assistant/server.jar google-auth
```

It prints a long address. Copy it into the browser on your laptop, sign in with your Google account, and allow the Gmail permissions it lists (read, send, and label/archive). Google shows a warning "Google hasn't verified this app" because the app is only for you: choose Advanced, then "Go to Wally (unsafe)", and continue (it is your own app). On the next screen keep every box ticked; if you untick one, that feature will not work. The browser then shows "Wally is connected to Google" and the server window prints `saved` and a line `refresh_token_expires_in present: yes` or `no`. Write that yes/no down. If you wait more than 10 minutes the command stops by itself; run it again.

You can close the tunnel window now (type `exit`).

## 7. Check that it works

Restart the server:

```
ssh -t hp sudo systemctl restart assistant
```

Within a minute the server journal shows a line starting `google poll ok`. Check it with:

```
ssh -t hp sudo journalctl -u assistant -n 30 --no-pager
```

## Moving an existing setup to Gmail-only (Wally 1.9.0)

Do this once if you signed in to Google before 1.9.0. Google keeps the old permissions (Calendar and Gmail) until you remove them, so the new sign-in would not become Gmail-only by itself.

1. Open https://myaccount.google.com/permissions, choose `Wally` and click Delete all connections. Only `Wally`. Never touch the entry for your backup tool (rclone / project `assistant`).
2. Run step 6 again (tunnel, then `google-auth`). Google now only asks for Gmail.
3. Write the date into "last google-auth" and "AC-4 due" below.
4. Calendar events no longer come from Google. If you want a Google event in Wally, press Import in Wally Calendar. The old command that listed Google calendars is gone.

## Adding send and label access (Wally 1.10.0)

Do this once if you signed in to Google before 1.10.0. The old sign-in only has permission to read, so the new features need a new sign-in.

1. Open https://console.cloud.google.com/auth/scopes (select project `wally-google` at the top), click Add or remove scopes, add `https://www.googleapis.com/auth/gmail.send` and `https://www.googleapis.com/auth/gmail.modify` next to the one you already have, click Update, then Save.
2. Run step 6 again (tunnel, then `google-auth`). Do not delete the old connection first; signing in again replaces it. Google shows the "not verified" warning again: Advanced, then "Go to Wally (unsafe)".
3. Restart the server (step 7) and write the date into "last google-auth" below.
4. Check it in the Mail tab: write a test email to your own second address, press Send, and it shows "Sent" within a minute.

## Fields to fill (keep this up to date)

- Google project: `wally-google` (name as created: ______)
- API enabled (Gmail): ______
- Audience In production: ______
- Scope category shown by Google: gmail.readonly = ______, gmail.send = ______, gmail.modify = ______
- Re-run google-auth for 1.10.0 done on: ______ (date)
- Client id of `wally-server` (the secret never goes here): ______
- refresh_token_expires_in present (yes/no): ______
- last google-auth: ______ (date)
- AC-4 due: ______ (last google-auth date + 8 days). Before 1.9.0 this was due 2026-11-16 or 8 days after the last google-auth, whichever is later; the new date comes from the re-auth above.

## If Wally says "Google disconnected"

This means Google no longer accepts the saved sign-in (you revoked it, or Google expired it). Run step 6 again (tunnel, then `google-auth`). The banner disappears after the next 15-minute check and the next sync. Update "last google-auth" and "AC-4 due" above.

## How to switch Wally's Google access off (revoke)

1. Open https://myaccount.google.com/permissions.
2. Choose `Wally` and click Delete all connections. Only do this for `Wally`.
3. Do NOT touch the entry for your backup tool (rclone / project `assistant`).

To switch it on again, run step 6.

## If the backup login was also hit

Revoking only Wally (project `wally-google`) should not touch the backup. If the nightly backup still stops working after a revoke test (the backup uploads fail), reconnect rclone:

Step 1. On the server, start the reconnect as the backup user:

```
ssh -t hp sudo -u assistant -H rclone config reconnect gdrive:
```

Step 2. Answer `n` to "Use web browser to automatically authenticate". rclone then prints a command that starts with `rclone authorize "drive" "..."`. Copy that exact command, run it in PowerShell on your laptop (rclone must be installed there), sign in, and paste the code it prints back into the server window. Do not run a bare `rclone authorize "drive"`: it would use rclone's old shared client instead of your own `rclone` client.

Warning: that command contains the client secret. Never paste it into chat or a file.

Step 3. Check the encrypted copy lists files:

```
ssh -t hp sudo -u assistant -H rclone lsf gcrypt:
```

Finally check the next night's backup ran (a new file dated today appears):

```
ssh -t hp sudo ls /var/backups/assistant
```

## Reminders

- The `wally-server` client secret lives only in `/etc/assistant/config.json` on the server.
- The saved sign-in lives only in `/var/lib/assistant/google-token.json` on the server (readable by the `assistant` user only).
- The backup lives in project `assistant` and must not be revoked.
