plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
apply(from = rootProject.file("gradle/kotlin-jvm.gradle"))
dependencies {
    api(project(":core:domain"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":testing:fixtures"))
}
