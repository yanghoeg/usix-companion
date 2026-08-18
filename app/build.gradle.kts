plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.usix.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.usix.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
    }

    // 저장소에 커밋한 고정 debug 키로 서명한다. CI 가 매 빌드 debug 키를 새로 만들어 서명이 바뀌면
    // 기기에서 덮어쓰기 업데이트가 거부되는데(매번 삭제 후 재설치), 이렇게 고정하면 그 문제가 사라진다.
    // 개인용 사이드로드 앱의 debug 서명이라 비밀번호가 노출돼도 무방하다(배포/Play 용 키 아님).
    signingConfigs {
        getByName("debug") {
            storeFile = file("usix-debug.keystore")
            storePassword = "usixdebug"
            keyAlias = "usix"
            keyPassword = "usixdebug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}
