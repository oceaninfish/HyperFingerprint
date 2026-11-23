plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "io.fingerprintfiveseconds.hyperos"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.fingerprintfiveseconds.hyperos"
        minSdk = 36
        targetSdk = 37
        versionCode = 10
        versionName = "1.7.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
