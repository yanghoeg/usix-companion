plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = (project.findProperty("versionName") as? String) ?: "0.1.6"

android {
    namespace = "dev.usix.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.usix.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = appVersionName
    }

    // Retain the public development key for compatible personal sideload updates.
    // It provides no private publisher identity. Controlled release signing needs
    // a tested migration for existing installations.
    signingConfigs {
        getByName("debug") {
            storeFile = file("usix-debug.keystore")
            storePassword = "usixdebug"
            keyAlias = "usix"
            keyPassword = "usixdebug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Preserve existing APK upgrade compatibility until signing migration.
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
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
