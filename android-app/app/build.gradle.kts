import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Version -----------------------------------------------------------------------
// One version for everything: the repo-root VERSION file. The Chrome manifest must carry the
// same number; scripts/check-version.mjs fails CI if anything disagrees.
val appVersion: String = rootProject.file("../VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toInt() }
require(versionParts.size == 3 && versionParts.all { it in 0..99 }) { "VERSION must look like 2.2.0, got '$appVersion'" }
// 2.2.0 -> 20200. Always increases with the version, as Android requires for updates.
val appVersionCode = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]

// --- Signing -----------------------------------------------------------------------
// The keystore itself is NEVER committed to this repo, and its password never appears
// in this file. Values come from one of two places:
//   1. CI: environment variables CLIPBRIDGE_KEYSTORE_FILE / CLIPBRIDGE_KEYSTORE_PASSWORD /
//      CLIPBRIDGE_KEY_ALIAS (and optionally CLIPBRIDGE_KEY_PASSWORD), set by the GitHub
//      Actions workflow from repository secrets.
//   2. Local dev: copy android-app/keystore.properties.example to
//      android-app/keystore.properties (gitignored) and fill in your own values.
// Without a key, tests and debug builds still work (debug falls back to Android's local debug
// key, so Google sign-in won't work in that build), but any release build fails loudly instead
// of silently producing an unsigned or wrongly signed APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
}

fun signingValue(envName: String, propKey: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

val ksStoreFilePath = signingValue("CLIPBRIDGE_KEYSTORE_FILE", "storeFile")
val ksStorePassword = signingValue("CLIPBRIDGE_KEYSTORE_PASSWORD", "storePassword")
val ksKeyAlias = signingValue("CLIPBRIDGE_KEY_ALIAS", "keyAlias")
val ksKeyPassword = signingValue("CLIPBRIDGE_KEY_PASSWORD", "keyPassword") ?: ksStorePassword
val hasReleaseKey = ksStoreFilePath != null && ksStorePassword != null && ksKeyAlias != null
// -------------------------------------------------------------------------------------

android {
    namespace = "app.clipbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clipbridge"
        minSdk = 29          // Android 10+
        targetSdk = 35       // Android 15: edge-to-edge is enforced; every screen handles insets
        versionCode = appVersionCode
        versionName = appVersion
    }

    // One fixed key for every build, so the SHA-1 registered in Google Cloud never changes.
    signingConfigs {
        if (hasReleaseKey) {
            create("personal") {
                storeFile = rootProject.file(ksStoreFilePath!!) // relative paths are from android-app/
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
                enableV1Signing = false // minSdk 29: v2/v3 signatures only
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug { if (hasReleaseKey) signingConfig = signingConfigs.getByName("personal") }
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("personal")
        }
    }

    buildFeatures { buildConfig = true }

    // UI tests run on the JVM with Robolectric (no emulator needed) and save screenshots of the
    // real layouts to app/build/screenshots for review.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Fail a release build early and clearly when no signing key is configured.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && (it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") || it.name.startsWith("packageRelease")) }
    if (wantsRelease && !hasReleaseKey) throw GradleException(
        "No signing key configured. Set CLIPBRIDGE_KEYSTORE_FILE, CLIPBRIDGE_KEYSTORE_PASSWORD and " +
        "CLIPBRIDGE_KEY_ALIAS as environment variables (used in CI), or create " +
        "android-app/keystore.properties from keystore.properties.example for a local build."
    )
}

// Newest versions that build against compileSdk 35 (later ones need the Android 16 SDK).
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
