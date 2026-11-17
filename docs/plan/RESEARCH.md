# Research: Current versions and facts for the personal-assistant stack
Date: 2026-11-05

Scope: Kotlin everywhere. Compose Multiplatform (Android: Galaxy S25, Windows desktop: Dell XPS 16), Ktor server on a 4 GB Debian box, SQLite via SQLDelight, Tailscale between devices.

Confidence: **High** = primary source (official docs, registry, repo), or two independent sources agree. **Medium** = one good secondary source, or a primary source that only covers part of the claim. **Low** = only community reports, or sources conflict.

"Stable" below means the newest non-alpha/beta/RC release I found on the date above.

---

## 1. Kotlin, Compose Multiplatform, AGP, Gradle, Android SDK levels

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| Kotlin | Latest stable. Released October 8, 2026, as a bug-fix release on 2.4.20 (Sep 7, 2026). | **2.4.21** | [Kotlin releases](https://kotlinlang.org/docs/releases.html) | High |
| Compose Multiplatform (CMP) | Latest stable, released 15 Oct. 1.13.0-beta02 (04 Nov) is a pre-release. | **1.12.2** | [CMP GitHub releases](https://github.com/JetBrains/compose-multiplatform/releases), [CMP compatibility page](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | High |
| CMP desktop stability | Stability table: Android **Stable**, iOS **Stable**, Desktop (JVM) **Stable**, Web (Wasm) **Beta**. Windows 10+ (x86-64, arm64) is a supported desktop OS. | n/a | [Supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html), [compatibility page](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | High |
| CMP desktop JDK | "Compose Multiplatform for desktop supports only JDK 11 or later" and "Only JDK 17 or later is supported for packaging native distributions." | JDK 17+ to package | [compatibility page](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | High |
| CMP 1.12.2 bundled libs | Material3 in CMP 1.12.2 is listed as `1.12.0-alpha03` (an alpha). Lifecycle 2.11.0, Navigation 2.10.0-rc01. | see finding | [What's new in CMP 1.12](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html) | Medium (page only, not cross-checked) |
| CMP 1.13 (upcoming, not stable) | 1.13.0-alpha02 notes say "`compileSdk` 37.1 is required" and Kotlin was raised to 2.4.20. 1.13.0-alpha01 raised Android minSdk to 24. | pre-release | [CMP GitHub releases](https://github.com/JetBrains/compose-multiplatform/releases) | High |
| Android Gradle Plugin (AGP) | Newest AGP is 9.5 (9.5.0). Android Studio Rabbit 2 (2026.2.2), the current stable Studio, supports "7.1-9.5". AGP 9.5 needs Gradle "9.6.0" minimum and JDK 17, max API level 37. | **9.5.0** | [AGP 9.5 release notes](https://developer.android.com/build/releases/gradle-plugin), [About AGP](https://developer.android.com/build/releases/about-agp) | High |
| **KMP-tested AGP range** | Kotlin Multiplatform plugin 2.4.20–2.4.21 is tested with **Gradle 7.6.3–9.7.0** and **AGP 8.5.2–9.3.1**. **AGP 9.5 is outside the tested range.** | AGP ≤ 9.3.1 | [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) | High |
| AGP to Gradle mapping | 9.5 needs Gradle 9.6.0, 9.4 needs 9.6.0, **9.3 needs 9.5.0**, 9.2 needs 9.4.1. | table | [About AGP](https://developer.android.com/build/releases/about-agp) | High |
| Gradle | Latest release is 9.8.2 (Oct 19, 2026). Kotlin 2.4.20 is "fully compatible with Gradle 7.6.3 through 9.7.0". Newer Gradle may give deprecation warnings. | **9.8.2** latest; **9.7.0** is the newest Kotlin-tested | [Gradle releases](https://gradle.org/releases/), [What's new in Kotlin 2.4.20](https://kotlinlang.org/docs/whatsnew2420.html) | High |
| KMP Android plugin change | The new `com.android.kotlin.multiplatform.library` plugin is recommended. Rename `androidTarget {}` to `android {}`. The legacy Android source set layout was removed in Kotlin 2.4.0. | n/a | [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) | High |
| Android API 37 tooling | API 37.0 needs at least AGP 9.1.1 and Android Studio Panda 3. API 36 needs AGP 8.9.1. | n/a | [About AGP](https://developer.android.com/build/releases/about-agp) | High |
| Android 17 status | Android 17 is API 37. Platform Stability (API locked) came at Beta 3 (Mar 2026), and the last listed beta was Beta 4.1 (June 1, 2026). Minor SDKs 37.1 and 37.2 appear in QPR betas. I did **not** find the date of the final stable release. | API 37 | [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes), [Android 17 overview](https://developer.android.com/about/versions/17) | Medium |
| Play target SDK rule (for reference only; app is sideloaded) | "Starting August 31 2026: New apps and app updates must target Android 16 (API level 36) or higher". Sideloaded apps aren't bound by this. | targetSdk 36+ | [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk) | High |
| Recommended compileSdk/targetSdk | My inference from the rows above: **compileSdk 37** (supported by AGP ≥ 9.1.1), **targetSdk 36 or 37**, minSdk 26+ (Health Connect ≥ 24, SQLDelight ≥ 23). This is a recommendation, not an official rule. | 37 / 36–37 | derived | Medium |

**Suggested build combination (my inference):** Kotlin 2.4.21 + AGP 9.3.1 + Gradle 9.7.0 + CMP 1.12.2. This keeps every piece inside JetBrains' tested range. AGP 9.5.0 + Gradle 9.8.x is newer, but JetBrains hasn't listed it as tested for KMP.

---

## 2. Ktor and Java on Debian

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| Ktor (server + client, same version) | Latest stable, published 29 Oct 2026. No newer EAP or RC listed. | **3.6.1** | [Ktor GitHub releases](https://github.com/ktorio/ktor/releases), [Maven Central metadata](https://repo1.maven.org/maven2/io/ktor/ktor-server-core/maven-metadata.xml) | High |
| Ktor minimum JDK | **Not found** in Ktor docs, the README, or the 3.x migration guide (the old "server requirements" URL returns 404). | unconfirmed | [Ktor 3 migration](https://ktor.io/docs/migrating-3.html), [Ktor README](https://github.com/ktorio/ktor/blob/main/README.md) | not confirmed |
| Recommended JDK for running Ktor | My recommendation: **JDK 21 LTS** (`openjdk-21-jre-headless`), the LTS Debian 13 ships in main. JDK 25 is also in trixie. Not an official Ktor statement. | 21 | derived from Debian rows below | Medium |
| Kotlin bytecode | "Starting with Kotlin 2.4.0, the compiler can generate classes containing Java 26 bytecode." | n/a | [What's new in Kotlin 2.4.0](https://kotlinlang.org/docs/whatsnew24.html) | High |
| Debian 12 bookworm (oldstable) main | Only `openjdk-17-jre-headless` 17.0.21+9-1~deb12u1 shows up. No 21 or 25 in main. | JDK 17 | [packages.debian.org search](https://packages.debian.org/search?keywords=openjdk&searchon=names&suite=all&section=main) | High (the search page notes some results may be suppressed) |
| Debian 13 trixie (stable) main | `openjdk-21-jre-headless` 21.0.13+9-1~deb13u2 and `openjdk-25-jre-headless` 25.0.5+8-1~deb13u1. | JDK 21, 25 | same | High |

---

## 3. SQLDelight, kotlinx.serialization, kotlinx-datetime

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| SQLDelight | Latest stable (Maven Central `lastUpdated` 2026-10-06). The docs page still shows 2.4.0. 2.4.0 needs Gradle 8.2.1+. 2.3.2 raised Android minSdk to 23. 2.1.0 changed generated insert/update/delete to return the row count. | **2.4.1** | [Maven metadata](https://repo1.maven.org/maven2/app/cash/sqldelight/android-driver/maven-metadata.xml), [GitHub releases](https://github.com/sqldelight/sqldelight/releases) | High |
| SQLDelight plugin id | `id("app.cash.sqldelight") version "2.4.1"` | 2.4.1 | [SQLDelight multiplatform docs](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/) | High |
| Android driver | `app.cash.sqldelight:android-driver:2.4.1` | 2.4.1 | same | High |
| JVM SQLite driver (desktop + Ktor server) | `app.cash.sqldelight:sqlite-driver:2.4.1` | 2.4.1 | same | High |
| kotlinx.serialization | Latest stable, released 19 Oct, "based on Kotlin 2.4.10". | **1.12.0** | [GitHub releases](https://github.com/Kotlin/kotlinx.serialization/releases) | High (year inferred from the Kotlin version) |
| kotlinx-datetime | Latest stable (Maven `lastUpdated` 2026-05-07). Needs Kotlin stdlib "not lower than `2.3.21`". | **0.8.0** | [Maven metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml), [README](https://github.com/Kotlin/kotlinx-datetime/blob/master/README.md) | High |
| Instant/Clock API change | 0.7.0: "Remove `kotlinx.datetime.Instant` and `kotlinx.datetime.Clock` in favor of `kotlin.time.Instant`". 0.7.1 added them back as typealiases. In 0.8.0 they're gone from the main artifact and kept only in `0.8.0-0.6.x-compat`. **Use `kotlin.time.Instant` / `kotlin.time.Clock`.** | n/a | [kotlinx-datetime releases](https://github.com/Kotlin/kotlinx-datetime/releases), [README](https://github.com/Kotlin/kotlinx-datetime/blob/master/README.md) | High |
| kotlin.time.Instant stability | The API reference marks members "Since Kotlin 2.3" with no `@ExperimentalTime` annotation, which suggests it's stable from 2.3. The docs don't use the word "stable". | Kotlin 2.3+ | [kotlin.time.Instant API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-instant/) | Medium |

---

## 4. Google OAuth (Gmail + Calendar) for a single-user app

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| (a) Testing status gives 7-day tokens | Yes, still true. External apps in "Testing" are "issued a refresh token expiring in 7 days", unless the only scopes are name/email/profile. Testing is capped at 100 test users. | n/a | [Google OAuth2 overview](https://developers.google.com/identity/protocols/oauth2), [Manage App Audience](https://support.google.com/cloud/answer/15549945) | High |
| (b) Scope classification | `gmail.readonly` is a **Restricted** scope ("require restricted scope OAuth App Verification"; storing it on servers also needs a security assessment). I couldn't confirm `calendar.readonly` as "Sensitive" because the Calendar scopes page doesn't use that word. | n/a | [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [Calendar auth](https://developers.google.com/workspace/calendar/api/auth) | High for Gmail; Calendar not confirmed |
| (b) Unverified "In production" | Google: in production, an app requesting unverified sensitive/restricted scopes shows an **"Unverified apps" warning**. The **user cap is 100 new users** over the project's lifetime and can't be reset. Google lists "personal use (fewer than 100 users)" as a case where verification isn't mandatory, and users can click through the warning. | n/a | [Manage App Audience](https://support.google.com/cloud/answer/15549945), [Unverified apps](https://support.google.com/cloud/answer/7454865), [When verification is not needed](https://support.google.com/cloud/answer/13464323) | High (warning + cap); Medium (restricted-scope specifics) |
| (b) Do production tokens expire? | Google Health API setup docs: in production, "refresh tokens generally don't expire unless they are revoked", or after about 6 months unused. rclone's docs (an independent source) say publishing a personal app stops the weekly expiry and that you can skip verification, accepting the unverified warning. Neither source specifically tests **gmail.readonly** (restricted) with an unverified app. | n/a | [Google Health API setup](https://developers.google.com/health/setup), [rclone Drive docs](https://rclone.org/drive/) | Medium |
| (b) Other ways tokens die | 100 refresh tokens per Google Account per client ID; a new one beyond that "automatically invalidates the oldest refresh token without warning". | n/a | [Google OAuth2 overview](https://developers.google.com/identity/protocols/oauth2) | High |
| (c) Device flow | **Not usable.** The device flow allows only email, openid, profile, drive.appdata, drive.file, youtube and youtube.readonly. Gmail and Calendar scopes aren't on the list. | n/a | [OAuth for limited-input devices](https://developers.google.com/identity/protocols/oauth2/limited-input-device) | High |
| (c) Recommended flow for the headless server | Use a **"Desktop app" OAuth client with a loopback redirect** (`http://127.0.0.1:port`), "the recommended mechanism" for desktop apps. The OOB copy/paste flow "is no longer supported". Practical setup (my inference): run the consent once on the laptop (or through an SSH tunnel to the server's loopback port), then store the refresh token on the server. | n/a | [OAuth for installed apps](https://developers.google.com/identity/protocols/oauth2/native-app) | High (flow); Medium (the run-elsewhere-then-copy pattern) |
| (d) Java client libraries, current versions | `google-api-services-gmail` **v1-rev20261005-2.9.2**; `google-api-services-calendar` **v3-rev20260925-2.0.0**; `google-api-client` **2.9.2**; `google-auth-library-oauth2-http` **1.55.0** (all updated in Oct 2026). | see finding | Maven Central: [gmail](https://repo1.maven.org/maven2/com/google/apis/google-api-services-gmail/maven-metadata.xml), [calendar](https://repo1.maven.org/maven2/com/google/apis/google-api-services-calendar/maven-metadata.xml), [api-client](https://repo1.maven.org/maven2/com/google/api-client/google-api-client/maven-metadata.xml), [auth](https://repo1.maven.org/maven2/com/google/auth/google-auth-library-oauth2-http/maven-metadata.xml) | High |
| (d) REST via Ktor vs Java libs | No official guidance found. My recommendation: **plain REST with the Ktor client + kotlinx.serialization**, for a few read-only calls (list events, list/get messages, token refresh). It reuses libraries you already have, avoids a second HTTP/JSON stack, and is lighter on a 4 GB server. Switch to the Java libs only if you need many endpoints. | n/a | opinion | n/a |

---

## 5. Android Health Connect + Garmin

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| connect-client | Stable **1.1.0** (released Oct 8, 2025). Latest alpha is 1.2.0-alpha06 (Aug 26, 2026). 1.2.0-alpha05 raised minSdk to 24. | **1.1.0** | [Health Connect release notes](https://developer.android.com/jetpack/androidx/releases/health-connect), [Google Maven metadata](https://dl.google.com/android/maven2/androidx/health/connect/connect-client/maven-metadata.xml) | High |
| Record classes | `SleepSessionRecord` (READ_SLEEP), `StepsRecord` (READ_STEPS), `RestingHeartRateRecord` (READ_RESTING_HEART_RATE), `ExerciseSessionRecord` (READ_EXERCISE), plus `HeartRateRecord` (READ_HEART_RATE). Permissions take the form `android.permission.health.READ_*`. | n/a | [Health Connect data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types) | High |
| Default history window | "By default, all applications can read data from Health Connect for up to 30 days prior to when any permission was first granted." An app has no limit on reading its own data on Android 14+. | 30 days | [Read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data) | High |
| Older history permission | `android.permission.health.READ_HEALTH_DATA_HISTORY` (`HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY`). Background reads need `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`; check `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` first. | n/a | [Data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types), [Read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data) | High |
| Steps attribution change | From the June 2026 Health Connect update, on-device phone steps are attributed to a per-device "Synthetic Package Name" instead of `android`. Use `aggregate()` without a DataOrigin filter to avoid this issue. | n/a | [Read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data) | High |
| Garmin Connect writes to Health Connect | Yes, one-way, since mid-2025. Garmin's list (as reported): **Activity:** active/total calories, cycling cadence, distance, elevation, **heart rate**, speed, **steps**, swimming strokes. **Wellness:** body fat, calories, floors, heart rate, **sleep data**, steps/step distance, weight. Not shared: training load, HRV status, Body Battery, etc. | n/a | [Garmin Rumors, Jul 1 2025, citing Garmin FAQ](https://garminrumors.com/garmin-clarifies-what-data-its-sharing-with-google-health-connect-heres-the-full-list/). [Garmin FAQ](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5) returned 403 to my fetcher. | Medium |
| Garmin: resting HR | **Not confirmed.** Garmin's list says only "Heart Rate Data". Third-party sites disagree on resting HR. | ? | [sahha.ai](https://sahha.ai/blog/garmin-developer-program-paused/), [ultrafit360](https://ultrafit360.com/blog/garmin-apple-health-health-connect) | Low |
| Garmin: exercise sessions | **Not confirmed.** The list names activity metrics, not `ExerciseSessionRecord` sessions. | ? | same as above | Low |
| Garmin package name (dataOrigin) | `com.garmin.android.apps.connectmobile` is the Garmin Connect Android package id. I couldn't confirm that this exact string shows up as `dataOrigin` in Health Connect. Log `record.metadata.dataOrigin.packageName` on the device to check. | n/a | [AppGoblin listing](https://appgoblin.info/apps/com.garmin.android.apps.connectmobile), [AppFollow listing](https://apps.appfollow.io/android/garmin-connecttm/com.garmin.android.apps.connectmobile?country=mk) | Medium (package id); unconfirmed as dataOrigin |

---

## 6. Ollama

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| Ollama | Latest stable, released 03 Nov 2026. v0.40.15-rc1 is a pre-release. | **0.40.14** | [Ollama GitHub releases](https://github.com/ollama/ollama/releases) | High |
| Vulkan backend | Docs: "Vulkan is enabled by default when the backend is installed"; on Windows most GPU drivers include Vulkan. Disable with `OLLAMA_VULKAN=0`. Docs warn the Vulkan iGPU "may be unstable" on iGPU+dGPU systems. | n/a | [Ollama GPU docs](https://docs.ollama.com/gpu) | High |
| Intel Arc iGPU on Windows | Source code defines `OLLAMA_IGPU_ENABLE` ("whether integrated GPUs can be selected"). A recent community report (Ollama 0.40.11, Arc 140T iGPU, Windows 11) needed `OLLAMA_VULKAN=1` + `OLLAMA_IGPU_ENABLE=1` to get "100% GPU" in `ollama ps`. In an Ollama GitHub issue about a Panther Lake iGPU, a maintainer replied "Try the Vulkan backend" and the poster said it worked. **No report found for the Arc B390 specifically.** | n/a | [envconfig source](https://raw.githubusercontent.com/ollama/ollama/main/envconfig/config.go), [dev.to report](https://dev.to/fanmixco/how-to-enable-ollama-gpu-acceleration-on-intel-arc-in-windows-2ll6), [Issue #14572](https://github.com/ollama/ollama/issues/14572) | Medium |
| Bind address | Default "binds 127.0.0.1 port 11434". Set `OLLAMA_HOST` (for example to the laptop's Tailscale IP `100.x.y.z:11434`, or `0.0.0.0:11434`). On Windows, quit Ollama, set the env var in the user's environment variables, then restart. | n/a | [Ollama FAQ](https://docs.ollama.com/faq), [envconfig source](https://raw.githubusercontent.com/ollama/ollama/main/envconfig/config.go) | High |
| /api/chat shape | `POST /api/chat` with `{model, messages:[{role: system/user/assistant/tool, content}], stream (default true), format ("json" or a JSON schema), options {temperature, num_ctx, num_predict, ...}, keep_alive, think, tools}`. A non-streaming reply has `message{role,content}`, `done`, `done_reason`, `eval_count`, `eval_duration` (ns), `prompt_eval_count`, `total_duration`. | n/a | [Ollama /api/chat docs](https://docs.ollama.com/api/chat) | High |
| Defaults | `OLLAMA_KEEP_ALIVE` default 5m. Context-length default conflicts: the FAQ says 4096, the source says it scales with VRAM. Set `num_ctx` explicitly. | n/a | [FAQ](https://docs.ollama.com/faq), [envconfig source](https://raw.githubusercontent.com/ollama/ollama/main/envconfig/config.go) | Medium |
| (a) Small CPU model (≤ ~2.5 GB RAM) candidates | `qwen3:1.7b` **1.4 GB** (40K ctx); `llama3.2:3b` **2.0 GB** (128K); `gemma3:1b` **806 MB** (32K); `qwen3.5:2b-q4_K_M` **1.9 GB**. **Recommendation: `qwen3:1.7b`.** It leaves room for the KV cache (the memory for the conversation context) within 2.5 GB. Send `think:false` and keep `num_ctx` around 4096. `llama3.2:3b` is the fallback if quality is too low, but it's tighter on RAM. | see finding | [qwen3 tags](https://ollama.com/library/qwen3/tags), [llama3.2 tags](https://ollama.com/library/llama3.2/tags), [gemma3 tags](https://ollama.com/library/gemma3/tags), [qwen3.5 tags](https://ollama.com/library/qwen3.5/tags) | High (sizes); recommendation is opinion |
| (b) Chat model for the 32 GB laptop | Candidates: `gemma4:12b` (q4_K_M **8.0 GB**, 256K ctx), `qwen3.5:9b` (**6.6 GB**), `gemma4:26b` MoE (**16–19 GB**). **Recommendation: `gemma4:12b`**, or `qwen3.5:9b` if you want faster replies. Both fit easily within 32 GB of shared memory. | see finding | [gemma4 tags](https://ollama.com/library/gemma4/tags), [qwen3.5 tags](https://ollama.com/library/qwen3.5/tags) | High (sizes); recommendation is opinion |
| Tokens/sec | **Not found** for the Arc B390 or for CPU-only small models. One unnamed-model llama.cpp Vulkan figure (Panther Lake 4.8 tok/s vs Lunar Lake 3.9) comes from an aggregator site and isn't usable. Measure with `eval_count / eval_duration`. | n/a | [lavx.hu](https://news.lavx.hu/article/intel-arc-b390-xe3-graphics-benchmarked-on-linux-panther-lake-performance-deep-dive) | Low |

---

## 7. rclone

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| rclone upstream | Latest stable | **v1.75.2** | [rclone downloads](https://rclone.org/downloads/) | High |
| Debian packages | bookworm **1.60.1+dfsg-2+b5**, trixie **1.60.1+dfsg-4**, sid 1.75.2-2. The Debian stable package is far behind upstream, so use the upstream `.deb` or the install script. | 1.60.1 | [packages.debian.org](https://packages.debian.org/search?keywords=rclone&searchon=names&exact=1&suite=all&section=all) | High |
| Own client_id | The Drive docs say the shared client_id "is being retired" and "creating one is now required rather than merely recommended". A "Testing" app's grants "expire after a week"; publish it (verification can be skipped; you'll see an unverified warning). `drive.file` scope limits rclone to files it created itself, which is a good fit for backups. | n/a | [rclone Google Drive docs](https://rclone.org/drive/) | High |
| Headless setup | On the server, run `rclone config` and answer **n** to the browser question. On the laptop, run `rclone authorize "drive"` (same rclone version recommended), then paste the token at `config_token>`. Alternatives: copy the config file, or SSH-tunnel port 53682. | n/a | [rclone remote setup](https://rclone.org/remote_setup/) | High |
| Crypt over Drive | crypt "wraps another remote" (e.g. `gdrive:backup`). Filename encryption options are `standard`/`obfuscate`/`off`. `password2` (salt) is "Optional but recommended". Content uses NaCl SecretBox (XSalsa20 + Poly1305); keys come from scrypt. **If you lose the config or passwords, the data can't be recovered.** Keep a copy of the passwords off the server. | n/a | [rclone crypt docs](https://rclone.org/crypt/) | High |

---

## 8. Nightly SQLite snapshot of a live WAL database

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| Don't copy the file | "If a database file is separated from its WAL file, then transactions that were previously committed to the database might be lost, or the database file might become corrupted." Never use plain `cp` on a live DB. | n/a | [SQLite WAL](https://www.sqlite.org/wal.html) | High |
| VACUUM INTO | "an alternative to the backup API for generating backup copies of a live database". Output is "a consistent snapshot", minimal size, and the target file must not already exist. Downsides: more CPU, and an interrupted run can leave a corrupt output file. | n/a | [SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html) | High |
| Backup API / `.backup` | Gives a consistent snapshot. Called with N = -1, it copies all pages in one step while holding a shared lock on the source for that call. If done in small steps, writes from other connections restart the backup. The CLI documents `.backup ?DB? FILE` but doesn't say how it is implemented. | n/a | [Backup API](https://www.sqlite.org/backup.html), [sqlite3_backup_step](https://www.sqlite.org/c3ref/backup_finish.html), [CLI docs](https://www.sqlite.org/cli.html) | High (API); Medium (CLI internals) |
| Recommendation | Run **`VACUUM INTO '/backups/app-YYYY-MM-DD.db'`** from the Ktor app itself (via the SQLDelight driver, so no extra tool is needed). Write to a temp name, then rename after success. For a small personal DB, either method is safe. VACUUM INTO is simpler and gives the smallest file to upload. | n/a | inference from above | Medium |
| sqlite3 CLI in Debian | `sqlite3` package: bookworm **3.40.1-2+deb12u2**, trixie **3.46.1-7+deb13u2** (`apt install sqlite3`). VACUUM INTO needs SQLite 3.27+, so both are fine. *The 3.27 cut-off comes from my memory; I didn't re-check it.* | 3.40.1 / 3.46.1 | [packages.debian.org](https://packages.debian.org/search?keywords=sqlite3&searchon=names&exact=1&suite=all&section=all) | High (versions) |

---

## 9. Compose desktop packaging for Windows

| Item | Finding | Version | Source | Confidence |
|---|---|---|---|---|
| Formats & tasks | `TargetFormat.Msi`, `TargetFormat.Exe`. Tasks: `packageMsi`, `packageExe`, `packageDistributionForCurrentOS`, `packageReleaseMsi` (ProGuard), `createDistributable`/`runDistributable` (no installer). No cross-compilation: build the MSI on Windows. | n/a | [CMP native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) | High |
| jpackage + JDK | The plugin uses `jpackage`, which needs **JDK 17+**. | JDK 17+ | same | High |
| Bundled runtime | `jlink` bundles a trimmed JRE inside the installer, so users don't need Java. Modules aren't auto-detected: add `modules("java.sql", ...)` (`java.sql` is needed for the SQLite JDBC driver) or use `includeAllModules = true`. The `suggestRuntimeModules` task helps (named `suggestModules` in some plugin versions; the plan says to check `tasks --all`). | n/a | same | High |
| WiX | jpackage needs "WiX 3.0 or later" for exe/msi. The Compose Gradle plugin **downloads WiX 3.11 automatically** (tasks `downloadWix`/`unzipWix`, cached in `<gradleUserHome>/compose-jb/wix311.zip`). Turn this off with `compose.desktop.application.downloadWix=false`, or point `WIX_PATH` at your own install. You don't need to install WiX by hand. | WiX 3.11 | [Oracle jpackage JDK 25](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html), [CMP plugin source wixToolset.kt](https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/desktop/application/internal/wixToolset.kt) | High |
| Upgrades | Set a constant `upgradeUuid` so new MSIs upgrade the old install. Version format is `MAJOR.MINOR.BUILD` (≤255.≤255.≤65535). | n/a | [CMP native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) | High |

---

## Could not verify

1. **Ktor's official minimum JDK.** No current Ktor page states it. Running the server on JDK 21 (or 17 on bookworm) is very likely fine, but this is unconfirmed. To settle it: the Ktor docs' requirements page (if restored) or the bytecode target in the published jars.
2. **Whether an unverified "In production" app keeps non-expiring refresh tokens for the restricted `gmail.readonly` scope.** Google's docs plus rclone suggest yes (with the warning and the 100-user cap). A Google forum post and one blog say restricted scopes need verification in production. To settle it: publish the app, do the consent flow, and check that the token response has no `refresh_token_expires_in: 604800`. Also confirm the token still works after 8 days.
3. **Whether `calendar.readonly` is officially "Sensitive".** The Calendar scopes page doesn't classify it. To check: the Cloud Console's scope picker shows the category.
4. **Garmin → Health Connect: resting heart rate and exercise sessions.** Not in Garmin's published list. Garmin's FAQ page blocked my fetch. To settle it: open Health Connect on the S25 → App permissions → Garmin Connect, or read records and log `dataOrigin`.
5. **Garmin's dataOrigin string.** The package id `com.garmin.android.apps.connectmobile` is confirmed. That this exact string appears in `metadata.dataOrigin` is not.
6. **Ollama on the Arc B390 (Panther Lake, Xe3) on Windows.** Only Lunar Lake / Arc 140T and generic Panther Lake reports exist. No tokens/sec figures for B390 or for CPU-only small models.
7. **Android 17 final release date**, and whether CMP 1.12.2 needs a specific compileSdk (it's only stated for 1.13).
8. **Exact release years** on some GitHub pages (CMP 1.12.2 "15 Oct", kotlinx.serialization 1.12.0 "19 Oct"). These are inferred as 2026 from the Kotlin versions they depend on.
