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

    signingConfigs {
        // A keystore in the repo, on purpose. A GitHub runner generates a fresh debug key every
        // run, so `adb install -r` between two builds of this same project is refused — which is
        // exactly the trap this tool fell into first. The password is public and the key is not a
        // trust anchor: it only has to be the same key every time.
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "sideload"
            keyAlias = "sideload"
            keyPassword = "sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
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
