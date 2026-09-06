plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionMajor = 0
val versionMinor = 5
val versionPatch = 5
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

// Legacy builds (the "0.3" release) already shipped versionCode 1_000_000
// (their internal versionName was 1.0.0), so 0.4 must code above it or
// Android refuses the install as a downgrade. (major + 1) keeps the scheme
// collision-free when major eventually bumps: 1.0.0 -> 2_000_000.
val computedVersionCode: Int =
    1_000_000 * (versionMajor + 1) + 1_000 * versionMinor + versionPatch + versionBuild

// ---- Cors.Connect service configuration -------------------------------------
// NOTE: this public repo ships only placeholders. Supply real values via env
// vars (CI) or a gitignored local.properties. See SECURITY_CLEANUP.md.
//
// Helper: read from an env var, else from local.properties, else the placeholder.
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: default

// Base URL of the instance-creation service.
val corsBaseUrl: String = cfg("CORS_BASE_URL", "https://example.invalid/replace-with-your-endpoint").removeSuffix("/")
// Shared static secret the server expects in the X-App-Token header (WB_APP_TOKEN).
// While left as the placeholder, CorsClient.isConfigured stays false and the
// instance-creation API is not called.
val corsAppToken: String = cfg("CORS_APP_TOKEN", "REPLACE_WITH_WB_APP_TOKEN")
// Telegram bot username the app opens to obtain initData for the claim flow.
val corsTgBot: String = cfg("CORS_TG_BOT", "REPLACE_WITH_TELEGRAM_BOT_USERNAME")
// ----------------------------------------------------------------------------

android {
    namespace = "bypass.whitelist"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "cc.cors.connect"
        minSdk = 24
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CORS_BASE_URL", "\"$corsBaseUrl\"")
        buildConfigField("String", "CORS_APP_TOKEN", "\"$corsAppToken\"")
        buildConfigField("String", "CORS_TG_BOT", "\"$corsTgBot\"")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/libgojni.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The committed debug.keystore was removed for the public repo. The debug
    // build now uses the Android Gradle Plugin's auto-generated
    // ~/.android/debug.keystore (standard android/android credentials).
    //
    // Before shipping a real release, add your own signing config sourcing the
    // keystore path and passwords from env vars / local.properties — see
    // SECURITY_CLEANUP.md.

    buildTypes {
        debug {
            // default debug signing (auto-generated keystore)
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
