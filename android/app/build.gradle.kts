plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.ecojournal.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    // Yemen-only rebuild proof: the DEBUG signing config MUST use the
    // ONE shared debug keystore (restored by CI from the
    // DEBUG_KEYSTORE_BASE64 secret to android/app/debug.keystore),
    // never the runner's ephemeral ~/.android/debug.keystore.
    // Standard Android debug credentials (android/androiddebugkey).
    // If the file is absent the build fails loudly here instead of
    // silently signing with a different key (G-CI-5(d)).
    signingConfigs {
        // AGP/Flutter already create a "debug" config (pointing at
        // ~/.android/debug.keystore) — reconfigure it in place
        // instead of creating a second one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "com.ecojournal.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // v6.2.0: FileProvider for the in-app update installer
    // (MainActivity hands the downloaded APK to the system installer).
    implementation("androidx.core:core:1.13.1")
}
