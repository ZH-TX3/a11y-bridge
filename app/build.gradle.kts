plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.zh_tx3.a11ybridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.zh_tx3.a11ybridge"
        // 30: takeScreenshot, stateDescription without guards, and the level this was tested on.
        minSdk = 30
        targetSdk = 35
        versionCode = System.getenv("BRIDGE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug key: the APK is for a phone you control, sideloaded over adb.
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
