plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}
apply(from = rootProject.file("gradle/android-library.gradle"))
android { namespace = "dev.usix.companion.adapters.persistence" }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(project(":core:application"))
    implementation(libs.room.runtime)
    kapt(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
