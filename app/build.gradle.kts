plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.aasim.bootstick"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.aasim.bootstick"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    // Release signing comes from the environment (GitHub secrets in .github/workflows/release.yml).
    // Without it, assembleRelease still builds, just unsigned.
    val keystore = System.getenv("BS_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("BS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BS_KEY_ALIAS")
            keyPassword = System.getenv("BS_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material:material-icons-core")
    // .xz decompression for packed images (Raspberry Pi OS, Armbian...); Android has gzip and zip but not xz.
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    // The real org.json, so tests can parse the catalog (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
}
