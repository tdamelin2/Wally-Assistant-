# Backup setup (one time, done by Tim)

What this does: every night at 03:00 (Jerusalem time) the server copies its database, locks the copy with a
password only you hold, and uploads it to your Google Drive. It keeps the newest 14.
**Do not enter real personal data in the apps until step 7 passes.** Test entries only.

Run commands in PowerShell on the XPS from `C:\Users\user\Projects\assistant`. `ssh -t` commands may ask for your
HP password. Finish `ops\server-setup.md` first.

Paste one command at a time. Long pasted lines can get split by the terminal, so this guide keeps each line short.

## 1. Install rclone on the HP
rclone is a tool that copies files to cloud storage. Check the CPU type first:
```
ssh hp "uname -m"
```
If it prints `x86_64`, use the first command; if `aarch64`, use the second.
```
ssh -t hp "cd /tmp && curl -LO https://downloads.rclone.org/v1.75.2/rclone-v1.75.2-linux-amd64.deb && sudo apt install ./rclone-v1.75.2-linux-amd64.deb"
ssh -t hp "cd /tmp && curl -LO https://downloads.rclone.org/v1.75.2/rclone-v1.75.2-linux-arm64.deb && sudo apt install ./rclone-v1.75.2-linux-arm64.deb"
```

## 2. Make your own Google access key for rclone
You need your own key. rclone's shared key is being retired during 2026, and `rclone config` refuses to continue without
a client ID. Follow https://rclone.org/drive/#making-your-own-client-id and use these choices.

Google will not let you publish the app ("In production") without a verified domain. Without publishing, the login
expires after 7 days and the nightly backup silently stops. So first set up a domain:
1. Buy a domain. Tim uses `your-domain.example`, bought at Cloudflare Registrar (at-cost price, DNS included).
2. Put two simple pages on it, hosted free on Cloudflare Pages (a project named `wally` with the custom domain
   attached): a home page at `https://your-domain.example` and a privacy page at `https://your-domain.example/privacy.html`.
3. Verify the domain in Google Search Console: add a **Domain** property and create the DNS TXT record it shows
   in Cloudflare DNS.

Then in the Google Cloud console:
1. Create a project (name it `assistant`) and enable the Google Drive API.
2. Google Auth Platform > Branding: app name, support email, App home page `https://your-domain.example`,
   Privacy policy link `https://your-domain.example/privacy.html`, Authorized domain `your-domain.example`. No logo.
3. Data Access: leave the scope list empty (rclone asks for `drive.file` itself at login).
4. Audience: user type External, then click **Publish app** so the status is "In production".
5. Clients > Create client, application type **Desktop app** (Tim named it `rclone`).
6. Write down the client ID and client secret in your password manager. Never paste them into chat or a file in the project.

## 3. Connect rclone to your Drive
```
ssh -t hp "sudo -u assistant -H rclone config"
```
Create two remotes:
- `gdrive`: type `drive`.
  - Paste your client ID, then your client secret.
  - Scope: `drive.file`.
  - "Configure this as a Shared Drive?": `n`.
  - "Use web browser to automatically authenticate": `n`.
  - rclone then prints a command that starts with `rclone authorize "drive" "..."`. Run **that exact command** in PowerShell on the
    XPS (rclone 1.75.2 for Windows, installed in Phase 0), sign in to Google in the browser, and paste the code it prints back into the HP window.
  - **Warning:** that `rclone authorize` command contains your client secret. Do not paste it into a chat, a ticket or any file you share.
- `gcrypt`: type `crypt`.
  - Remote: `gdrive:assistant-backups`.
  - Filename encryption `standard`; directory name encryption `true`.
  - Choose "Generate random password" for both password and password2.
  - "Password strength in bits": just press Enter (the default).

For each remote, at the end rclone asks "Keep this remote?". Answer **`y`**. If you answer anything else the remote is not saved
(this happened once with `gcrypt` and it had to be created again). Quit the menu with `q`, then check both remotes exist:
```
ssh -t hp "sudo -u assistant -H rclone listremotes"
```
It must print both `gdrive:` and `gcrypt:`.

Save both crypt passwords in your password manager. **Without them the backups cannot be opened.**
Check the config is where the timer will read it:
```
ssh -t hp "sudo -u assistant -H rclone config file"
```
It must print `/var/lib/assistant/.config/rclone/rclone.conf`.

## 4. Install the backup files
Copy the files to the HP:
```
scp ops\assistant-backup.sh ops\assistant-backup.service hp:/tmp/
scp ops\assistant-backup.timer ops\assistant-restore-test.sh hp:/tmp/
```
Remove Windows line endings:
```
ssh -t hp "sed -i 's/\r$//' /tmp/assistant-backup.sh /tmp/assistant-restore-test.sh"
ssh -t hp "sed -i 's/\r$//' /tmp/assistant-backup.service /tmp/assistant-backup.timer"
```
Install the scripts:
```
ssh -t hp "sudo cp /tmp/assistant-backup.sh /tmp/assistant-restore-test.sh /opt/assistant/"
ssh -t hp "sudo chmod 755 /opt/assistant/assistant-backup.sh /opt/assistant/assistant-restore-test.sh"
```
Install and start the timer:
```
ssh -t hp "sudo cp /tmp/assistant-backup.service /tmp/assistant-backup.timer /etc/systemd/system/"
ssh -t hp "sudo systemctl daemon-reload"
ssh -t hp "sudo systemctl enable --now assistant-backup.timer"
```

The server service only needs to be installed again if it was installed before `UMask=0077` was added to `ops\assistant.service`
(that setting makes new files private). If your unit already has it, skip this. To check:
```
ssh hp "grep UMask /etc/systemd/system/assistant.service"
```
If it prints `UMask=0077`, you are done with this part. If it prints nothing, repeat the unit install and the restart from `ops\server-setup.md`.

## 5. Check the files have no Windows line endings
```
ssh hp "grep -c \$'\r' /opt/assistant/assistant-backup.sh /opt/assistant/assistant-restore-test.sh /etc/systemd/system/assistant-backup.service /etc/systemd/system/assistant-backup.timer"
```
Every count must be `0`.

## 6. Run one backup now
Start the backup and read the log:
```
ssh -t hp "sudo systemctl start assistant-backup.service"
ssh -t hp "sudo journalctl -u assistant-backup -n 20"
```
Check the local copy, the encrypted copy and the timer:
```
ssh -t hp "sudo ls -l /var/backups/assistant"
ssh -t hp "sudo -u assistant -H rclone ls gcrypt:"
ssh -t hp "sudo -u assistant -H rclone ls gdrive:assistant-backups"
ssh hp "systemctl list-timers assistant-backup.timer"
```
Expected: no errors; today's file with mode `-rw-------`; today's file listed in `gcrypt:`; only scrambled names in `gdrive:`; the timer shows the next run at 03:00 Jerusalem time.

## 7. Restore test (the gate)
Close the phone app and the desktop app first, so nothing syncs during the test. Replace DATE with today's date as `YYYY-MM-DD`.
Download the backup from Google Drive (this also proves the crypt password works):
```
ssh -t hp "sudo -u assistant -H rclone copy gcrypt:assistant-DATE.db /tmp/dl/"
```
Run the restore test on the downloaded file:
```
ssh -t hp "sudo -u assistant /opt/assistant/assistant-restore-test.sh /tmp/dl/assistant-DATE.db"
```
Expected: `integrity_check: ok`, thirty-four table rows with equal numbers (the last four are `chat`, `chat_message`, `memory_note` and `outbox_notification`), and `PASS` at the end. Any `FAIL` line means the backup is not trustworthy: stop and tell me.
Then remove the downloaded copy:
```
ssh -t hp "sudo rm -rf /tmp/dl"
```

## 8. Run a second backup more than 1 hour later
This proves the Google login refreshes by itself:
```
ssh -t hp "sudo systemctl start assistant-backup.service"
ssh -t hp "sudo journalctl -u assistant-backup -n 20"
```
Expected: no errors.

## Update the restore helper after a schema change
The restore test lists every table by name. When a phase adds a table (Phase 3 added `meal`; Phase 8 added `doc_folder`, `doc`, `dream`, `dream_pattern`, `goal` and `ai_job`; Phase 9 added `course`, `study_file`, `card`, `quiz` and `exam`; Phase 10 added `usage_session`, `app_rule` and `listen`; Phase 12 added `mail_draft`, `mail_action` and `hw_check`; Phase 13 added `approval` and `research`; Phase 17 added `chat`, `chat_message`, `memory_note` and `outbox_notification`), refresh the helper on the HP once, one command at a time:
```
scp ops\assistant-restore-test.sh hp:/tmp/
```
```
ssh -t hp "sed -i 's/\r$//' /tmp/assistant-restore-test.sh"
```
```
ssh -t hp "sudo cp /tmp/assistant-restore-test.sh /opt/assistant/"
```
```
ssh -t hp "sudo chmod 755 /opt/assistant/assistant-restore-test.sh"
```
Then run step 7 again; it must print the `meal` row, the six Phase 8 rows (`doc_folder`, `doc`, `dream`, `dream_pattern`, `goal`, `ai_job`), the five Phase 9 rows (`course`, `study_file`, `card`, `quiz`, `exam`), the three Phase 10 rows (`usage_session`, `app_rule`, `listen`), the three Phase 12 rows (`mail_draft`, `mail_action`, `hw_check`), the two Phase 13 rows (`approval`, `research`), the four Phase 17 rows (`chat`, `chat_message`, `memory_note`, `outbox_notification`) and `PASS`. If one `listen` row differs because the 30-minute Spotify fetch ran between the backup and the test, make a fresh backup and test again. The same goes for the Phase 17 chat rows: if you chatted with Wally or a reminder was queued between the backup and the test, one count can differ; make a fresh backup and test again right away.

### What the backup does not hold (Phase 9)
The backup holds the database only. The study files on the HP disk (`/var/lib/assistant/study/`) and the Moodle login (`/var/lib/assistant/moodle-token.json`) are not in it. After a restore:
- Moodle files are downloaded again by themselves on the next check (the server notices a listed file is missing on disk and fetches it again).
- Files you uploaded by hand are gone from disk: their rows stay but cannot be read; upload them again.
- The Moodle login must be pasted again in Settings (see `ops/moodle.md`).

### After restoring an old backup (Phase 12)
The backup holds your email drafts too, and the server sends a draft by itself as soon as it starts and finds one marked "approved". An old backup can hold drafts you already sent or no longer want. So after you restore an old backup, and before you start the server again, turn every approved draft back into a plain draft (one command):
```
ssh -t hp "sudo -u assistant sqlite3 /var/lib/assistant/assistant.db \"UPDATE mail_draft SET status='DRAFT' WHERE status='APPROVED'\""
```
Then start the server, open the Mail tab and check the drafts. Check "Sent" drafts against Gmail's Sent folder. A draft that is still "Sending" in the backup becomes "Unknown - check Gmail Sent" when the server starts; it is never sent a second time.

### What the backup does not hold (Phase 10)
The Spotify sign-in (`/var/lib/assistant/spotify-token.json`) is not in the backup. After a restore run `spotify-auth` again (see `ops/spotify.md`, step 3). The songs list (`listen`) is in the backup and refills by itself.

## Rotating the client secret
Do this if the client secret was ever pasted somewhere it should not be (for example inside the `rclone authorize` command in a chat).
The backups keep working during the swap.
1. Google Cloud console > Google Auth Platform > Clients > open the `rclone` client > **Add secret**. Copy the new secret into your password manager.
2. On the HP, open rclone's menu:
   ```
   ssh -t hp "sudo -u assistant -H rclone config"
   ```
   Press `e` (edit existing remote), choose `gdrive`, press Enter through the questions until `client_secret`, paste the new secret, then keep
   pressing Enter (answer `n` to re-authenticate) and `y` to "Keep this remote?". Quit with `q`.
3. Test that the encrypted remote still works:
   ```
   ssh -t hp "sudo -u assistant -H rclone ls gcrypt:"
   ```
   It must list your backups without an error.
4. Only then go back to the Google console, **Disable** the old secret and, once everything still works, **Delete** it.
