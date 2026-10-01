plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
tasks.withType<Test>().configureEach {
    val conformance = layout.buildDirectory.dir("conformance")
    systemProperty("companion.conformance", conformance.get().asFile.absolutePath)
    outputs.dir(conformance)
}
apply(from = rootProject.file("gradle/kotlin-jvm.gradle"))
dependencies {
    implementation(project(":core:application"))
    implementation(project(":protocol"))
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(project(":testing:fixtures"))
}
