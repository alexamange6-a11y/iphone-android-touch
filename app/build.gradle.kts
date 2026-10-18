plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.iphonebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.iphonebridge"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.3-android-touch"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
