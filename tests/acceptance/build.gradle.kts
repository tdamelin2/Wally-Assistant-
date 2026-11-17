plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":shared"))
    testImplementation(project(":server"))
    testImplementation(libs.kotlinx.coroutinesCore)
    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.runtime)
    testImplementation("app.cash.sqldelight:sqlite-driver:2.4.1")
    testImplementation("app.cash.sqldelight:runtime:2.4.1")
    testImplementation("io.ktor:ktor-client-core:3.6.1")
    testImplementation("io.ktor:ktor-client-mock:3.6.1")
    testImplementation("io.ktor:ktor-server-core-jvm:3.6.1")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.6.1")
}
