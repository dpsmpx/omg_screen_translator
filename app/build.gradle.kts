plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки GitHub Actions: каждая новая сборка получает больший versionCode.
val ciBuildNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.example.gametranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gametranslator"
        minSdk = 30
        targetSdk = 34
        versionCode = ciBuildNumber ?: 1
        versionName = "1.0." + (ciBuildNumber ?: 0)

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Постоянный отладочный ключ из репозитория. Без него GitHub Actions каждый раз
            // создаёт новый ключ, и новую версию нельзя установить поверх старой.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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

    packaging {
        jniLibs {
            // Библиотеки ML Kit уже оптимизированы; не пытаемся их «стрипать».
            keepDebugSymbols += listOf("**/libmlkit_google_ocr_pipeline.so", "**/libtranslate_jni.so")
        }
    }
}

dependencies {
    // Распознавание текста с экрана. Модели ВШИТЫ в приложение и работают без интернета.
    implementation("com.google.mlkit:text-recognition:16.0.1")           // английский, испанский (латиница)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")   // китайский
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")  // японский

    // Перевод прямо на телефоне. Языковые модели (около 30 МБ каждая) скачиваются один раз.
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
