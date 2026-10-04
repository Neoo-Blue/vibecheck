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
        versionCode = 66
        versionName = "6.10.0"
    }

    // Releases are signed with one fixed key, so each version installs over the one before. The
    // key is in the repository encrypted (AES-256 under a long random password): without
    // RELEASE_KEY_PASSWORD, a GitHub secret or a local environment variable, it is useless, and
    // a build falls back to the debug key, which cannot install over a release.
    val releasePassword: String? = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releasePassword != null) create("release") {
            storeFile = file("signing/release.p12")
            storeType = "pkcs12"
            storePassword = releasePassword
            keyAlias = "vibecheck"
            keyPassword = releasePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
