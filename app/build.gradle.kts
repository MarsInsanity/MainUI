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
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

// Version = number of commits, so a build made here and one made by GitHub Actions
// from the same commit get the same number, and every later commit a higher one
// (the in-app updater compares them). CI checks out the full history for this.
val commitCount: Int = runCatching {
    val p = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out.toInt() else null
}.getOrNull() ?: 1

// Repository the in-app updater reads releases from ("owner/name").
val updateRepo: String = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "MarsInsanity/MainUI"

// Release signing key: GitHub Actions writes the MAINUI_KEYSTORE secret to a file and
// passes its path in MAINUI_KEYSTORE. Without it, builds use this machine's debug key
// (fine for testing, but Android only updates an install with a build signed by the same key).
val releaseKeystore: String? = System.getenv("MAINUI_KEYSTORE")?.takeIf { File(it).isFile }

android {
    namespace = "io.github.marsinsanity.mainui"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.marsinsanity.mainui"
        minSdk = 29
        targetSdk = 34
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "SS_DEV_ID", "\"${secret("screenscraper.devid", "SS_DEV_ID")}\"")
        buildConfigField("String", "SS_DEV_PASSWORD", "\"${secret("screenscraper.devpassword", "SS_DEV_PASSWORD")}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) create("mainui") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("MAINUI_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
            keyAlias = System.getenv("MAINUI_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "androiddebugkey"
            keyPassword = System.getenv("MAINUI_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("mainui") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    // libmainuiinput.so: the virtual keyboard MainUI presses RetroArch's hotkeys with (tools/KeyInject).
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
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
        // Extracted to the native library folder, so the root helper can System.load() it by path.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // .rar archives (RAR4 and RAR5) for "Unzip all" (RomUnzip). Pure Java, UnRAR licence.
    implementation("com.github.junrar:junrar:8.1.2")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in unit tests; the real one for parsing tests.
    testImplementation("org.json:json:20240303")
    // An XmlPull parser (Android has its own) for the gamelist.xml / DAT tests.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
