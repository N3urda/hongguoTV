plugins { kotlin("jvm"); `java-library`; application }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":shared-core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.hongguotv.core.ProbeKt") }

// Existing Android/local verification commands must also exercise shared business rules.
tasks.test { dependsOn(":shared-core:jvmTest") }
