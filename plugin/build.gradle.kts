plugins { id("com.android.application") }
android {
    namespace = "io.github.lynlioss.musicimport"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.lynlioss.musicimport"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs { create("release") {
        storeFile = file(System.getenv("PLUGIN_KEYSTORE_FILE") ?: "../release.jks")
        storePassword = System.getenv("PLUGIN_KEYSTORE_PASSWORD")
        keyAlias = "music-import"
        keyPassword = System.getenv("PLUGIN_KEYSTORE_PASSWORD")
    } }
    buildTypes { release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        if (System.getenv("PLUGIN_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += "**/*.so" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
