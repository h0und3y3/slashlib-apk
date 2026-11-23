plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.slashlib.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.slashlib.app"
        minSdk = 21          // Android 5.0+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Чистый Kotlin/Java без нативного кода: один APK работает и на armeabi-v7a (armv7), и на arm64-v8a (armv8).
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // чтобы APK можно было установить без своего ключа
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
