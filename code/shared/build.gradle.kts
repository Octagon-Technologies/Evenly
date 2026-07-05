import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Room (KMP) — the compiler reads @Entity/@Dao in commonMain and generates one
// implementation per target. schemaDirectory() makes Room export a JSON schema per
// version so migrations can be diffed/tested in CI (Room fails the build without it).
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    // Room KMP generates an `actual object` for @ConstructedBy; expect/actual classes are still
    // marked Beta, so opt in explicitly to keep the build warning-free.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "da.chelimo.sharecost.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // Networking engine
            implementation(libs.ktor.client.okhttp)
            // DI (Android)
            implementation(libs.koin.android)
            // Android-only platform libs (actuals land in androidMain/.../platform)
            implementation(libs.androidx.activity) // ComponentActivity + ActivityResult APIs (FilePicker, E-5)
            implementation(libs.androidx.work)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.googleid)
            // Firebase (requires androidApp/google-services.json — see the Firebase setup note)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
        }
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Navigation (Compose Multiplatform)
            implementation(libs.navigation.compose)

            // Dependency injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)

            // kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Supabase + Ktor
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.realtime)
            implementation(libs.supabase.storage)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)

            // Images
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.qrose) // QR code rendering for the invite sheet (pure Compose MP)

            // Local DB (KSP/room-compiler wired with the first @Entity — data layer)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)

            // Preferences (non-secret)
            implementation(libs.datastore.preferences.core)

            // Logging
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)

    // Room is a KSP processor; it must be added to EACH target's ksp configuration
    // separately (commonMain has no ksp config — the codegen runs per platform).
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
