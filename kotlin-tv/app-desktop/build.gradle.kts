import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

kotlin { jvmToolchain(17) }
dependencies {
    implementation("HongguoTVNative:core")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
    implementation("org.openani.mediamp:mediamp-mpv:0.5.1")
    val system = System.getProperty("os.name")
    val os = when {
        system.startsWith("Mac") -> "macos"
        system.startsWith("Windows") -> "windows"
        else -> "linux"
    }
    val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
    runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-$os-$arch:0.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit"))
}
compose.desktop {
    application {
        mainClass = "com.hongguotv.desktop.MainKt"
        jvmArgs += "-Djava.net.useSystemProxies=true"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HongguoDesktop"
            packageVersion = "1.0.0"
            modules("java.net.http", "java.prefs", "jdk.unsupported", "jdk.httpserver")
        }
    }
}
