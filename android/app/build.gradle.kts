plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dropfly.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dropfly.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // fixed signing key so future updates install over the old app (no uninstall needed after this one)
    signingConfigs {
        create("fixed") {
            storeFile = file("dropfly.keystore")
            storePassword = "dropfly"
            keyAlias = "dropfly"
            keyPassword = "dropfly"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
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
