plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

val appVersionName = (project.findProperty("versionName") as? String) ?: "0.3.0"

android {
    namespace = "dev.usix.companion"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "dev.usix.companion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 8
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

    buildFeatures.compose = true
    composeOptions.kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":core:domain"))
    implementation(project(":core:application"))
    implementation(project(":adapters:android"))
    implementation(project(":adapters:persistence"))
    implementation(project(":adapters:transport"))
    implementation(project(":feature:control"))
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
