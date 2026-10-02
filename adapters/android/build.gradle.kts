plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
apply(from = rootProject.file("gradle/android-library.gradle"))
android { namespace = "dev.usix.companion.adapters.android" }
dependencies {
    implementation(project(":core:application"))
    implementation(libs.coroutines.android)
    implementation(libs.mlkit.latin)
    implementation(libs.mlkit.korean)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":protocol"))
    testImplementation(project(":adapters:transport"))
    testImplementation(project(":testing:fixtures"))
}
