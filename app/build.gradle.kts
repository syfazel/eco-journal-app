plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ecojournal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ecojournal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-phase2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
    implementation("androidx.compose.material:material-icons-core")
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

    // FCM scaffolding ONLY. No google-services.json is committed and no
    // Firebase key exists anywhere in this repo (see TODO in
    // fcm/EcoMessagingService.kt). The dependency alone needs no key;
    // enabling real push additionally requires the google-services
    // Gradle plugin + google-services.json supplied by Yasin.
    implementation("com.google.firebase:firebase-messaging:23.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
