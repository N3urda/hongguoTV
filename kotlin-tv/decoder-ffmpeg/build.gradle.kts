plugins { id("com.android.library") }
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig { minSdk = 21; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").jniLibs.srcDir("build/native-libs")
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    compileOnly("org.checkerframework:checker-qual:3.49.3")
}

// Build from verified source rather than shipping an opaque precompiled AAR.
val buildNativeAudio by tasks.registering(Exec::class) {
    inputs.file("tools/build_native.py")
    inputs.file("src/main/jni/ffmpeg_jni.cc")
    outputs.dir(layout.buildDirectory.dir("native-libs"))
    commandLine("python3", "tools/build_native.py", "--ndk",
        android.sdkDirectory.resolve("ndk/27.1.12297006").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildNativeAudio) }
