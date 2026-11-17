# Research — Wally new features (2026-11-09)

Quick pass by the `researcher` agent. "Unconfirmed" items must be settled by a test before the phase that needs them.

| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | Moodle: `login/token.php?service=moodle_mobile_app` returns `token` + `privatetoken` | Medium | https://tracker-old.moodle.org/browse/MDL-67749 |
| 2 | Moodle SSO sites log in via browser `admin/tool/mobile/launch.php`, token returned by deep link | Medium | https://docs.moodle.org/dev/Moodle_App_Deep_Linking |
| 3 | Moodle behind SSO needs `login/token.php`, `webservice/*`, `tokenpluginfile.php` allowed by admins | Medium | https://docs.moodle.org/39/en/Moodle_app_FAQ |
| 4 | Apache PDFBox 3.0.8 (2026-07-11) | High | https://pdfbox.apache.org/ |
| 5 | Apache POI 5.5.1 (2025-11-30), newer may exist | Medium | https://poi.apache.org/ |
| 6 | Spotify Dev Mode new restrictions from 2026-03-09 | High | https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide |
| 7 | Spotify Dev Mode quota counted per developer account (July 2026) | High | https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates |
| 8 | Spotify Dev Mode: 5 allowlisted users, developer needs Premium; fits a 1-user app | Medium | https://vorplabs.com/agent-tools/spotify-api-changes |
| 9 | Netflix: no API; per-profile Viewing activity CSV download | High | https://help.netflix.com/en/node/101917 |
| 10 | Android `UsageStatsManager` needs `PACKAGE_USAGE_STATS` (granted in Settings → Usage access) | High | https://developer.android.com/reference/android/app/usage/UsageStatsManager |
| 11 | Android `queryEvents` keeps ~7 days → store events daily | Low | community report |
| 12 | JNA 5.19.1 | Medium | https://javadoc.io/doc/net.java.dev.jna/jna |
| 13 | Gmail `gmail.send` = Sensitive; `gmail.compose` (drafts) = Restricted (CASA for verification) | High | https://developers.google.com/workspace/gmail/api/auth/scopes |
| 14 | Google Testing mode: tokens expire after 7 days; "Publish app" removes it (unverified warning, owner clicks through — unconfirmed) | High (7-day) | https://support.google.com/cloud/answer/15549945 |
| 15 | Windows thermal WMI needs admin and is often "Not supported" on laptops | Medium | Citrix uberAgent docs |
| 16 | Ollama latest v0.40.15 (Wally pins 0.40.14) | High | https://github.com/ollama/ollama/releases |
| 17 | qwen3.5:9b tool calling had Ollama parser bugs early 2026, fixes in March 2026; must test | Medium | https://github.com/ollama/ollama/issues/14745 |
| 18 | Re-checked 2026-11-09 on the Gmail scopes page: `gmail.send` Sensitive; `gmail.modify`, `gmail.readonly`, `gmail.compose` Restricted; `gmail.labels` Non-sensitive (whether it allows message label changes is not stated) | High | https://developers.google.com/workspace/gmail/api/auth/scopes |
| 19 | Codex: `codex exec` defaults to a read-only sandbox; flags `-s read-only`, `--output-schema <path>`, `-o/--output-last-message <path>`, `--json`, `--skip-git-repo-check`, `--ephemeral`, `-C <dir>`, `-c key=value`; web search via `web_search = "live"` (default "cached"); `--search` documented as a global flag only | High (docs undated, read 2026-11-09) | https://learn.chatgpt.com/docs/non-interactive-mode , https://learn.chatgpt.com/docs/developer-commands?surface=cli |
| 20 | Codex auth and cost: `exec` reuses the saved ChatGPT login (plan allowance; Plus has 5-hour and possibly weekly limits) or `CODEX_API_KEY` (API pricing) | High | https://learn.chatgpt.com/docs/pricing |
| 21 | Codex data use follows ChatGPT data controls ("Improve the model"); Plus may be used for training unless turned off | Medium | https://help.openai.com/en/articles/11369540-using-codex-with-chatgpt |
| 22 | Codex CLI 0.166.0 installed on the XPS (`codex --version`) | High (checked locally) | local command |
| 23 | WallyCalendar (not built yet) plans `GET/POST/PATCH/DELETE /v1/events` and `GET /v1/dream-week` for a `wally` token on port 8081; Wally-side work deferred (C4, C5, C21) | High (read its SPEC/DECISIONS) | `C:\Users\user\Projects\WallyCalendar\docs\plans\wally-calendar\SPEC.md` |

## Unconfirmed — settle by test
- Moodle: does Tim's university allow mobile web services? Exact `core_course_get_contents` fields.
- Spotify `recently-played` limit 50 / scope `user-read-recently-played` after 2026 changes.
- Android 15/16 or Samsung limits on usage stats.
- JNA `User32` `GetForegroundWindow`/`GetWindowText`/`GetLastInputInfo` without admin.
- Hardware: `powercfg /batteryreport`, `Win32_Battery`, `Get-PhysicalDisk` without admin.
- qwen3.5:9b tool-call reliability on current Ollama.
- Codex: whether `codex exec --help` on 0.166.0 matches row 19; typical latency; explicit terms for scripted personal use on a ChatGPT login (PLAN Task 12.1).
