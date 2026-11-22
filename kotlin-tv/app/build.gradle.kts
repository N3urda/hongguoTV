import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val legacyAudio = providers.gradleProperty("legacyAudio").map { it.toBooleanStrict() }.getOrElse(false)
val debugDevice = providers.gradleProperty("debugDevice").getOrElse("universal")
require(debugDevice in setOf("universal", "phone", "tv")) { "debugDevice must be universal, phone, or tv" }
val outputSuffix = listOfNotNull(
    "platform".takeUnless { legacyAudio },
    debugDevice.takeUnless { it == "universal" },
).joinToString("/")
if (outputSuffix.isNotEmpty()) layout.buildDirectory.set(file("build/$outputSuffix"))
val ndkRevision = "27.1.12297006"
val rustCore = rootProject.layout.projectDirectory.dir("../rust-core")
val rustJni = layout.buildDirectory.dir("rust-jni")

val signingProperties =
    Properties().apply {
        val config = rootProject.file("keystore.properties")
        if (config.exists()) config.inputStream().use { load(it) }
    }

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    ndkVersion = ndkRevision
    sourceSets.getByName("main").jniLibs.srcDir(rustJni)
    if (debugDevice == "phone") {
        sourceSets.getByName("debug").manifest.srcFile("src/phone/AndroidManifest.xml")
    }
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        testInstrumentationRunner = "com.hongguotv.nativeapp.LivePlaybackInstrumentation"
        manifestPlaceholders["appLabel"] = "hongguoTV"
        minSdk = 21
        if (!legacyAudio) minSdk = 23
        buildConfigField("boolean", "LEGACY_AUDIO", legacyAudio.toString())
        buildConfigField("boolean", "FORCE_TV_CONTROLS", "false")
        buildConfigField("boolean", "FORCE_PHONE_UI", "false")
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 33
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.16.12"
    }
    // Publish explicit ARM installer choices plus a universal updater fallback.
    splits {
        abi {
            isEnable = !legacyAudio
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // API 21/22 cases run in the compatibility build; the platform APK cannot install there.
            if (!legacyAudio) it.systemProperty("robolectric.enabledSdks", (23..36).joinToString(","))
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2",
            )
        }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-rust-debug"
            buildConfigField("boolean", "FORCE_TV_CONTROLS", (debugDevice == "tv").toString())
            buildConfigField("boolean", "FORCE_PHONE_UI", (debugDevice == "phone").toString())
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("delivery")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}

val buildRustPlayback by tasks.registering(Exec::class) {
    inputs.files(rustCore.file("Cargo.toml"), rustCore.file("Cargo.lock"), rustCore.file("build_android.py"))
    inputs.dir(rustCore.dir("src"))
    inputs.property("ndkVersion", ndkRevision)
    outputs.dir(rustJni)
    commandLine(
        "python3", rustCore.file("build_android.py").asFile.absolutePath,
        "--ndk", android.sdkDirectory.resolve("ndk/$ndkRevision").absolutePath,
        "--output", rustJni.get().asFile.absolutePath,
    )
}
tasks.named("preBuild") { dependsOn(buildRustPlayback) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // API 23 tests use legacy graphics, without the cross-platform native renderer.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    if (legacyAudio) implementation(project(":decoder-ffmpeg"))
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
