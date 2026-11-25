plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.sealbattery"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.sealbattery"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "2.0"
    }
    // ใช้กุญแจเซ็นชุดเดียวทุก build เพื่อให้ติดตั้งทับเวอร์ชันเก่าได้
    signingConfigs {
        getByName("debug") {
            storeFile = file("seal-debug.keystore")
            storePassword = "android"
            keyAlias = "sealdebug"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
