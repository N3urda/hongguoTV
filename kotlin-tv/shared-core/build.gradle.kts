plugins { kotlin("multiplatform") }

kotlin {
    // All four initial clients consume JVM bytecode: Android (D8) and desktop JVM.
    // Keep business logic in commonMain so it cannot depend on JVM/platform APIs.
    jvm()
    jvmToolchain(17)
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
