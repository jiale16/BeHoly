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

    // 注：本增量已移除图像模型相关资产，故不再需要 aaptOptions 的 noCompress 配置。
}

dependencies {
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ★ 本增量已移除图像模型与 OCR 相关依赖 ★
    //   检测机制由旧版截屏 + OCR + 图像模型，
    //   改为 AccessibilityService 遍历视图树文本 + 本地敏感词库匹配，零新增依赖，保持离线铁律。

    // AndroidX 基础库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("androidx.activity:activity-ktx:1.8.0")

    // ===== 仪表化测试（instrumented）依赖：QA 为「悔改反思日志」测试新增 =====
    // 仅用于本地连接真机/模拟器执行 ./gradlew connectedAndroidTest，不进入 release 包。
    // 非业务代码改动，仅为让 RepentanceStoreTest 可编译运行。
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")

    // ===== 单元测试（unit test）依赖：QA 为 Robolectric/Mockito 单测新增 =====
    // 仅用于本地 ./gradlew test 执行，不进入 release 包。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
}
