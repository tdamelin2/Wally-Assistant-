# Build notes (Phase 1)

## Wizard AndroidX / library versions kept from the generated starter
The starter project (`_wizard/`, now removed) pinned these; they are kept in `gradle/libs.versions.toml`:

| Library | Version |
|---|---|
| androidx activity | 1.13.0 |
| androidx appcompat | 1.8.0 |
| androidx core | 1.19.1 |
| espresso | 3.7.0 |
| lifecycle | 2.11.0 |
| androidx test ext | 1.3.0 |
| androidx test runner | 1.7.0 |
| material3 (Compose) | 1.12.0-alpha03 |
| kotlinx-coroutines | 1.11.0 |
| logback | 1.6.6 |

Pinned by the plan instead of the wizard: Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.3.1,
Ktor 3.6.1 (wizard had 3.6.0), SQLDelight 2.4.1, kotlinx.serialization 1.12.0, kotlinx-datetime 0.8.0,
Gradle 9.7.0 (wrapper has the official `distributionSha256Sum`), minSdk 26 (wizard had 24).

## compileSdk (D44)
`compileSdk = 36` failed `.\gradlew build`: `androidx.compose.ui:ui-android:1.12.1` requires compiling
against Android API 37 or later. `android-compileSdk` in the catalog is therefore `37` (used by both
`:shared` and `:androidApp`). `targetSdk` and `minSdk` stay 36 and 26. Android platform 37 was
available/installed automatically.

## SQLDelight with `com.android.kotlin.multiplatform.library`
No problem found. `.\gradlew :shared:generateCommonMainAssistantDbInterface` succeeds, the generated
`AssistantDb` compiles for the JVM target and the Android target (`:androidApp:assembleDebug`
and `build` pass), and `android-driver` (androidMain) / `sqlite-driver` (jvmMain) resolve. The plugin
setup was not changed. Generated row classes use the table names (`Health_day`, `Cal_event`,
`Mail_item`; query holders `health_dayQueries` etc.).

## Other findings
- Compose Resources package: `compose.resources { packageOfResClass = "dev.tim.assistant.generated.resources" }`
  in `:shared` (the starter's default was derived from the old project name).
- `:server` depends on `:shared` (D45), which brings Compose jars with duplicate file names into the
  server distribution. `distTar`/`distZip` failed with "duplicate entry", so `:server` sets
  `duplicatesStrategy = EXCLUDE` on Tar/Zip/Copy tasks.
- `gradle/gradle-daemon-jvm.properties` (AZUL21 criteria from the starter) was deleted; `JAVA_HOME` is
  Temurin 21 and the 17 toolchain for `:shared`, `:server`, `:desktopApp` is found by Gradle
  (foojay resolver kept in `settings.gradle.kts`).
- The server takes its bind address as the first argument (`server <tailscale-ip>`), exits with code 2
  and prints `usage: server <tailscale-ip>` when it is missing; it never falls back to 0.0.0.0.
- The wrapper jar is the one shipped with the starter (from 9.5.1); it downloads Gradle 9.7.0 and
  verifies it against the checksum in `gradle-wrapper.properties`.

## Phase 3: database upgrade (meal table)
- SQLDelight needed no gradle setting for migrations: `shared/src/commonMain/sqldelight/migrations/1.sqm`
  (a copy of the `CREATE TABLE meal` statement) is picked up by the plugin and `AssistantDb.Schema.version` becomes 2.
- JDBC (desktop and server) do not run migrations themselves: both call `prepareSchema(driver)`
  (`shared/src/jvmMain/.../data/SchemaSetup.kt`). It wraps the upgrade in the database's own transaction
  (`AssistantDb(driver).transaction { }`), because a raw `BEGIN`/`COMMIT` sent through `driver.execute` can land on a
  different connection and fail with "cannot commit - no transaction is active".
- Android is unchanged: `AndroidSqliteDriver(AssistantDb.Schema, ...)` migrates 1 -> 2 itself.
- Old desktop and server files have `user_version` 0 (JDBC `Schema.create` never set it); `prepareSchema` treats that as 1.

## Phase 6: icon source
- Checked `org.jetbrains.compose.material:material-icons-core:1.12.2` on Maven Central and the JetBrains Compose repository: that version does not exist. The library stopped at 1.7.3 (December 2024), which is not the project's Compose version (1.12.2). Mixing it in would add a library at a different version, so it was not added.
- Chosen: XML vector drawables (Material Symbols / Material icons paths, Apache-2.0) in `shared/src/commonMain/composeResources/drawable/` (`ic_add`, `ic_menu`, `ic_settings`, `ic_more_vert`, `ic_arrow_back`, `ic_cloud_done`, `ic_sync`, `ic_cloud_off`, `ic_today`, `ic_list`, `ic_bar_chart`, `ic_favorite`, `ic_chat`). They are loaded with `vectorResource(Res.drawable.ic_x)` on both the laptop and the phone. No new library or version.
