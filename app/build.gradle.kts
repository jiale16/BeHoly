import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.beholy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.beholy"
        minSdk = 29
        targetSdk = 34
        versionCode = 10
        versionName = "2.7"

        // 构建日期（编译开始时生成，供「关于」页展示）。使用 resValue 而非 BuildConfig，
        // 因本项目 BuildConfig 在 AGP8 下引用不稳定。
        val buildTime = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date())
        resValue("string", "build_time", buildTime)

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
            if (!keystorePropsFile.exists()) {
                // fail-fast:正式分发必须用独立 release keystore,避免误用 debug keystore
                // 导致被同 debug 签名的任意包覆盖安装。
                // 本地调试请使用 debug 或 v21 构建类型:./gradlew assembleDebug
                throw GradleException(
                    "release 构建需要 keystore.properties 文件用于签名正式包,但该文件不存在。\n" +
                    "请在项目根目录创建 keystore.properties,内容示例:\n" +
                    "STORE_FILE=/path/to/your/release.keystore\n" +
                    "STORE_PASSWORD=your_store_password\n" +
                    "KEY_ALIAS=your_key_alias\n" +
                    "KEY_PASSWORD=your_key_password\n\n" +
                    "若仅用于本地调试构建,请改用 debug 构建类型:./gradlew assembleDebug"
                )
            }
            val keystoreProps = Properties().apply {
                keystorePropsFile.inputStream().use { load(it) }
            }
            storeFile = rootProject.file(keystoreProps["STORE_FILE"] as String)
            storePassword = keystoreProps["STORE_PASSWORD"] as String
            keyAlias = keystoreProps["KEY_ALIAS"] as String
            keyPassword = keystoreProps["KEY_PASSWORD"] as String
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
            // 启用 R8 代码压缩 + 资源压缩:
            // - 裁剪未使用代码与资源,缩小 APK 体积;
            // - 混淆代码,提高逆向门槛(敏感词库逻辑、处置策略不裸露);
            // - 保留行号便于崩溃日志定位(见 proguard-rules.pro)。
            isMinifyEnabled = true
            isShrinkResources = true
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
    // 每日统计页 ViewPager2（日/周/月 Tab 滑动）
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Room：命中记录结构化存储（替代 HitLogger 文本日志的"检测命中"部分）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

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
