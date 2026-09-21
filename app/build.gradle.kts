plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vibecheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vibecheck"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "6.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // sideloaded personal build
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // On-device Chinese OCR via Play Services: recognition runs on the phone, nothing is
    // uploaded. The model itself is delivered by Play Services so the APK stays small.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
