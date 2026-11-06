// Plain Kotlin/JVM code shared by the app and checked by fast JVM unit tests: encryption,
// QR pairing, sync pacing, pruning, log redaction and naming rules. Nothing here touches
// Android, so `./gradlew :core:test` runs anywhere, without an Android SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Same bytecode level as the app, without requiring a JDK 17 install (any JDK 17+ builds it).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Android ships org.json in the platform, so the app must not bundle its own copy.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The same vectors the Chrome extension is tested against (tests/parity.test.mjs).
    systemProperty("vectors", rootProject.projectDir.resolve("../protocol/test-vectors.json").absolutePath)
    inputs.file(rootProject.projectDir.resolve("../protocol/test-vectors.json"))
}
