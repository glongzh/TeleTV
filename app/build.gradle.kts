import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Load Telegram credentials from local.properties (or environment as fallback).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramApiId: String =
    localProps.getProperty("TELEGRAM_API_ID") ?: System.getenv("TELEGRAM_API_ID") ?: "0"
val telegramApiHash: String =
    localProps.getProperty("TELEGRAM_API_HASH") ?: System.getenv("TELEGRAM_API_HASH") ?: ""

// PostHog analytics. Optional: an empty key leaves the SDK uninitialized and every
// Analytics call a no-op, so a checkout without credentials still builds and runs.
val posthogApiKey: String =
    localProps.getProperty("POSTHOG_API_KEY") ?: System.getenv("POSTHOG_API_KEY") ?: ""
val posthogHost: String =
    localProps.getProperty("POSTHOG_HOST") ?: System.getenv("POSTHOG_HOST")
        ?: "https://us.i.posthog.com"

android {
    namespace = "com.teletv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teletv"
        minSdk = 21          // Android TV baseline
        targetSdk = 34
        versionCode = 10
        versionName = "1.0.0"

        // Exposed as BuildConfig.TELEGRAM_API_ID / TELEGRAM_API_HASH.
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        // Exposed as BuildConfig.POSTHOG_API_KEY / POSTHOG_HOST.
        buildConfigField("String", "POSTHOG_API_KEY", "\"$posthogApiKey\"")
        buildConfigField("String", "POSTHOG_HOST", "\"$posthogHost\"")

        // Match the ABIs of the TDLib .so files in src/main/jniLibs.
        // arm64-v8a / armeabi-v7a = real TV boxes (new / old); x86_64 / x86 = emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }

    signingConfigs {
        // Personal release key. Keystore + passwords live outside VCS
        // (keystore/ and local.properties are both git-ignored).
        val ksFile = localProps.getProperty("KEYSTORE_FILE")
        if (ksFile != null && rootProject.file(ksFile).exists()) {
            create("release") {
                storeFile = rootProject.file(ksFile)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    // Compose for TV
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ExoPlayer / Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Local tag index (media filtering)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // Product analytics (see com.teletv.analytics.Analytics)
    implementation("com.posthog:posthog-android:3.19.0")

    // Required by the vendored TDLib bindings (TdApi.java / Client.java use
    // androidx.annotation.Nullable etc.). The bindings live in
    // app/src/main/java/org/drinkless/tdlib/ and their native libs (libtdjni +
    // libcryptox + libsslx) in app/src/main/jniLibs/<abi>/, both from the
    // Telegram X prebuilt bundle (github.com/TGX-Android/tdlib).
    implementation("androidx.annotation:annotation:1.8.2")

    testImplementation("junit:junit:4.13.2")
}
