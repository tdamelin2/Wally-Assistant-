import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    // Windows foreground window and idle time for the Screentime tracker (no admin rights needed).
    implementation(libs.jna)
    implementation(libs.jna.platform)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        // jpackage (needed for the MSI) is not in every JDK Gradle may run on, so use the JDK 17 toolchain.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
            .get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Wally"
            packageVersion = "1.18.0"
            modules("java.instrument", "java.logging", "java.management", "java.sql", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/wally.ico"))
                upgradeUuid = "263d9a33-d272-424f-9df3-e5168273ba55"
                menu = true
                shortcut = true
            }
        }
    }
}

// Dev runs use their own database so they never touch the installed app's real data.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    environment("ASSISTANT_DB", "${System.getenv("LOCALAPPDATA")}\\Assistant\\dev.db")
}
