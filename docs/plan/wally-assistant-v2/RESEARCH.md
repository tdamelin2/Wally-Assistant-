# Research: Wally assistant v2 - UX, Compose, sync, push, Ollama, Codex
Date: 2026-11-10

## Answer
Current versions found: Compose Multiplatform 1.12.2, kotlinx-datetime 0.8.0, androidx.work 2.12.0, Ollama v0.40.16 and Codex CLI 0.167.0. WorkManager still has a 15-minute minimum for repeating jobs. To sync every 10 minutes, run a timer only while the app is open and let WorkManager handle the 15-minute background sync. For push without Google, use self-hosted ntfy (an open-source notification server) or UnifiedPush (an open standard for push without Google). For reminders at a fixed time, schedule them on the phone itself. Small models (qwen3:4b, llama3.2:3b, about 2-2.5 GB) fit in 8 GB of RAM, but the CPU speed figures I found are rough guesses. `codex exec` (Codex's non-interactive mode) can run on a headless Linux server: you log in once with `codex login --device-auth` or copy `~/.codex/auth.json` from another machine.

## Findings

### 1. UX patterns worth copying
Only #1 was checked against an app's own docs. The rest are common patterns from memory and were **not checked per app**. Treat them as design suggestions, not facts about those apps.

| # | Pattern | Cheap in Compose? | Confidence | Source |
|---|---------|------------------|------------|--------|
| 1 | Quick-add field that reads dates as you type ("tomorrow at 4 pm") and highlights them (Todoist) | Medium: you need your own parser or the LLM | High | [Todoist help](https://todoist.com/help/articles/205325931) (current) |
| 2 | Swipe right to complete, swipe left to reschedule or delete | Yes: `SwipeToDismissBox` (Material 3) | Low (pattern) | not verified |
| 3 | "Undo" snackbar (a short pop-up message at the bottom) after delete or complete, instead of a confirm dialog | Yes: `SnackbarHost` + action | Low (pattern) | not verified |
| 4 | Checkbox tick animation plus the row fading or sliding out | Yes: `AnimatedVisibility`, `animateItem` | Low (pattern) | not verified |
| 5 | Light haptic tap (small vibration) on complete | Yes: `LocalHapticFeedback` | Low (pattern) | not verified |
| 6 | Pull-to-refresh to force a sync | Yes: `PullToRefreshBox` (Material 3) | Low (pattern) | not verified |
| 7 | Friendly empty state ("All done for today") instead of a blank list | Yes: plain composable | Low (pattern) | not verified |
| 8 | Daily planning ritual: choose today's tasks from a backlog each morning (Sunsama-style) | Medium | Low (pattern) | not verified |
| 9 | Timeline of the day with tasks as time blocks (Structured-style) | Medium-High | Low (pattern) | not verified |
| 10 | One-tap mood or energy check-in with icons (Daylio/Bearable-style) | Yes | Low (pattern) | not verified |
| 11 | Streak or year-in-pixels view for habits | Medium: Canvas grid | Low (pattern) | not verified |
| 12 | Snooze presets in reminders ("1 h", "tonight", "tomorrow") | Yes: notification actions | Low (pattern) | not verified |
| 13 | Keyboard shortcut for quick-add on desktop (Things 3/Todoist-style) | Yes: `onKeyEvent` | Low (pattern) | not verified |

### 2. Compose Multiplatform and dates
| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | Latest stable is **1.12.2**, released "15 October" (GitHub "Latest" tag). 1.13.0 is still in beta | High | [GitHub releases](https://github.com/JetBrains/compose-multiplatform/releases) (2026-10), [What's new 1.13](https://kotlinlang.org/docs/multiplatform/whats-new-compose-113.html) |
| 2 | Material 3 `DatePicker`, `DatePickerDialog` and `TimePicker` work on Desktop. They appear in changelog mirrors from the 1.5 era, and the API docs list them under "common" (shared code) | Medium | [changelog mirror](https://code.fanruan.com/github/compose-jb/src/tag/v1.6.0/CHANGELOG.md), [API ref](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3/-time-picker-display-mode/-companion/-picker.html) |
| 3 | Shared-element transitions (an item animating smoothly between two screens): "Shared transition APIs are stable in 1.10" (Jetpack Compose Animation 1.10.0, Dec 2025) | High | [Compose Animation releases](https://developer.android.com/jetpack/androidx/releases/compose-animation) |
| 4 | `Modifier.animateItem()` was added in 1.7.0-alpha06 as a non-experimental replacement for `animateItemPlacement()`. `AnimatedVisibility` is a long-standing API. I did not check how either behaves in Compose Multiplatform specifically | Medium | same as above |
| 5 | kotlinx-datetime latest stable: **v0.8.0**. The page shows "07 May" but no year | High | [GitHub releases](https://github.com/Kotlin/kotlinx-datetime/releases) |
| 6 | MM-dd-yyyy works with the format DSL (a small builder language for date formats): `LocalDate.Format { monthNumber(); char('-'); day(); char('-'); year() }`. `monthNumber()` pads with zeros by default ("01/13/2021" in the official sample). Check the day function name in 0.8.0 before using it: older versions used `dayOfMonth()` | High (monthNumber/char), Medium (day name) | [monthNumber docs](https://kotlinlang.org/api/kotlinx-datetime/kotlinx-datetime/kotlinx.datetime.format/-date-time-format-builder/-with-year-month/month-number.html), [LocalDate.Format](https://kotlinlang.org/api/kotlinx-datetime/kotlinx-datetime/kotlinx.datetime/-local-date/-companion/-format.html) |

### 3. Android periodic sync
| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | Periodic work still has a **15-minute minimum** (`MIN_PERIODIC_INTERVAL_MILLIS`), and Doze (Android's battery-saving idle mode) can delay it further | High | [PeriodicWorkRequest ref](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) |
| 2 | androidx.work stable is **2.12.0** (Sept 23, 2026). It needs compileSdk 33+ and minSdk 24 | High | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| 3 | Foreground service alternative (a background task that must show a permanent notification): on Android 15+ targets, `dataSync` services get "6 hours in a 24-hour period", and Google recommends WorkManager instead | High | [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| 4 | While the app is open, a coroutine loop (`delay(10.minutes)` inside a lifecycle-aware scope) has no OS minimum. This is standard coroutine behaviour and was not checked against a doc | Medium | (reasoning) |

### 4. Push without Google
| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | ntfy server **v2.37.2** (Nov 3, 2026) and Android app **v1.25.2** (Jul 23, 2026). A cached copy disagreed (see Conflicts) | Medium | [ntfy releases](https://docs.ntfy.sh/releases/) |
| 2 | The ntfy app is on Play, F-Droid and GitHub. The F-Droid build "does not use Firebase", and the app doesn't use Firebase for self-hosted servers | High | [ntfy phone docs](https://docs.ntfy.sh/subscribe/phone) |
| 3 | ntfy works as a UnifiedPush distributor (the app that receives pushes for other apps) | High | [F-Droid: 5 years of UnifiedPush](https://f-droid.org/es/2026/01/08/unifiedpush-5-years.html) (2026-01) |
| 4 | Android 14: `SCHEDULE_EXACT_ALARM` is no longer granted automatically on new installs. Calendar and alarm apps can use `USE_EXACT_ALARM`, which is "granted on install", but app-store policy limits who may use it | High | [Android 14 exact alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms) |
| 5 | `POST_NOTIFICATIONS` has been a runtime permission (one the user must approve in a pop-up) since Android 13 (API 33). If the user denies it, they must turn notifications on in Settings | High | [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| 6 | FCM (Firebase Cloud Messaging, Google's push service) needs a Google project and Play Services, so it doesn't fit a "no Google" goal. Not researched further | Medium | (reasoning) |

### 5. Ollama
| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | Latest release **v0.40.16**, Nov 9, 2026 | High | [GitHub releases](https://github.com/ollama/ollama/releases) |
| 2 | Context length (how much text the model can see at once) defaults to 4096 tokens per the FAQ and can be changed with `OLLAMA_CONTEXT_LENGTH`. "Setting a larger context length will increase the amount of memory required" | High | [FAQ](https://docs.ollama.com/faq), [context length](https://docs.ollama.com/context-length) |
| 3 | `keep_alive` sets how long a model stays loaded in RAM: "10m", "24h", a negative value (forever), or 0 (unload now). It overrides `OLLAMA_KEEP_ALIVE` | High | [FAQ](https://docs.ollama.com/faq) |
| 4 | Download sizes: qwen3:4b about 2.5 GB, llama3.2:3b about 2.0 GB. Both fit in 8 GB of RAM with room for the OS | Medium | [webscraft](https://webscraft.org/blog/ollama-na-8-gb-ram-yaki-modeli-pratsyuyut-u-2026?lang=en), [insiderllm](https://insiderllm.com/guides/cpu-only-llms-what-actually-works/) |
| 5 | CPU speed estimates disagree widely: from "2-8 tokens per second" on sub-4B models to 25-45 tok/s on an 8-core laptop. Expect the low end on an old CPU | Low | [serverman](https://www.serverman.co.uk/?p=4095), [bestllmfor](https://bestllmfor.com/reviews/llama3-3b-deep-review/) |
| 6 | Llama 3.2 3B has a reported tool-use score of BFCL V2 67.0 (BFCL is a benchmark for tool calling). I found no matching score for qwen3:4b | Low | [bestllmfor](https://bestllmfor.com/reviews/llama3-3b-deep-review/) |

### 6. Codex CLI
| # | Finding | Confidence | Source |
|---|---------|------------|--------|
| 1 | Latest stable **0.167.0**, Nov 6, 2026 | High | [GitHub releases](https://github.com/openai/codex/releases) |
| 2 | On a headless machine (a server with no screen or browser), run `codex login --device-auth`: you enter a code on another device. The fallback is to log in elsewhere and copy `~/.codex/auth.json` to the server | High | [Codex auth docs](https://developers.openai.com/codex/auth) |
| 3 | `codex exec` is the non-interactive mode. It streams progress to stderr and prints the final message to stdout. It runs in a read-only sandbox by default, and actions that need approval fail | High | [Non-interactive docs](https://developers.openai.com/codex/noninteractive.md) |
| 4 | An API key via `CODEX_API_KEY` is supported for `codex exec` | Medium | [third-party guide](https://codex.danielvaughan.com/2026/04/01/codex-cli-authentication-flows-credential-management/) |

## Conflicts
- **ntfy versions:** one cached copy of the release page listed server v2.25.0 (Jun 2026). I trust v2.37.2 because it is the newer snapshot.
- **Ollama default context:** older FAQ copies say 2048 tokens. The current FAQ says 4096. Third-party sites describe RAM-based tiers that I couldn't confirm in the official docs.

## Unconfirmed
- Whether the UX patterns in section 1 (other than Todoist quick-add) match what each named app actually does.
- Real speed of each model on your laptop. Settle it with `ollama run <model> --verbose`.
- Qwen3.5 and Gemma sizes or tool-calling quality: not found.
- How reliably qwen3:4b handles tool calling: not found.
- The release year of kotlinx-datetime 0.8.0, and the exact name of the day-of-month function in its format DSL.
- Whether the device code login can be blocked by account settings (one third-party claim).
