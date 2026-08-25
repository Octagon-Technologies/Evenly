import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Firebase — requires androidApp/google-services.json (see the Firebase setup note)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.crashlytics)
}

// Release upload-signing credentials, from local.properties (gitignored) or the matching env vars for
// CI. Absent credentials leave `release` unsigned rather than failing the build, so a contributor
// without the keystore can still run `assembleRelease` locally — but Play rejects an unsigned upload,
// so `bundleRelease` for the store must be run somewhere these resolve. See code/RELEASE_SIGNING.md.
// Must sit below `plugins {}`: the Kotlin DSL allows only imports above that block.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun secret(key: String, env: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStorePath = secret("release.storeFile", "EVENLY_RELEASE_STORE_FILE")
val releaseStoreFile = releaseStorePath?.let { path ->
    file(path).takeIf { it.exists() } ?: rootProject.file(path).takeIf { it.exists() }
}
val releaseStorePassword = secret("release.storePassword", "EVENLY_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("release.keyAlias", "EVENLY_RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("release.keyPassword", "EVENLY_RELEASE_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)

    // Branded launch screen. Backports the Android 12 splash API to minSdk 24, so the blue-and-feather
    // launch is identical on every supported version instead of only on 31+.
    implementation(libs.androidx.core.splashscreen)

    // Firebase Cloud Messaging — the host-registered messaging service forwards into the shared PushBus (F7).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "app.splitevenly"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.splitevenly"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "androidApp: no release signing credentials found - the release build will be " +
                        "UNSIGNED and Play will reject it. See code/RELEASE_SIGNING.md.",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}