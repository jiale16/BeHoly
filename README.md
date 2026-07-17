# BeHoly

> 本地离线 · 基督徒屏幕内容守望 App
>
> 在设备本地检测成人/色情相关**文字**，于内容出现时引导「悔改归向神」，并可在（可选的）设备所有者模式下强制关闭违规应用、锁屏或重启。

---

## 一、项目定位

BeHoly 是一款**仅供个人使用、完全离线**的安卓内容守望工具，面向希望克制手机上色情/成人内容的基督徒用户。设计哲学不是「惩罚」，而是「取代与挽回」——用每日金句与悔改反思，把跌倒的瞬间变成转向神的瞬间。

### 与同类产品的区别

| 对比对象 | 差异 |
|----------|------|
| Bible Lock / Bible Mode（"读经解锁手机"） | 那些产品用「读经/祷告才能解锁手机」做机制；BeHoly 做的是**主动检测成人内容并干预**，而非用读经换解锁。 |
| Covenant Eyes / Accountable2You（色情问责监控） | 那些产品截图/上报给「盟友」；BeHoly **不截图、不上传、不生成上报报告**，全部本地处理，并以信仰视角（悔改 + 每日经文）呈现。 |

---

## 二、核心特性

- **完全离线**：不声明任何网络权限，不联网，不上传任何数据（"离线铁律"）。
- **无障碍文本检测**：通过 `AccessibilityService` 遍历当前界面视图树文本，与本地敏感词库做子串匹配（不区分大小写）。**无需截屏、无需 OCR、无需图像模型**。
- **分级处置（Tier）**：
  - **Tier1（基础，无需特殊权限）**：踢回桌面 + 弹出全屏悔改提醒页。
  - **Tier2（需设备所有者）**：隐藏/封禁违规应用 + 锁屏 + 5 分钟「冷静期」循环重锁。
  - **Tier3（需设备所有者）**：封禁 + 锁屏 + 重启设备。
  - **同包累计命中自动升级**：10 分钟内累计 ≥3 次升 Tier2，≥5 次升 Tier3。
- **悔改流**：命中后弹出全屏悔改页（锁屏可见、拦截返回键），可进入结构化反思表单（心情 / 看的方法 / 反思 / 以后避免方案），保存到本地 JSONL。
- **每日金句**：前台常驻通知每天显示一条圣经经文（按日轮换）。
- **优雅降级**：未配置设备所有者时，Tier2/3 自动退化为 Tier1（仅提醒），并在日志标注「未配置设备所有者」。
- **隐蔽停止**：主界面连点标题 5 次（3 秒内）才显示「停止监控」。

---

## 三、技术栈

| 维度 | 选型 |
|------|------|
| 语言 / 构建 | Kotlin 1.9.22，AGP 8.2.2，Java 17，Gradle Kotlin DSL |
| 平台 | minSdk 29（Android 10），target / compileSdk 34 |
| 并发 | kotlinx-coroutines（结构化并发） |
| 检测 | Android `AccessibilityService`（视图树文本遍历）+ 本地敏感词库（`assets/sensitive_words.txt`） |
| 管控（可选） | `DevicePolicyManager`（设备所有者，adb 一次性激活） |
| 存储 | 本地文件（`repentance_records.jsonl`、命中日志），`org.json`，零额外依赖 |
| 测试 | Robolectric / Mockito（单元测试）+ AndroidX Instrumented（`connectedAndroidTest`） |

> 历史设计文档（`docs/system_design.md`）曾规划「截屏 + ML Kit OCR + TFLite open_nsfw 图像模型」方案；**当前实现已移除图像/模型相关依赖**，改由无障碍文本检测取代，以换取零依赖、低功耗与绝对离线。

---

## 四、架构总览

```
界面文本变化 (AccessibilityEvent)
        │
        ▼
BeHolyAccessibilityService  ──遍历视图树文本──▶ TextDetector + SensitiveWordDictionary
        │ 命中                                            │
        │                                                ▼
        │                                         TierClassifier (基础等级)
        │                                                │
        ▼                                                ▼
DisposalExecutor ──踢回桌面(HOME)──▶ MonitoringService (前台 specialUse 服务)
        │                                    │  ├─ 拉起 RepentanceActivity (Tier1 提醒)
        │                                    │  ├─ startDispose (Tier2/3)
        │                                    │  │    └─ DeviceOwnerHelper (hideApp / lockNow / reboot)
        │                                    │  └─ 冷静期循环重锁 (亮屏且回到被封应用即重锁)
        ▼
   (可选) Device Owner 模式：强制关闭 / 锁屏 / 重启
```

**关键组件职责**

- `BeHolyAccessibilityService`：事件驱动检测；按包名节流（800ms）、跳过自身/系统/白名单包；顶层捕获异常避免进程崩溃（无障碍与 UI 同进程）。
- `MonitoringService`：前台 `specialUse` 服务；常驻通知（每日金句）；合规后台拉起悔改页；执行分级处置与冷静期循环重锁。
- `DisposalExecutor`：处置路由（只决策不执行），非 DO 时降级为 Tier1。
- `DeviceOwnerHelper`：封装设备所有者专属 API，非 DO 时所有写操作降级为 no-op 并返回 false。
- `RepentanceActivity` / `RepentanceFormActivity`：悔改提醒与结构化反思表单。
- `DailyVerse` / `MonitorState` / `HitLogger` / `InAppLogger` / `RepentanceStore`：金句、状态机、命中日志、应用内日志、反思存储。

---

## 五、快速开始

### 构建

```bash
cd BeHoly
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

> 当前 `release` 签名复用 debug keystore（见 `app/build.gradle.kts` 注释）。正式分发前请替换为专属 release keystore。

### 安装与权限

1. 安装 APK 到 **Android 10+** 设备。
2. 打开 App → 点击「开启监控」。
3. **开启无障碍服务**：系统跳转无障碍设置，找到 BeHoly 并开启（这是检测能力的唯一来源）。
4. （可选）**配置设备所有者**以获得封禁 / 锁屏 / 重启能力：
   ```bash
   adb shell dpm set-device-owner com.example.beholy/.ui.BeHolyAdminReceiver
   ```
   > ⚠️ 一旦设为设备所有者，正常卸载前需先移除（通常需出厂重置或专用命令）。
5. 授予通知权限（Android 13+）。

### 使用

- 当屏幕上出现成人/色情相关文字，App 会踢回桌面并弹出悔改页。
- 可选填写反思表单，记录保存到本地。
- 连点主界面标题 5 次显示「停止监控」。

---

## 六、权限模型

| 权限 | 用途 | 必需 |
|------|------|------|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台常驻服务 | 是 |
| `POST_NOTIFICATIONS` | 常驻通知与悔改警示 | 是（Android 13+） |
| 无障碍服务 (`BIND_ACCESSIBILITY_SERVICE`) | 界面文本检测 | 是（核心检测） |
| `PACKAGE_USAGE_STATS` | 冷静期判断前台应用 | 建议（否则循环重锁降级） |
| `SYSTEM_ALERT_WINDOW` | 后台可靠拉起悔改页 | 建议 |
| 设备所有者（`adb dpm`） | 封禁 / 锁屏 / 重启 | 可选 |
| **任何网络权限** | — | **刻意不声明** |

---

## 七、检测与分级参数（`Constants.kt`）

| 常量 | 值 | 含义 |
|------|----|------|
| `COOLDOWN_PERIOD_MS` | `300_000` | 冷静期 5 分钟 |
| `TIER2_HIT_THRESHOLD` | `3` | 同包累计 ≥3 次升 Tier2 |
| `TIER3_HIT_THRESHOLD` | `5` | 同包累计 ≥5 次升 Tier3 |
| `TIER_WINDOW_MS` | `600_000` | 累计统计窗口 10 分钟 |
| `ACCESSIBILITY_SCAN_THROTTLE_MS` | `800` | 同包最小处理间隔（防事件风暴） |
| 高危词 / 包名黑名单 | 空集合 | 占位，待产品填充 |

匹配方式：**子串包含，不区分大小写**（见 `SensitiveWordDictionary` + `TextDetector`）。

---

## 八、目录结构

```
BeHoly/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/sensitive_words.txt
│       │   ├── java/com/example/beholy/
│       │   │   ├── BeHolyApp.kt
│       │   │   ├── data/      (Constants, DetectionResult, DailyVerse,
│       │   │   │               RepentanceRecord, SensitiveWordDictionary)
│       │   │   ├── detection/ (TierClassifier, text/TextDetector)
│       │   │   ├── service/   (MonitoringService, BeHolyAccessibilityService)
│       │   │   ├── ui/        (MainActivity, RepentanceActivity,
│       │   │   │               RepentanceFormActivity, BeHolyAdminReceiver,
│       │   │   │               PermissionHelper)
│       │   │   └── util/      (DeviceOwnerHelper, DisposalExecutor,
│       │   │                   HitLogger, InAppLogger, MonitorState,
│       │   │                   RepentanceStore)
│       │   └── res/           (layout, values, xml/*, drawable)
│       ├── test/              (单元测试：Robolectric / Mockito)
│       └── androidTest/       (仪表化测试：RepentanceStore / Accessibility 处置)
├── docs/                     (mermaid 类图/时序图 + 增量设计/PRD)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── local.properties
```

---

## 九、测试

```bash
./gradlew test                 # 单元测试
./gradlew connectedAndroidTest # 仪表化测试（需真机 / 模拟器）
```

---

## 十、隐私与边界

- **完全离线**：无任何网络调用；敏感词库与反思记录仅存于本机。
- **不截图、不识别图像**：当前仅基于界面**可见文字**检测，对纯图片 / 视频（无文字内容）不生效。
- 本工具面向**个人自主管教**，不是医疗、心理或临床产品，也不构成对任何行为的判定。

---

## 十一、已知限制 / 后续方向

- 检测仅覆盖界面文字，无法识别无文字的成人图片 / 视频（如需，可评估重新引入 TFLite 图像模型分支，见历史设计文档）。
- 设备所有者配置为开发者级操作（adb），对普通用户门槛高；卸载需先移除 DO。
- 分级黑名单与高危词当前为空，所有命中默认走累计升级逻辑。
- 缺少「同伴 / 小组问责」与「跨设备同步」——若做信仰问责社群，这是最大的差异化扩展方向。
- 依赖无障碍服务，部分厂商 ROM 需手动加白保活；Google Play 对"用无障碍做监控"类应用有政策限制，分发渠道需评估。

---

## 十二、关联文档

- `docs/system_design.md`（顶层设计，含旧版截屏 + 图像模型方案，部分已演进）
- `docs/incremental_design_accessibility.md` / `docs/incremental_prd_accessibility.md`
- `docs/incremental_design_repentance.md` / `docs/incremental_prd_repentance.md`
- `docs/class-diagram.mermaid` / `docs/sequence-diagram.mermaid`

---

> 免责声明：本软件仅供个人灵修自主管教使用。使用者需自行了解所在地区对无障碍服务、设备管理员及内容过滤的法律与平台政策要求。
