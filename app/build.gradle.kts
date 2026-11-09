import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional ScreenScraper developer credentials, read from local.properties or
// environment so they never have to be committed. Users can also enter them in
// Settings > Scraper at runtime.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").replace("\"", "\\\"")

android {
    namespace = "io.github.marsinsanity.mainui"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.marsinsanity.mainui"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SS_DEV_ID", "\"${secret("screenscraper.devid", "SS_DEV_ID")}\"")
        buildConfigField("String", "SS_DEV_PASSWORD", "\"${secret("screenscraper.devpassword", "SS_DEV_PASSWORD")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release build can be sideloaded
            // directly; replace with your own signing config for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
}
