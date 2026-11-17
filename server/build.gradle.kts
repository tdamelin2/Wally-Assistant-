plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "dev.tim.assistant"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.tim.assistant.server.MainKt")
}

// the fat jar name is part of the deploy contract (ops/deploy-server.ps1 copies server/build/libs/server-all.jar)
ktor {
    fatJar {
        archiveFileName.set("server-all.jar")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
    // Google client (P2-Q-CLA-07: direct coordinates, pinned to the same Ktor version as the catalog)
    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-cio:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    // study text extraction (Phase 9)
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)
    testImplementation("io.ktor:ktor-client-mock:3.6.1")
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

// shared pulls in Compose jars that exist twice under different groups (D45); keep the first copy
tasks.withType<Tar>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<Zip>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<Copy>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
