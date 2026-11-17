import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    android {
        namespace = "dev.tim.assistant.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.sqldelight.androidDriver)
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.androidx.health.connect.client)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(libs.ktor.clientOkhttp)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.clientMock)
            implementation(libs.sqldelight.sqliteDriver)
            // ApprovalTest runs two stores against the real server code in-process (test only)
            implementation(project(":server"))
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverTestHost)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.tim.assistant.generated.resources"
}

sqldelight {
    databases {
        create("AssistantDb") {
            packageName.set("dev.tim.assistant.db")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
