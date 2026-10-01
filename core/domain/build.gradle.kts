plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
apply(from = rootProject.file("gradle/kotlin-jvm.gradle"))
dependencies {
    testImplementation(libs.junit)
}
