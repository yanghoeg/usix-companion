plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
apply(from = rootProject.file("gradle/android-library.gradle"))
android {
    namespace = "dev.usix.companion.feature.control"
    buildFeatures.compose = true
    composeOptions.kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
}
dependencies {
    implementation(project(":core:application"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":testing:fixtures"))
}
