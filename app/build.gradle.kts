import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionMajor = 0
val versionMinor = 5
val versionPatch = 12
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

// Legacy builds (the "0.3" release) already shipped versionCode 1_000_000
// (their internal versionName was 1.0.0), so 0.4 must code above it or
// Android refuses the install as a downgrade. (major + 1) keeps the scheme
// collision-free when major eventually bumps: 1.0.0 -> 2_000_000.
val computedVersionCode: Int =
    1_000_000 * (versionMajor + 1) + 1_000 * versionMinor + versionPatch + versionBuild

// =============================================================================
// Secrets & environment-specific config — see local.properties.sample for the
// full, documented list. Every key here resolves env var -> local.properties
// -> a placeholder that is safe to publish (the app just stays unconfigured/
// disabled for that feature until a real value is supplied). Real values for
// this checkout live ONLY in local.properties (gitignored, never committed);
// copy local.properties.sample to local.properties and fill it in there, or
// export the same-named env var for CI builds. Nothing below this comment
// should ever be a real credential — if you're adding a new one, follow the
// same pattern.
// =============================================================================
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: default

// ---- Cors.Connect service configuration -------------------------------------
// Base URL of the instance-creation service (a normal host, or a Yandex Cloud
// Function proxy — see CorsClient's doc for the URL-shape difference).
val corsBaseUrl: String = cfg("CORS_BASE_URL", "https://example.invalid/replace-with-your-endpoint").removeSuffix("/")
// Shared static secret the server expects in the X-App-Token header
// (WB_APP_TOKEN on the server). While left as the placeholder,
// CorsClient.isConfigured stays false and the instance-creation API is never
// called.
val corsAppToken: String = cfg("CORS_APP_TOKEN", "REPLACE_WITH_WB_APP_TOKEN")
// Telegram bot username the app opens to obtain initData for the claim flow.
val corsTgBot: String = cfg("CORS_TG_BOT", "REPLACE_WITH_TELEGRAM_BOT_USERNAME")
// ----------------------------------------------------------------------------

// ---- VK relay fallback (see cc.cors.connect.api.VkLinkFallback) ------------
// VK community access token (messages scope): used to call
// messages.getHistory when beta.cors-fox.cc AND the Yandex Function proxy are
// both unreachable. This token can also *send* messages as the bot if
// extracted from the APK, so it's a live credential, not a static shared
// secret — keep it out of source the same way as corsAppToken above. While
// left as the placeholder, the fallback is simply disabled (see
// VkLinkFallback.isConfigured).
val vkCommunityToken: String = cfg("VK_COMMUNITY_TOKEN", "")
// The relay conversation's peer id (the community admin's VK numeric id) —
// must match WB_VK_RELAY_PEER_ID on the server.
val vkRelayPeerId: String = cfg("VK_RELAY_PEER_ID", "")
val vkApiVersion: String = cfg("VK_API_VERSION", "5.199")
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
        buildConfigField("String", "VK_COMMUNITY_TOKEN", "\"$vkCommunityToken\"")
        buildConfigField("String", "VK_RELAY_PEER_ID", "\"$vkRelayPeerId\"")
        buildConfigField("String", "VK_API_VERSION", "\"$vkApiVersion\"")
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

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
