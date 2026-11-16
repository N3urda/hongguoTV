import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
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
