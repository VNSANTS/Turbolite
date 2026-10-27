plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vnsants.turbolite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vnsants.turbolite"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-beta"
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
    implementation("dev.rikka.shizuku:api:12.2.0")
    implementation("dev.rikka.shizuku:provider:12.2.0")
}
