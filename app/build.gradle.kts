plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation(project(":adsb"))
}

android {
    namespace = "com.formiguer.rtlsdr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.formiguer.rtlsdr"
        // Android 7.1.1 = API 25
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
