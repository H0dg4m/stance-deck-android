plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Live interop tests need a built `stance` binary; see LiveInteropTest.
    systemProperty("stance.binary", System.getProperty("stance.binary") ?: System.getenv("STANCE_BIN") ?: "")
    systemProperty("stance.vectors", rootDir.resolve("../tests/fixtures/deck-vectors.json").absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
