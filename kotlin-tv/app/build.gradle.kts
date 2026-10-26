import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

// Build identity: shown in the app UI and embedded in the APK file name, so any two builds of the
// phone / TV flavors can be told apart at a glance (e.g. HG-Phone版_v0.9.0_20261017-0035-debug.apk).
// Evaluated at configuration time; the configuration cache is off (see gradle.properties), so this
// refreshes on every build.
val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
val buildStamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        // Lowered from 26 (Android 8.0) to 21 (Android 5.0 Lollipop).
        // NOTE: Media3 must stay <= 1.8.1 (media3-exoplayer/media3-ui 1.9.0+ require API 23).
        minSdk = 21
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 9
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.9.0"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }
    // Two independently-installable build variants that coexist long-term:
    //   phone -> com.hongguotv.phone "HG-Phone版" (portrait + per-video auto-rotation)
    //   tv    -> com.hongguotv.tv    "HG-TV版"    (forced landscape)
    // Each gets its own applicationId, launcher label, launcher icon source set and in-app label.
    flavorDimensions("device")
    productFlavors {
        create("phone") {
            val label = "HG-Phone版"
            dimension = "device"
            applicationId = "com.hongguotv.phone"
            manifestPlaceholders["appLabel"] = label
            buildConfigField("String", "APP_LABEL", "\"$label\"")
            buildConfigField("boolean", "IS_TV", "false")
        }
        create("tv") {
            val label = "HG-TV版"
            dimension = "device"
            applicationId = "com.hongguotv.tv"
            manifestPlaceholders["appLabel"] = label
            buildConfigField("String", "APP_LABEL", "\"$label\"")
            buildConfigField("boolean", "IS_TV", "true")
        }
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
    // Name each APK so the variant and build are identifiable at a glance:
    //   HG-Phone版_v0.9.0_20261017-0035-debug.apk  /  HG-TV版_v0.9.0_20261017-0035-debug.apk
    applicationVariants.all {
        val variant = this
        val label = if(variant.flavorName.equals("tv", ignoreCase = true)) "HG-TV版" else "HG-Phone版"
        variant.outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "${label}_v${variant.versionName}_${buildStamp}-${variant.buildType.name}.apk"
        }
    }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
