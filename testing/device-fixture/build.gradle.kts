plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "dev.usix.companion.fixture"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig {
        applicationId = "dev.usix.companion.fixture"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "P3.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions.jvmTarget = "17"
    buildFeatures.compose = true
    composeOptions.kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    signingConfigs { getByName("debug") {
        storeFile = rootProject.file("app/usix-debug.keystore"); storePassword = "usixdebug"; keyAlias = "usix"; keyPassword = "usixdebug"
    } }
}
dependencies {
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
