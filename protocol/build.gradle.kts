plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
apply(from = rootProject.file("gradle/kotlin-jvm.gradle"))
dependencies {
    implementation(libs.json)
    testImplementation(libs.junit)
}
