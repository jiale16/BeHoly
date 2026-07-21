import java.util.Properties

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
        versionCode = 4
        versionName = "2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 正式签名配置：从 gitignored 的 keystore.properties 读取 release keystore。
    // 若 keystore.properties 缺失，则回退到 debug keystore（保证本地构建不中断）。
    signingConfigs {
        getByName("debug") {
            // AGP 默认 debug 签名无需显式配置，此处占位以备扩展
        }
        // v2.1 旧版签名：用于覆盖安装手机上已存在的 v2.1（同签名可原地升级、保留私有数据）。
        // 旧版 keystore 为 Android 默认 debug keystore：alias=androiddebugkey，密码=android。
        create("v21") {
            storeFile = rootProject.file("keystore/v21.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val keystoreProps = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(keystoreProps["STORE_FILE"] as String)
                storePassword = keystoreProps["STORE_PASSWORD"] as String
                keyAlias = keystoreProps["KEY_ALIAS"] as String
                keyPassword = keystoreProps["KEY_PASSWORD"] as String
            } else {
                // 回退：复用 debug keystore（仅用于本地无 keystore.properties 时的构建）
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // v2.1 过渡构建：可覆盖安装旧 v2.1（保留数据并导出备份），debuggable 便于 adb 兜底。
        create("v21") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("v21")
            isDebuggable = true
        }
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
