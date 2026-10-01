plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
apply(from = rootProject.file("gradle/kotlin-jvm.gradle"))
dependencies {
    api(project(":core:application"))
    implementation(libs.coroutines.core)
}
