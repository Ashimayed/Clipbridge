import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Signing -----------------------------------------------------------------------
// The keystore itself is NEVER committed to this repo, and its password never appears
// in this file. Values come from one of two places:
//   1. CI: environment variables CLIPBRIDGE_KEYSTORE_FILE / CLIPBRIDGE_KEYSTORE_PASSWORD /
//      CLIPBRIDGE_KEY_ALIAS, set by the GitHub Actions workflow from repository secrets.
//   2. Local dev: copy android-app/keystore.properties.example to
//      android-app/keystore.properties (gitignored) and fill in your own values.
// If neither is present, the build fails loudly instead of silently using no signing
// config or a hardcoded fallback.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
}

fun signingValue(envName: String, propKey: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propKey)

val ksStoreFilePath = signingValue("CLIPBRIDGE_KEYSTORE_FILE", "storeFile")
val ksStorePassword = signingValue("CLIPBRIDGE_KEYSTORE_PASSWORD", "storePassword")
val ksKeyAlias = signingValue("CLIPBRIDGE_KEY_ALIAS", "keyAlias")
val ksKeyPassword = signingValue("CLIPBRIDGE_KEYSTORE_PASSWORD", "keyPassword")
// -------------------------------------------------------------------------------------

android {
    namespace = "app.clipbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.clipbridge"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 4
        versionName = "2.1.1"
    }

    // One fixed key for every build, so the SHA-1 registered in Google Cloud never changes.
    signingConfigs {
        create("personal") {
            if (ksStoreFilePath != null && ksStorePassword != null && ksKeyAlias != null) {
                storeFile = file(ksStoreFilePath)
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            } else {
                throw GradleException(
                    "No signing key configured. Set CLIPBRIDGE_KEYSTORE_FILE, " +
                    "CLIPBRIDGE_KEYSTORE_PASSWORD and CLIPBRIDGE_KEY_ALIAS as environment " +
                    "variables (used in CI), or create android-app/keystore.properties from " +
                    "keystore.properties.example for a local build."
                )
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
