plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.iqbal.tiktokauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.iqbal.tiktokauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        val updateUrl = (
            providers.gradleProperty("updateUrl").orNull
                ?: System.getenv("AUTOSCROLL_UPDATE_URL")
                ?: "https://github.com/iqbalhikam/AutoScroll/releases/latest/download/update.json"
            )
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        val envKeystore = System.getenv("AUTOSCROLL_KEYSTORE")
        if (!envKeystore.isNullOrBlank()) {
            create("sideload") {
                storeFile = file(envKeystore)
                storePassword = System.getenv("AUTOSCROLL_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("AUTOSCROLL_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("AUTOSCROLL_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("AUTOSCROLL_KEYSTORE").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("sideload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
