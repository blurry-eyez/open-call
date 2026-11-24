import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Personal settings are NOT stored in the repo. Provide them (first match wins) via
//   -PBASE_URL=...  |  environment variable  |  local.properties (git-ignored)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(key: String, default: String): String =
    (project.findProperty(key) as String?) ?: System.getenv(key) ?: localProps.getProperty(key) ?: default

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.s2105.opencall"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.s2105.opencall"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // Your Open Call server, e.g. https://call.example.com
        buildConfigField("String", "BASE_URL", "\"" + setting("BASE_URL", "https://your-server.example.com") + "\"")
        // Web-application OAuth client ID (the same one the server uses). Android client is matched by package + SHA-1.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"" + setting("GOOGLE_WEB_CLIENT_ID", "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Optional release keystore (CI passes these as secrets). Without it the debug key is used.
    val ksPath = System.getenv("KEYSTORE_PATH")
    if (ksPath != null) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (ksPath != null) "release" else "debug")
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
