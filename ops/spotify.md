# Spotify (listening history) - Phase 10

This file has two parts: the setup guide for Tim (what to do, one step at a time) and, below it, the Facts section (what Spotify's own docs say, checked on 2026-11-10).

## Setup guide (do it once, then again about every 6 months)

What it gives you: every 30 minutes your home server asks Spotify which songs you played and saves them. Wally shows them in the Listening list on the Screentime tab. It only reads your play history; it cannot play, skip or change anything.

You need Spotify Premium on the account that owns the Spotify app (you have it). If Premium ever lapses, this stops working until you subscribe again.

Last spotify-auth: ______ (date). Next due: ______ (that date + 5 months). The sign-in lasts 6 months and refreshing does not extend it.

### 1. Create the Spotify app (browser, on your laptop)
1. Open https://developer.spotify.com/dashboard and log in with your Spotify account.
2. Press **Create app**. Name: `Wally`. Description: `Personal listening log`.
3. In **Redirect URIs** type exactly this and press Add (it must match to the letter, no trailing slash):
   ```
   http://127.0.0.1:8765/callback
   ```
4. Under "Which API/SDKs are you planning to use?" tick **Web API**. Accept the terms and Save.
5. Open the app > **Settings**. Copy the **Client ID**. Press **View client secret** and copy the secret. The secret is a password: put it in your password manager, never in chat or in the project.
6. Still in Settings, open **User Management** and add yourself: your name and the email of your Spotify account. Without this, Spotify refuses your own app (answer 403) and Wally shows "Spotify not connected".

### 2. Put the keys in the server config (on the HP)
1. Open the config file:
   ```
   ssh -t hp sudo nano /etc/assistant/config.json
   ```
2. Add this block next to the existing `google`, `ollama` and `moodle` blocks (do not forget the comma on the line before it). Paste your own Client ID and secret between the quotes:
   ```
   "spotify": {
     "clientId": "<your client id>",
     "clientSecret": "<your client secret>"
   }
   ```
   The whole file then has this shape (keep your real values):
   ```
   {
     "bindAddress": "100.x.y.z",
     "port": 8080,
     "dbPath": "/var/lib/assistant/assistant.db",
     "tokens": { "phone": "...", "laptop": "..." },
     "google": { "clientId": "...", "clientSecret": "..." },
     "ollama": { "url": "http://127.0.0.1:11434", "briefingModel": "qwen3:1.7b" },
     "moodle": { "baseUrl": "https://moodle.example.edu" },
     "spotify": { "clientId": "...", "clientSecret": "..." }
   }
   ```
3. Check the file is valid. It prints nothing when correct; otherwise it prints a line and column, so go back and fix that spot:
   ```
   ssh -t hp "sudo python3 -m json.tool /etc/assistant/config.json > /dev/null"
   ```
4. Restart the server:
   ```
   ssh -t hp sudo systemctl restart assistant
   ```
Deploy the new server first if you have not (`powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1`). Until you sign in (next step) nothing is fetched and Settings says "Spotify not connected".

### 3. Sign in once (the server cannot open a browser, so we tunnel)
1. Open a tunnel from your laptop to the server and keep this window open:
   ```
   ssh -L 8765:127.0.0.1:8765 -t hp
   ```
2. In that same window start the sign-in as the server user:
   ```
   sudo -u assistant java -Xmx256m -jar /opt/assistant/server.jar spotify-auth
   ```
3. It prints a long address. Copy it into the browser on your laptop, log in to Spotify if asked and press **Agree**. The browser shows "Wally is connected to Spotify" and the server window prints `saved`. If you wait more than 5 minutes the command stops by itself; run it again.
4. Type `exit` to close the tunnel. No restart is needed: within 30 minutes the server picks up the new sign-in (or restart the server to fetch right away).
5. Write today's date in "Last spotify-auth" at the top of this file.

### 4. Check it works
- Play a song in Spotify for a minute, then wait up to 30 minutes.
- On the HP: `ssh -t hp sudo journalctl -u assistant -n 30 --no-pager` shows a line starting `spotify poll ok`.
- In Wally: Settings shows Spotify connected, and the Screentime tab's Listening section lists the song. A song never appears twice.

### Reconnecting (when Wally says "Spotify not connected")
It happens when the 6 months are over, you removed Wally in your Spotify account's apps page, you are not in the app's User Management list, or Premium lapsed. Fix the cause, then repeat step 3 and update the date at the top. If the log says `auth failure: ... 403`, check User Management and Premium first.

### What is and is not saved
- The database (and so the nightly backup) holds the songs list only. The Spotify sign-in file is `/var/lib/assistant/spotify-token.json`, readable only by the server user. It is never in the config, the repo, the logs or the backup. After a restore from backup run step 3 again.
- The server fetches the most recent plays Spotify allows (about the last 50) every 30 minutes. If the server is off for a long time, plays older than that are lost; Spotify does not give them out.
- If Spotify is busy (answer 429) or down, that round is skipped and tried again in 30 minutes. Nothing is half saved.

## Facts

All facts below were read on 2026-11-10 from developer.spotify.com (page text fetched with
Invoke-WebRequest). Base URL: `https://developer.spotify.com/documentation/web-api/`.
Each line ends with its source path under that base. "Not documented" means the page does not say.

### Endpoint and scope
- Endpoint: `GET https://api.spotify.com/v1/me/player/recently-played`. Source: `reference/get-recently-played`.
- Auth: OAuth 2.0, scope `user-read-recently-played` ("Access your recently played items"). Source: `reference/get-recently-played`.
- Still available to Development Mode apps after the February 2026 changes: the Dev Mode "Endpoints still available" list names "Get Recently Played Tracks (GET /me/player/recently-played)". Source: `references/changes/february-2026`.
- Podcast episodes are not supported (tracks only). Source: `reference/get-recently-played`.
- Policy note on the page: streaming apps may not be commercial; do not alter/synchronise Spotify content. A personal read-only history log is not affected. Source: `reference/get-recently-played`.

### Query parameters and paging
- `limit`: integer, default 20, minimum 1, maximum 50. Source: `reference/get-recently-played`.
- `after`: Unix timestamp in milliseconds; returns all items after (but not including) this cursor position. Source: `reference/get-recently-played`.
- `before`: Unix timestamp in milliseconds; returns items before (but not including) the cursor. `after` and `before` must not both be given. Source: `reference/get-recently-played`.
- Response is a paged object: `href`, `limit`, `next` (URL of next page, null if none), `cursors` {`after`, `before`} ("the cursor to use as key to find the next page" / "previous page"), `total`, `items`. Source: `reference/get-recently-played`.
- Not documented on the page: the sort order of `items` (commonly observed newest first), how many plays back the history reaches (commonly observed: last 50 plays only), and which of `next` or `cursors.after` to follow when `after` was sent. Implementation consequence: parse items without assuming order, dedupe by play id, follow `next` when it is non-null (up to 10 pages as in the Plan), take the max `played_at` as the cursor, never rely on `total`.

### Response fields we use
Each `items[]` entry is a PlayHistoryObject:
- `played_at`: string, date-time (ISO 8601).
- `track.id`: Spotify ID string (can be null for local files in practice; the page also has `track.is_local`: boolean, "from a local file").
- `track.name`: string.
- `track.artists[].name`: string (array of simplified artist objects).
- `track.duration_ms`: integer.
- Also present, unused: `context`, `track.album`, `track.uri`, `track.external_urls`.
- Fields removed from Dev Mode responses since February 2026 (`available_markets`, `popularity`, `linked_from`, track `external_ids` was reverted in March 2026): none of them is in our list. Source: `references/changes/february-2026`, `references/changes/march-2026`.

### Token flow (authorization code)
- Authorize: `GET https://accounts.spotify.com/authorize` with `client_id`, `response_type=code`, `redirect_uri`, `state` (optional but strongly recommended), `scope`, optional `show_dialog`. Source: `tutorials/code-flow`.
- Callback query: `code` and `state`; on denial `error` and `state`. The app must compare `state` and stop on mismatch. Source: `tutorials/code-flow`.
- Exchange: `POST https://accounts.spotify.com/api/token`, form body `grant_type=authorization_code`, `code`, `redirect_uri` (must equal the one used in authorize); header `Authorization: Basic base64(client_id:client_secret)`, `Content-Type: application/x-www-form-urlencoded`. Response 200: `access_token`, `token_type` (Bearer), `scope`, `expires_in` (seconds), `refresh_token`. Source: `tutorials/code-flow`.
- Access token lifetime: 1 hour (expires_in 3600 in the examples). Source: `tutorials/refreshing-tokens`.
- Refresh: same token URL, form `grant_type=refresh_token`, `refresh_token`; Basic header with client id and secret (authorization code flow; `client_id` in the body only for PKCE). Source: `tutorials/refreshing-tokens`.
- Rotation: "Depending on the grant used ... a refresh token might not be included in each response. When a refresh token is not returned, continue using the existing token." When one is returned, use it from then on. Matches the Plan (persist the new one atomically, keep the old one when omitted). Source: `tutorials/refreshing-tokens`.
- Refresh token lifetime: 6 months from the moment the user authorizes; refreshing does NOT extend it. After that the user must go through the authorization flow again. NEW fact, not in the Plan: spotify-auth must be re-run about every 6 months, and the guide should say so (spotifyOk goes false when it lapses). Source: `tutorials/refreshing-tokens`.
- `invalid_grant` from the token endpoint = refresh token expired, revoked or invalid; discard it and re-authorize, do not retry. Matches the Plan (status false). Source: `tutorials/refreshing-tokens`.

### Redirect URI rules
- Must match the registered value exactly, including case and trailing slash. Source: `tutorials/code-flow`, `concepts/redirect_uri`.
- HTTP is allowed only for loopback addresses; use the explicit IP literal `http://127.0.0.1:PORT` or `http://[::1]:PORT`. `localhost` is NOT allowed. Source: `concepts/redirect_uri`.
- A loopback URI may be registered without a port and the port added in the request; with a fixed port, register it as is. Plan value `http://127.0.0.1:8765/callback` is valid; register exactly that string in the dashboard. Source: `concepts/redirect_uri`.

### Development Mode, Premium and allowlist
- New apps start in Development Mode, meant for apps used on a single Spotify account. Source: `concepts/quota-modes`.
- The app OWNER must have Spotify Premium for the app to work; if Premium lapses the app stops until the owner resubscribes (all Dev Mode apps, since 2026-02-11 for new apps and 2026-03-09 for existing ones). Tim has Premium (Plan). Source: `concepts/quota-modes`, `tutorials/february-2026-migration-guide`.
- Up to 5 authenticated users per Dev Mode app; each must be added to the app's allowlist (Developer Dashboard -> app -> Settings -> User Management -> Add new user, name and Spotify account email). A non-allowlisted user can log in but API calls with their token return 403. So Tim must be added to his own app's allowlist. Source: `concepts/quota-modes`.
- Client IDs per developer: 1 for new apps, raised to 25 in July 2026. Source: `tutorials/february-2026-migration-guide`, `references/changes/july-2026`.
- Dev Mode has request quotas per developer account (since July 2026, shared across the account's apps), separate from rate limits; exceeding it returns 429 with body `{"error":{"status":429,"message":"Too many requests","reason":"QUOTA_EXCEEDED"}}`. Exact numbers are "subject to change" and not published. One request per 30 min (plus paging) is far below any plausible limit. Source: `concepts/quota-modes`, `references/changes/july-2026`.
- Rate limit: rolling 30-second window; a 429 normally carries a `Retry-After` header (seconds). The Plan does not retry within a run (waits for the next 30-min run), which is compatible. Source: `concepts/rate-limits`.
- Extended Quota Mode is only for organizations (250k MAU etc.); not relevant and not needed. Source: `concepts/quota-modes`.

### Check against the Plan's Spotify contract
- No contradiction found: endpoint, scope, `limit=50`, `after` in ms, `next`/cursor paging, redirect `http://127.0.0.1:8765/callback`, refresh rotation and `invalid_grant` handling are all consistent with the docs.
- Additions for the guide / code: (1) refresh token expires 6 months after authorization (re-run spotify-auth; not extended by refreshing); (2) token response `refresh_token` is optional on refresh (already handled); (3) a 403 on the API call means the user is not on the allowlist or the owner's Premium lapsed (treat like a failed poll, not a crash; status false is reasonable but the Plan only lists 401 -> false, so 403 should be decided by the manager); (4) sort order and history depth of `items` are undocumented, so the poller must not depend on them.
- Live behaviour (real order, depth, whether `after` + `next` loop behaves) is not provable from the docs; it can only be confirmed on the HP after spotify-auth.
