plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "com.promolandia.tvmagnet"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.promolandia.tvmagnet"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }
    signingConfigs {
        create("release") {
            providers.gradleProperty("TV_MAGNET_KEYSTORE").orNull?.let { storeFile = File(it) }
            storePassword = providers.gradleProperty("TV_MAGNET_STORE_PASSWORD").orNull
            keyAlias = providers.gradleProperty("TV_MAGNET_KEY_ALIAS").orNull
            keyPassword = providers.gradleProperty("TV_MAGNET_KEY_PASSWORD").orNull
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (project.hasProperty("TV_MAGNET_KEYSTORE")) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") } }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true; unitTests.isReturnDefaultValues = true
        // Opt-in end-to-end test (EndToEndTorrentTest): TVM_E2E_NATIVE_DIR points at a desktop libtorrent4j.so.
        unitTests.all { it.jvmArgs("-Djava.library.path=" + (System.getenv("TVM_E2E_NATIVE_DIR") ?: "")) }
    }
}
dependencies {
    val torrentVersion = "2.1.0-39"
    // Each artifact carries only its own ABI's libtorrent4j.so, so both can be combined.
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$torrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$torrentVersion")
    // Debug-only: lets the box emulator (x86_64) run the real native library. Never shipped in release.
    debugImplementation("org.libtorrent4j:libtorrent4j-android-x86_64:$torrentVersion")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.13.4")
    testImplementation("xpp3:xpp3:1.1.4c")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
