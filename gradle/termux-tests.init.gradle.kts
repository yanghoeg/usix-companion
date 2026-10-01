// Explicit Termux test profile. Default Linux CI still uses Conscrypt.
// Conscrypt 2.5.2 has no Linux/Android aarch64 OpenJDK JNI library.
// These existing bridge tests do not exercise TLS; BouncyCastle runs the same assertions.
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        systemProperty("robolectric.conscryptMode", "OFF")
    }
}
