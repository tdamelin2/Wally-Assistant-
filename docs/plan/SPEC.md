# Personal AI Assistant (Wally) — Spec

The app is called **Wally** (D54). Internal names (`assistant`, `dev.tim.assistant`) are unchanged.

## 1. Goal
A private personal assistant for one person (Tim). It lets him log his day (check-in, spending, journal, tasks, meals) on his Samsung S25 phone and Dell XPS 16 laptop, keeps both in sync through his existing HP Debian server over Tailscale, shows charts of his data, shows his Google calendar and unread email read-only, reads his Garmin health data from Health Connect, and adds a local AI that writes a daily morning briefing and answers questions about his own data. All personal data stays on his own hardware, except the encrypted backup copy in Google Drive and the data Google already holds.

## 2. Success criteria
1. An entry of each type (check-in, spending, journal, task) saved on the phone appears on the laptop after the laptop's next sync, and the reverse.
2. An entry saved while the server is unreachable is kept on the device, marked "not synced", and reaches the server on the first sync after the server is reachable again, with no duplicate created.
3. Sending the same entry to the server twice leaves exactly one row on the server.
4. Editing the same entry on both devices while offline ends, after both sync, with the edit that has the later `updatedAt` on both devices.
5. Deleting an entry on one device removes it from the other device's lists after that device syncs.
6. A request to the server without the correct bearer token gets HTTP 401; the server does not answer on any non-Tailscale address.
7. A nightly backup file exists on the server for each of the last 14 nights, an encrypted copy exists in Google Drive, and a restore of the newest backup into a fresh database passes the row-count check. No real data is entered before the first restore test passes.
8. The Charts screen shows check-in trends (30 days), spending by category (current month), monthly spending totals (6 months) and tasks done per week (8 weeks), computed from local data with the server off.
9. The Today screen on both apps shows the next 7 days of events from the configured Google calendars and the unread Gmail list (sender, subject, snippet), no older than 15 minutes plus the device's last sync time; nothing is ever written to Google.
10. After a phone sync, the last 7 days of Garmin-origin sleep, steps, resting heart rate and exercise sessions appear on both apps; records from other apps are ignored; a day without Garmin data shows "no data".
11. By 07:30 Asia/Jerusalem each day, the server has generated a briefing with the HP's local model, and both apps show it after sync.
12. The Chat screen answers a question about the user's data using the XPS Ollama model; if the XPS Ollama is unreachable it shows "XPS is offline — turn it on to chat" within 5 seconds.
13. Hebrew text typed in any free-text field displays right-to-left correctly on both apps.
14. A meal or snack saved on one device appears on the other after sync, and every entry saved before the meal table existed is still there after the apps and server are updated.
15. Within 30 minutes of a meal reaching the server (once any backlog of older meals is done, about 20 per hour), it shows an approximate kcal, protein, carbs and fat marked "≈"; editing its text clears the numbers and they are estimated again.
16. Both apps adapt to the window width (D60, guide `docs/plan/ui-layout-guide.html`): wider than 600 dp shows a collapsible sidebar (icons only when collapsed, state remembered); narrower shows a bottom bar with Today, Log, Charts, Health and Chat, and Settings behind a gear. One + button adds any entry type, forms open as a dialog (wide) or full screen (narrow), and nothing is cut off at phone width.

## 3. Scope
### In scope
- Phase 0: server checks and runtimes, project skeleton, shared schema, phone wireless debugging (adb over Wi-Fi), SSH key login from XPS to HP.
- Phase 1: logging (4 entry types), sync, server, auth, backups and restore test.
- Phase 2: diet log (meals and snacks, database upgrade) and charts.
- Phase 3: Google read-only calendar and Gmail.
- Phase 4: Health Connect (Garmin only) with sleep and steps charts.
- Phase 5: adaptive layout (sidebar on wide windows, bottom bar on narrow ones, one + button).
- Phase 6: AI briefing (HP), chat (XPS) and meal nutrition estimates (HP).

### Out of scope (deferred)
| Item | Owned by |
|------|----------|
| Neon / cloud offline relay | Not planned (dropped, D36) |
| Google writes (create events, send email) | Not planned (dropped, D36) |
| Voice input/output | Not planned (dropped, D36) |
| App-usage tracking (phone and laptop) | Not planned (dropped, D36) |
| Background sync workers | Not planned (D12) |
| Editable spending categories, multi-currency | Not planned (D16) |
| Tags, priorities, recurring tasks | Not planned (D17) |
| Saved chat history, AI tools/actions | Not planned (D35) |
| Hebrew UI translation | Not planned (D18) |
| Publishing to Google Play / Microsoft Store | Not planned: apps are installed directly |
| Other Wally apps (Calendar, Study, Dream log, Newspaper) | Not planned now; how they would plug in is in `EXPANSION.md` (D59) |
| The Wally reference look (`wally-reference.webp`: palette, JetBrains Mono, sticker cards, folder tabs, greeting); dark mode | Dropped (D58, replaced by D60) |
| Nutrition goals, barcode scanning, food database | Not planned |

## 4. Users and flows
One user. All flows on both apps unless marked.

**First-time setup (each device)**
1. Open the app → Settings screen asks for Server URL (e.g. `http://hp-server:8080` via Tailscale MagicDNS) and Device token.
2. Tap Test connection → app saves the values and calls `GET /v1/health` with the token → shows "Connected" or the error ("Wrong token" for 401, "Server unreachable" otherwise).

**Log an entry**
1. Today screen → tap Check-in / Spending / Journal / Task / Meal.
2. Fill the form → Save. The entry is written to the local DB with `syncState = PENDING`, the app returns to Today, and a sync starts.
3. The list shows the entry with a small "not synced" dot until the server accepts it.

**Sync** (on app open, after save, Sync button)
1. Push: send all `PENDING` rows to `POST /v1/sync/push`. On success mark them `SYNCED`.
2. Pull: `GET /v1/sync/pull?since=<cursor>` returns every row changed since the cursor; upsert locally with last-edit-wins; store the new cursor.
3. Top bar shows "Synced 2 min ago" or "Offline — 3 waiting".

**Meal**: Meal or Snack, "What did you eat?", time (default now). The list shows "nutrition pending" until the server's estimate arrives by sync, then "≈ 650 kcal · P 30 g · C 70 g · F 20 g".

**Charts**: Charts tab → four charts from local data.

**Google**: Today screen shows "Next 7 days" events and "Unread mail" from the synced cache, with "Updated HH:MM".

**Health** (phone): on each sync, read Health Connect for the last 7 days, keep Garmin records only, save as entries, push. Laptop shows them after pull. Health tab shows sleep and steps charts and a workout list.

**Briefing**: at 07:00 the server builds the briefing; the apps show it at the top of Today after sync.

**Chat**: Chat tab → type a question → the app gathers local data for the last 30 days, sends it with the question to XPS Ollama, and streams the answer. History is cleared when the screen closes.

## 5. Data
One SQLDelight schema in the `shared` module is used unchanged by the server and both apps (simpler than two schemas). Every synced table has: `id TEXT PRIMARY KEY`, `updatedAt INTEGER` (epoch ms UTC, set by the writer on each edit), `deleted INTEGER` (0/1), `serverSeq INTEGER` (assigned and used by the server only; devices leave it 0 and track a pull `cursor` instead), `syncState TEXT` (`PENDING` | `SYNCED` | `REJECTED`; the server always stores `SYNCED`; a `REJECTED` row is shown with "Rejected by server — edit to retry").

IDs: random entries use a random UUID string. One-per-key entries use a deterministic id so two devices writing the same thing produce the same row: `checkin:<localDate>`, `health:<localDate>`, `workout:<hcRecordId>`, `cal:<calendarId>:<eventId>`, `mail:<gmailId>`, `briefing:<localDate>`.

| Table | Fields | Written by | Lifetime |
|-------|--------|-----------|----------|
| `checkin` | `localDate TEXT`, `mood`, `energy`, `sleepQuality`, `stress` (INTEGER 1–5), `note TEXT?` | apps | Until deleted |
| `spending` | `amountAgorot INTEGER` (>0), `category TEXT` (Food, Transport, Shopping, Bills, Fun, Health, Other), `note TEXT?`, `localDate TEXT` | apps | Until deleted |
| `journal` | `text TEXT` (1–10 000 chars), `createdAt INTEGER` | apps | Until deleted |
| `task` | `title TEXT` (1–200 chars), `dueDate TEXT?`, `done INTEGER`, `doneAt INTEGER?` | apps | Until deleted; hidden from list 7 days after `doneAt` |
| `meal` | `type TEXT` (`MEAL` or `SNACK`), `text TEXT` (1–1000 chars), `eatenAt INTEGER`, `localDate TEXT`, `kcal INTEGER?`, `proteinG INTEGER?`, `carbsG INTEGER?`, `fatG INTEGER?`, `estimateModel TEXT?` | apps (server fills the five estimate fields, D57) | Until deleted |
| `health_day` | `localDate TEXT`, `sleepMinutes INTEGER?`, `steps INTEGER?`, `restingHr INTEGER?` | phone | Until deleted |
| `workout` | `hcRecordId TEXT`, `type TEXT`, `startAt INTEGER`, `endAt INTEGER`, `distanceMeters INTEGER?` | phone | Until deleted |
| `cal_event` | `calendarId`, `eventId`, `title`, `startAt`, `endAt`, `allDay INTEGER`, `location TEXT?` | server | Soft-deleted when cancelled, when a fetch of its window (now − 1 day to now + 7 days) no longer returns it, or when it ended > 30 days ago |
| `mail_item` | `gmailId`, `sender`, `subject`, `snippet`, `receivedAt` | server | Soft-deleted when no longer unread or older than 30 days |
| `briefing` | `localDate TEXT`, `text TEXT`, `model TEXT` | server | Kept |
| `kv` | `key TEXT PRIMARY KEY`, `value TEXT` (not synced) | each side locally | Server: `seqCounter`. Device: server URL, token, XPS Ollama URL, `cursor`, `lastSyncAt`, `googleOk`, `googleLastFetchAt` |

Schema upgrades use SQLDelight migration files and `PRAGMA user_version` (D56): version 1 is the schema of dev Phases 1–2, version 2 adds `meal`.

Soft-deleted rows (tombstones) are kept forever so that a device that was offline for a long time still learns about deletions; they are small.

Money is integer agorot. Instants are UTC epoch ms. "Day" fields are `localDate` strings (YYYY-MM-DD) in Asia/Jerusalem.

Server-side secrets live outside the DB: `/etc/assistant/config.json` (device tokens) and `/var/lib/assistant/google-token.json` (Google refresh token, written by `google-auth` running as `assistant`), both mode 600, owner `assistant`.

## 6. Interfaces
### Server HTTP API (Ktor, bound to the server's Tailscale IP, port 8080)
All requests need `Authorization: Bearer <device token>`; tokens are listed in `/etc/assistant/config.json`. JSON via kotlinx.serialization.

| Endpoint | Input | Output |
|----------|-------|--------|
| `GET /v1/health` | — | `{"status":"ok","serverTime":<ms>,"googleOk":<bool, or null until the first poll>,"googleLastFetchAt":<ms or null>}` |
| `POST /v1/admin/briefing` (any device token) | — | Generates today's briefing now (replaces an existing one); `{"localDate","text"}` |
| `POST /v1/sync/push` | `{"rows":[SyncRow...]}`, max 500 rows | `{"accepted":[ids],"rejected":[{"id","reason"}]}` |
| `GET /v1/sync/pull?since=<serverSeq>` | cursor | `{"rows":[SyncRow...],"cursor":<maxSeq>}` (max 1000 rows; client repeats until fewer returned) |

`SyncRow` = `{"table":"spending","id":"…","updatedAt":…,"deleted":false,"data":{…}}`. Server rule: write the row (and give it `serverSeq = ++seqCounter`) if no row with that id exists or incoming `updatedAt` > stored `updatedAt`; otherwise keep the stored row. Either way the id is listed in `accepted`, so the client stops resending. Rows for server-written tables (`cal_event`, `mail_item`, `briefing`) or with invalid fields are listed in `rejected` with a reason (`read-only table`, `invalid: <field>`).

### Server commands (on Debian)
- `assistant.service` — systemd unit running `java -Xmx512m -jar /opt/assistant/server.jar`.
- `java -jar /opt/assistant/server.jar google-auth` — starts a one-shot listener on `127.0.0.1:8765`, prints a Google sign-in URL; the user opens it on the XPS through an SSH tunnel (`ssh -L 8765:127.0.0.1:8765 <server>`), and the refresh token is written to `/var/lib/assistant/google-token.json`. (Google's copy-paste code flow is retired and the device flow does not allow Gmail/Calendar scopes — RESEARCH.md §4.)
- `java -jar /opt/assistant/server.jar google-calendars` — prints the account's calendar IDs and names, to copy into config.
- `/opt/assistant/assistant-backup.sh` — run by `assistant-backup.timer` at 03:00 Asia/Jerusalem.
- `/opt/assistant/assistant-restore-test.sh <backup-file>` — copies the backup to a temp file, runs `PRAGMA integrity_check`, and prints row counts per table next to the live DB's counts.

### Config file `/etc/assistant/config.json`
```json
{
  "bindAddress": "100.x.y.z",
  "port": 8080,
  "dbPath": "/var/lib/assistant/assistant.db",
  "tokens": { "phone": "<64 hex chars>", "laptop": "<64 hex chars>" },
  "google": { "clientId": "…", "clientSecret": "…", "calendarIds": ["primary"] },
  "ollama": { "url": "http://127.0.0.1:11434", "briefingModel": "qwen3:1.7b" }
}
```
Tokens are generated with `openssl rand -hex 32`.

### App screens (both apps)
Today (greeting, briefing, quick-add buttons including Meal, events, unread mail, open tasks), Log lists (Check-ins, Spending, Journal, Tasks, Meals with edit/delete), Charts, Health (data shown on both; Health Connect permission button phone-only), Chat, Settings (server URL, token, XPS Ollama URL, Sync now, last sync).

## 7. Edge cases and error states
| Case | Expected behaviour |
|------|--------------------|
| Server unreachable during sync | Entries stay `PENDING`; top bar shows "Offline — N waiting"; no error dialog |
| Wrong/missing token | Server 401; app shows "Wrong token — check Settings" |
| Same row pushed twice | Server keeps one row (upsert by id) |
| Second check-in on the same day | Form opens the existing check-in for editing instead of creating a new one |
| Two devices create a check-in for the same day while offline | Both use id `checkin:<localDate>`, so it is one row; last edit wins |
| Spending amount 0, negative, or non-numeric | Save button disabled; field shows "Enter an amount above 0" |
| Empty journal text / task title | Save disabled |
| Entry saved just after midnight | `localDate` uses Asia/Jerusalem date at save time |
| DST change | Instants are UTC; `localDate` from kotlinx-datetime with `Asia/Jerusalem` |
| Device clock wrong | Accepted as-is (last-edit-wins uses device time); server logs a warning if `updatedAt` is more than 5 minutes in the future |
| Google token revoked/expired | Server sets `googleOk=false`; apps show "Google disconnected — run google-auth on the server" in Today; cached items stay until the normal expiry rules in §5 remove them |
| Google refresh token turns out to expire every 7 days (unverified app with restricted Gmail scope, RESEARCH.md §4 "could not verify") | Accepted: the same banner appears and the user re-runs `google-auth` weekly (D38) |
| Garmin does not write resting heart rate or exercise sessions to Health Connect (unconfirmed, RESEARCH.md §5) | That metric shows "no data" on every day; nothing else changes (D28, D37) |
| HP server off at 07:00 | Server generates the briefing on start-up if none exists for today and it is after 07:00 |
| Google API error / no internet on server | Retry at next 15-minute run; keep cache |
| Health Connect permission denied | Health tab shows "Allow Health Connect" button; sync continues for other data |
| No Garmin data for a day | Shows "no data" for that day |
| Health Connect not installed/unavailable | Health tab shows "Health Connect not available" |
| XPS Ollama unreachable | Chat shows "XPS is offline — turn it on to chat" within 5 s |
| HP briefing model fails or exceeds 20 minutes | Briefing for that day stored as "Briefing unavailable today"; retried only by `POST /v1/admin/briefing` |
| Backup upload to Drive fails | Local backup kept; failure written to journal (`journalctl -u assistant-backup`); the next night's run uploads every local backup missing on Drive |
| Server disk full | Push returns HTTP 507; app keeps entries `PENDING` |
| Prompt injection in email or journal text | No effect: AI has no tools or write paths (D35) |
| Model gives a bad nutrition reply (not JSON, negative, kcal > 5000) | Meal stays "nutrition pending"; retried on the next runs, skipped after 3 failures until the server restarts (D57) |
| Meal edited while its estimate is running | Estimate is discarded (the meal's `updatedAt` changed); the next run estimates the new text |
| An app older than the meal table pulls a `meal` row | Row skipped with a log line, no crash; both apps are reinstalled in the same session as the server deploy |
| Database file from before the meal table | Upgraded in place by migration on first open; all existing rows kept (D56) |

## 8. Technical decisions
All versions checked 2026-11-05; full sources and confidence in `RESEARCH.md`.

| Area | Choice | Version | Source (link) |
|------|--------|---------|---------------|
| Language | Kotlin | 2.4.21 | https://kotlinlang.org/docs/releases.html |
| UI | Compose Multiplatform (Android + Desktop JVM, both "Stable") | 1.12.2 | https://github.com/JetBrains/compose-multiplatform/releases |
| Build | Gradle | 9.7.0 (newest in Kotlin's tested range; 9.8.2 exists but is untested with KMP) | https://kotlinlang.org/docs/whatsnew2420.html |
| Android build | Android Gradle Plugin | 9.3.1 (newest in KMP's tested range; 9.5.0 exists but is untested) | https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html |
| Android levels | minSdk 26, compileSdk 36, targetSdk 36 | — | Assistant choice: 36 needs only AGP 8.9.1+ (https://developer.android.com/build/releases/about-agp); S25 runs Android 15+; Health Connect needs ≥ 24 |
| Server | Ktor server (Netty engine) + Ktor client | 3.6.1 | https://github.com/ktorio/ktor/releases |
| Server Java | JVM bytecode target 17; run on `openjdk-17-jre-headless` (Debian 12) or `openjdk-21-jre-headless` (Debian 13) | 17 / 21 | https://packages.debian.org/search?keywords=openjdk&searchon=names&suite=all&section=main |
| Build JDKs on XPS | Temurin JDK 21 (default; ≥ 17 needed for MSI packaging) and Temurin JDK 17 (Gradle toolchain for `shared`/`server` bytecode 17, D47) | 21 / 17 | https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html |
| Database | SQLDelight (`android-driver`, `sqlite-driver`) | 2.4.1 | https://repo1.maven.org/maven2/app/cash/sqldelight/android-driver/maven-metadata.xml |
| JSON | kotlinx.serialization | 1.12.0 | https://github.com/Kotlin/kotlinx.serialization/releases |
| Dates | kotlinx-datetime with `kotlin.time.Instant` / `kotlin.time.Clock` (the kotlinx versions were removed in 0.8.0) | 0.8.0 | https://github.com/Kotlin/kotlinx-datetime/releases |
| Google APIs | Plain REST via Ktor client (Calendar v3, Gmail v1, OAuth token endpoint); no Google Java libraries | — | https://developers.google.com/identity/protocols/oauth2/native-app |
| Google OAuth | "Desktop app" client, loopback redirect, project published "In production" (unverified, warning screen accepted) | — | https://support.google.com/cloud/answer/15549945 |
| Health | `androidx.health.connect:connect-client` | 1.1.0 | https://developer.android.com/jetpack/androidx/releases/health-connect |
| Garmin origin filter | `com.garmin.android.apps.connectmobile` (confirmed on device in Task 4.1) | — | https://appgoblin.info/apps/com.garmin.android.apps.connectmobile |
| Local AI | Ollama | 0.40.14 | https://github.com/ollama/ollama/releases |
| HP briefing model | `qwen3:1.7b` (1.4 GB), `think:false`, `num_ctx` 4096 | — | https://ollama.com/library/qwen3/tags |
| XPS chat model | `qwen3.5:9b` (6.6 GB), `num_ctx` 16384 | — | https://ollama.com/library/qwen3.5/tags |
| Backup snapshot | `sqlite3` CLI `VACUUM INTO` (Debian package) | Debian's packaged version | https://www.sqlite.org/lang_vacuum.html |
| Backup upload | rclone upstream `.deb` with own Google client ID, `crypt` remote over Drive | 1.75.2 | https://rclone.org/downloads/ |
| Desktop installer | Compose `packageMsi` (jpackage + auto-downloaded WiX 3.11), `modules("java.sql")` | — | https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html |

## 9. Constraints
- HP server: 4 GB RAM. Ktor server JVM heap capped at 512 MB (`-Xmx512m`); briefing model must fit in the remaining memory.
- XPS 16: 32 GB RAM, Intel Core Ultra X7 358H, Intel Arc B390 iGPU.
- Network: Tailscale only; server and Ollama on XPS never bound to public interfaces.
- Privacy: no personal data leaves own devices except encrypted backups (Google Drive) and data already in Google.
- Budget: free tools only.
- One developer (a student); each phase must be usable on its own.

## 10. Compatibility
New project; no earlier phases or locked decisions exist. `Architecture_planner_PLAN.md` is superseded by this spec (D1).
