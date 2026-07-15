plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

android {
    namespace = "com.example.beholy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.beholy"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 临时签名配置：复用 debug keystore 给 release 用，便于直接安装验证。
    // 正式分发时请替换为专属 release keystore（见下方注释）
    signingConfigs {
        getByName("debug") {
            // AGP 默认 debug 签名无需显式配置，此处占位以备扩展
        }
        create("release") {
            // ★ 正式分发时取消下方注释并填入你的 release keystore 信息 ★
            // storeFile = file("keystores/release.jks")
            // storePassword = "your_store_password"
            // keyAlias = "your_key_alias"
            // keyPassword = "your_key_password"

            // 临时：复用 debug keystore（路径与 AGP 默认值一致）
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // 关键：assets 中的 .tflite 模型不能被压缩，否则 Interpreter 读取会失败
    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit 离线中文文字识别
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // TensorFlow Lite（图像 NSFW 分类）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // 注：tensorflow-lite-hexagon:0.4.4 不在 google()/mavenCentral() 仓库
    //     如需 Hexagon NN 加速，请从 https://www.tensorflow.org/lite/android/delegates/hexagon
    //     手动下载 .aar 并以 files("libs/tensorflow-lite-hexagon.aar") 方式引入

    // AndroidX 基础库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("androidx.activity:activity-ktx:1.8.0")
}
