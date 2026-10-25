plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// v5 P1 build fingerprint: the git short hash at build time, so a
// phone screenshot/report can be tied to an exact build (gate G1
// for defect A1 — the missing fingerprint left A1 undiagnosable
// from screenshots). "unknown" when git is unavailable; the value
// is hex-only or "unknown", never free text, and is exposed only
// via BuildConfig.GIT_COMMIT on Diagnostics line 1.
val v5GitCommit: String = try {
    val raw = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    if (raw.matches(Regex("[0-9a-f]+"))) raw else "unknown"
} catch (_: Exception) {
    "unknown"
}

android {
    namespace = "com.ecojournal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ecojournal.app"
        minSdk = 26
        targetSdk = 34
        // v4.0.1: fundamental app-update-system fix (see
        // docs/app-update-system-fix-plan.md). Never reused, never
        // reversed; the version manifest (v4-I) must name exactly
        // this name/code at publish time.
        // v5 P1: versionName/versionCode UNCHANGED (P6 owns the
        // 5.0.0 / 6 bump). Only the fingerprint field is added.
        versionCode = 5
        versionName = "4.0.1"
        buildConfigField("String", "GIT_COMMIT", "\"$v5GitCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Shared debug signing (fix for the 2026-10-25 install failure):
    // when app/debug.keystore exists (restored by CI from a GitHub
    // secret, never committed), debug builds sign with it, so every
    // cloud APK shares one signature and updates over the previous
    // one. Without the file, AGP falls back to its generated debug
    // key — the pre-fix behaviour.
    signingConfigs {
        create("sharedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            if (file("debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        // v5 P1: generates com.ecojournal.app.BuildConfig (for the
        // GIT_COMMIT fingerprint field above). No other use.
        buildConfig = true
    }
    // NOTE: no composeOptions block — with Kotlin 2.0 the Compose
    // compiler version is driven by the kotlin.plugin.compose plugin
    // above (2.0.21), not by kotlinCompilerExtensionVersion.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // HorizontalPager / ScrollableTabRow front page. Pager lives in
    // foundation; version comes from the same 2024.09.00 BOM, no new pin.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Real bottom-nav / reader icons (M3, P1-12): version from the BOM.
    // Core alone does NOT provide Bookmark/BookmarkBorder/ShowChart in
    // this BOM (cloud build 40213937473 proved it); extended does.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    // XML window theme parent Theme.Material3.DayNight.NoActionBar
    // (themes.xml) comes from the Material XML library, NOT from
    // androidx.compose.material3 — without this, AAPT linking fails.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Offline-first cache.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Settings (font scale, theme, notifications, invite state).
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Background sync (15-minute periodic work, Android minimum).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // WebP image loading with disk cache.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // FCM scaffolding ONLY, dormant by decision (M5,
    // docs/vnext-decisions.md §3). No google-services.json is
    // committed and no Firebase key exists anywhere in this repo.
    // The dependency alone needs no key; enabling real push later
    // (a new decision) would additionally require the
    // google-services Gradle plugin + google-services.json.
    implementation("com.google.firebase:firebase-messaging:23.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
