plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gametranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gametranslator"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    // Распознавание текста с экрана. Модели ВШИТЫ в приложение и работают без интернета.
    implementation("com.google.mlkit:text-recognition:16.0.0")           // английский, испанский (латиница)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")   // китайский
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")  // японский

    // Перевод прямо на телефоне. Языковые модели (около 30 МБ каждая) скачиваются один раз.
    implementation("com.google.mlkit:translate:17.0.3")
}
