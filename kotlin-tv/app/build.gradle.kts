import java.util.Properties
import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    kotlin("android")
}

val legacyAudio = providers.gradleProperty("legacyAudio").map { it.toBooleanStrict() }.getOrElse(false)
val nativeSigningSdk = providers.gradleProperty("nativeSigningSdkDir").orNull?.let { file(it) }
val nativeSigningEnabled = nativeSigningSdk != null
check(!(nativeSigningEnabled && legacyAudio)) { "Native signing requires the ARM64 platform build" }
val reviewedNativeSha256 = "c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3"
fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }
val verifyNativeSigningSdk = tasks.register("verifyNativeSigningSdk") {
    nativeSigningSdk?.let { sdk ->
        inputs.dir(sdk)
        doLast {
            val library = sdk.resolve("jniLibs/arm64-v8a/libmetasec_ml.so")
            val metadata = sdk.resolve("assets/native-signing/sdk-metadata.json")
            val manifest = JsonSlurper().parse(sdk.resolve("manifest.json")) as Map<*, *>
            check(sha256(library) == reviewedNativeSha256 && manifest["sdkSha256"] == reviewedNativeSha256) {
                "Native signing SDK SHA-256 mismatch"
            }
            check(sha256(metadata) == manifest["metadataSha256"]) { "Native signing metadata SHA-256 mismatch" }
            val config = JsonSlurper().parse(metadata) as List<*>
            check(config.size == 17 && config[0] == "8662" && config[12] == "5" && config[14] == "0" &&
                listOf(6, 7, 8, 9, 13).all { config[it] == "" } &&
                config[15] == emptyList<Any>() && config[16] == emptyList<Any>()) {
                "Native signing metadata contains unsupported collection mode, identity or extension slots"
            }
        }
    }
}
if (nativeSigningEnabled) layout.buildDirectory.set(file("build/native-signing"))
else if (!legacyAudio) layout.buildDirectory.set(file("build/platform"))

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
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        testInstrumentationRunner = "com.hongguotv.nativeapp.LivePlaybackInstrumentation"
        manifestPlaceholders["appLabel"] = "hongguoTV"
        minSdk = 21
        if (!legacyAudio) minSdk = 23
        buildConfigField("boolean", "LEGACY_AUDIO", legacyAudio.toString())
        buildConfigField("boolean", "NATIVE_SIGNING_SDK_AVAILABLE", nativeSigningEnabled.toString())
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 35
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.16.14"
    }
    // Publish explicit ARM installer choices plus a universal updater fallback.
    splits {
        abi {
            isEnable = !legacyAudio
            reset()
            if (nativeSigningEnabled) include("arm64-v8a") else include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = !nativeSigningEnabled
        }
    }
    buildFeatures { buildConfig = true }
    nativeSigningSdk?.let { sdk ->
        sourceSets.getByName("main") {
            jniLibs.srcDir(sdk.resolve("jniLibs"))
            assets.srcDir(sdk.resolve("assets"))
        }
        packaging.jniLibs.useLegacyPackaging = true
    }
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
            versionNameSuffix = "-debug.20261114"
            manifestPlaceholders["appLabel"] = "hongguoTV"
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

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyNativeSigningSdk) }

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
