# BeHoly

> 本地离线 · 基督徒屏幕内容守望 App
>
> 在设备本地检测成人/色情相关**文字**，于内容出现时引导「悔改归向神」，并可在（可选的）设备所有者模式下强制关闭违规应用、锁屏或重启。

当前版本：**v2.6**（versionCode 9）

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
- **无障碍文本检测**：通过 `AccessibilityService` 遍历当前界面视图树文本，与本地敏感词库做匹配。**无需截屏、无需 OCR、无需图像模型**。
- **Aho-Corasick 自动机匹配**：使用 AC 自动机做多模式串匹配，构建 O(总词长)、扫描 O(文本长 + 命中数)，替代 O(词数×文本长) 暴力扫描，对长文本与中等规模词库显著降耗。大小写不敏感、子串匹配。
- **安全短语豁免**：`色情低俗` 等分类标签短语整体出现时豁免（如举报页面），避免子串误报；该词若出现在短语之外（如「色情网站」）仍正常命中。
- **分级处置（Tier）**：
  - **Tier1（基础，无需特殊权限）**：弹出全屏悔改提醒页 + 持续打断（阻断期内违规包回前台立刻 HOME）。
  - **Tier2（需设备所有者）**：隐藏/封禁违规应用 + 锁屏 + 5 分钟「冷静期」循环重锁。
  - **Tier3（需设备所有者）**：封禁 + 锁屏 + 重启设备。
  - **同包累计命中自动升级**：10 分钟内累计 ≥3 次升 Tier2，≥5 次升 Tier3。
- **非 DO 下的递进打断**：未配置设备所有者时，按同包**今日累计**命中次数驱动阻断期与悔改页强度递进（基于 Room 数据库查询，跨进程重启不丢失）：
  - 第 1-2 次：阻断 30 秒，悔改页可立即关闭。
  - 第 3-4 次：阻断 2 分钟，悔改页最小停留 30 秒（按钮置灰倒计时）。
  - 第 5+ 次：阻断 5 分钟，悔改页最小停留 60 秒。
  - 阻断期内 `BeHolyAccessibilityService` 检测到违规包回前台立刻 `GLOBAL_ACTION_HOME`，这是非 DO 下唯一有效的打断手段。
- **悔改流闭环**：命中 → 全屏悔改页（锁屏可见、拦截返回键、强度递进文案）→ 结构化反思表单（心情 / 看的方法 / 反思 / 以后避免方案）→ 恩典闭环页（显示得胜天数与安慰经文），反思记录保存到本地 JSONL。
- **每日金句**：前台常驻通知每天显示一条圣经经文（按日轮换），点击跳转主界面。
- **得胜天数（连胜）**：无障碍服务开启即记为受守护的一天，连续守护天数递增；服务关闭则连胜清零。
- **检测命中统计**：按天/周/月查看命中次数柱状图与命中列表（数据源为 Room 数据库 `hit_records` 表）。
- **回转记录查看**：整页卡片列表展示历史反思记录，含概览卡（回转次数 / 连胜天数 / 距上次回转）与详情弹窗。
- **悔改记录导出/导入**：通过系统文件选择器（SAF）导出 JSONL 到用户自选位置，跨签名重装或换机后可导入恢复。
- **无障碍关闭劝诫**：用户已开启服务却打开系统无障碍设置页（意图关闭）时弹出劝诫守卫，给一个转向神的停顿。
- **优雅降级**：未配置设备所有者时，Tier2/3 自动退化为 Tier1（仅提醒 + 持续打断），并在日志标注「未配置设备所有者」。
- **隐蔽入口**：Device Owner 配置入口收进右上角三个点菜单（多数用户无法设置）；日志默认折叠，需主动开启。

---

## 三、技术栈

| 维度 | 选型 |
|------|------|
| 语言 / 构建 | Kotlin 1.9.22，AGP 8.2.2，Java 17，Gradle Kotlin DSL |
| 平台 | minSdk 29（Android 10），target / compileSdk 34 |
| 并发 | kotlinx-coroutines（结构化并发） |
| 检测 | Android `AccessibilityService`（视图树文本遍历）+ Aho-Corasick 自动机 + 本地敏感词库（`assets/sensitive_words.txt`） |
| 管控（可选） | `DevicePolicyManager`（设备所有者，adb 一次性激活） |
| 结构化存储 | Room（`hit_records` 表，存储命中记录） |
| 文件存储 | `repentance_records.jsonl`（悔改反思记录）、`detection_log.txt`（操作日志）、`org.json` |
| UI 组件 | ViewPager2（统计页日/周/月 Tab 滑动）、ViewBinding |
| 测试 | Robolectric / Mockito（单元测试）+ AndroidX Instrumented（`connectedAndroidTest`） |
| 构建 | R8 代码压缩 + 资源压缩（release），三种构建类型：debug / v21 / release |

> 历史设计文档曾规划「截屏 + ML Kit OCR + TFLite open_nsfw 图像模型」方案；**当前实现已移除图像/模型相关依赖**，改由无障碍文本检测取代，以换取低功耗与绝对离线。

---

## 四、架构总览

```
界面文本变化 (AccessibilityEvent)
        │
        ▼
BeHolyAccessibilityService
  ├─ 主线程:包名过滤(自身/系统/白名单/强制检测) + 节流 + 阻断期守卫
  ├─ IO 线程:迭代遍历视图树 → 聚合文本 → TextDetector + SensitiveWordDictionary(AC 自动机)
  │           → TierClassifier(基础等级) → MonitorState.classifyTier(累计升级)
  │           → 命中记录写入 Room(hit_records)
  │           → DisposalExecutor.execute
  │
  ├─ 阻断期守卫:命中后 setBlock,阻断期内违规包回前台立刻 HOME
  └─ 设置页守卫:检测到用户打开无障碍设置页 → AccessibilityGuardActivity 劝诫

DisposalExecutor (只决策与路由)
  ├─ 非 DO:直接 startActivity(RepentanceActivity) + setBlock(按 hitCount 递进)
  │         (不发前置 HOME,避免系统限流导致悔改页延迟显示)
  └─ DO:performGlobalAction(HOME) → MonitoringService.startDispose
                                         ├─ DeviceOwnerHelper.hideApp / lockNow / reboot
                                         ├─ 冷静期循环重锁(screenStateReceiver)
                                         └─ showRepentance

MonitoringService (前台 specialUse 服务)
  ├─ 常驻通知:金句 / 中性守护 / 警示(按 pendingRepentanceAction 切换)
  ├─ 合规拉起悔改页:startActivity + FullScreenIntent 通知兜底
  ├─ 通知抑制:命中时切换前台通知为警示,悔改结束后恢复金句/中性(非 DO 路径由 suppressNotification 显式触发)
  └─ 通知点击:FLAG_UPDATE_CURRENT 保证 extras 刷新,noHistory 已移除保证可靠拉起

悔改流:RepentanceActivity → RepentanceFormActivity → GraceActivity
       (强度递进)            (结构化反思)            (恩典闭环 + 得胜天数)
```

**关键组件职责**

- `BeHolyAccessibilityService`：事件驱动检测；按包名节流（800ms）、跳过自身/系统/白名单包；阻断期持续 HOME；设置页关闭劝诫守卫；顶层捕获异常避免进程崩溃。重活（视图树遍历 + 词库匹配）post 到 `Dispatchers.IO`。
- `MonitoringService`：前台 `specialUse` 服务；常驻通知（金句/中性/警示三种）；合规后台拉起悔改页；执行 DO 下分级处置与冷静期循环重锁。
- `DisposalExecutor`：处置路由（只决策不执行），非 DO 时直接 `startActivity` 悔改页 + `setBlock`；DO 时 HOME 后交 MonitoringService 落地系统管控。
- `DeviceOwnerHelper`：封装设备所有者专属 API，非 DO 时所有写操作降级为 no-op 并返回 false。
- `AhoCorasick` + `SensitiveWordDictionary`：AC 自动机多模式串匹配，构建后只读可并发扫描，支持安全短语豁免。
- `RepentanceActivity` / `RepentanceFormActivity` / `GraceActivity`：悔改提醒（强度递进 + 最小停留倒计时）→ 结构化反思表单 → 恩典闭环（得胜天数）。
- `DetectionStatsActivity`：按天/周/月命中统计，DB 层 `strftime` 按天聚合，内存二次聚合按周/月。
- `RepentanceListActivity`：回转记录卡片列表，零额外依赖（无 RecyclerView/CardView，ScrollView + 动态 inflate）。
- `StreakStore`：连续守护天数，无障碍开启即记一天，关闭则清零。
- `AppDatabase` / `HitDao` / `HitRecordEntity`：Room 数据库，结构化存储命中记录（毫秒时间戳、包名、等级、命中词、累计次数）。
- `JsonlToDbMigrator`：一次性迁移器，将旧版 `detection_log.txt` 中的命中记录导入 Room，幂等（SharedPreferences 标记 + DB 计数兜底）。
- `InAppLogger`：应用内日志器（内存缓冲 + 崩溃日志持久化到 `cacheDir`），UI 实时显示。
- `HitLogger`：操作日志（启动/停止/无障碍开启/关闭/处置完成）到 `detection_log.txt`，仅"检测命中"一类已迁移到 Room。

---

## 五、快速开始

### 构建

```bash
cd BeHoly
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
./gradlew assembleV21          # 覆盖安装旧 v2.1（保留数据,debuggable）
./gradlew assembleRelease      # 正式包(需 keystore.properties,启用 R8 压缩)
```

> `release` 构建需要项目根目录 `keystore.properties` 文件（gitignored），缺失时 **fail-fast** 报错，不会回退到 debug keystore。本地调试请用 `assembleDebug` 或 `assembleV21`。

### 安装与权限

1. 安装 APK 到 **Android 10+** 设备。
2. 打开 App → 点击「显示每日金句」。
3. **开启无障碍服务**：系统跳转无障碍设置，找到 BeHoly 并开启（这是检测能力的唯一来源）。
4. **授予悬浮窗权限**（`SYSTEM_ALERT_WINDOW`）：检测到不良内容时悔改页需从后台弹出，Android 10+ 需显式授予。App 会在**无障碍服务开启后**自动引导（与检测闭环配套），也可从右上角菜单「授予悬浮窗权限」手动重新授权。
5. （可选）**配置设备所有者**以获得封禁 / 锁屏 / 重启能力：
   ```bash
   adb shell dpm set-device-owner com.example.beholy/.ui.BeHolyAdminReceiver
   ```
   > ⚠️ 一旦设为设备所有者，正常卸载前需先移除（通常需出厂重置或专用命令）。
6. 授予通知权限（Android 13+）。
7. （建议）授予「使用情况访问权限」（`PACKAGE_USAGE_STATS`）：DO 下冷静期循环重锁时判断前台应用所需，未授予则循环重锁降级。

### 使用

- 当屏幕上出现成人/色情相关文字，App 会弹出悔改页（非 DO 下阻断期内违规包回前台将持续 HOME）。
- 可填写反思表单，保存后进入恩典闭环页，记录保存到本地。
- 主界面显示连续得胜天数；可进入「检测统计」查看命中图表，或「回转记录」查看历史反思。
- 右上角菜单可导出悔改记录（SAF 文件选择器），换机/重装后可导入恢复。

---

## 六、权限模型

| 权限 | 用途 | 必需 |
|------|------|------|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台常驻服务 | 是 |
| `POST_NOTIFICATIONS` | 常驻通知与悔改警示 | 是（Android 13+） |
| 无障碍服务 (`BIND_ACCESSIBILITY_SERVICE`) | 界面文本检测 | 是（核心检测） |
| `SYSTEM_ALERT_WINDOW` | 后台可靠拉起悔改页 | 是（Android 10+ 需显式授予） |
| `PACKAGE_USAGE_STATS` | 冷静期判断前台应用 | 建议（否则循环重锁降级） |
| 设备所有者（`adb dpm`） | 封禁 / 锁屏 / 重启 | 可选 |
| **任何网络权限** | — | **刻意不声明** |

---

## 七、检测与分级参数（`Constants.kt`）

| 常量 | 值 | 含义 |
|------|----|------|
| `COOLDOWN_PERIOD_MS` | `300_000` | DO 下冷静期 5 分钟 |
| `TIER2_HIT_THRESHOLD` | `3` | 同包累计 ≥3 次升 Tier2 |
| `TIER3_HIT_THRESHOLD` | `5` | 同包累计 ≥5 次升 Tier3 |
| `TIER_WINDOW_MS` | `600_000` | 累计命中统计窗口 10 分钟 |
| `ACCESSIBILITY_SCAN_THROTTLE_MS` | `800` | 同包最小处理间隔（防事件风暴） |
| `BLOCK_DURATION_BY_HIT_COUNT` | `30s / 2min / 5min` | 非 DO 阻断期阶梯（按命中次数 1-2 / 3-4 / 5+） |
| `MIN_STAY_BY_HIT_COUNT` | `10s / 30s / 60s` | 悔改页最小停留阶梯（按命中次数 1-2 / 3-4 / 5+） |
| `A11Y_GUARD_COOLDOWN_MS` | `30_000` | 无障碍关闭劝诫冷却 |
| `SAFE_PHRASES` | `{"色情低俗"}` | 安全短语豁免（整体出现时豁免子串命中） |
| `SKIP_PACKAGE_CONTAINS` | `{"bible"}` | 包名含此子串跳过（读经 App） |
| `FORCE_DETECT_PACKAGE_NAMES` | `{com.android.browser, com.android.chrome}` | 强制检测（浏览器不被系统前缀跳过） |
| 高危词 / 包名黑名单 | 空集合 | 占位，待产品填充 |

匹配方式：**Aho-Corasick 自动机，子串包含，不区分大小写**（见 `AhoCorasick` + `SensitiveWordDictionary`）。

---

## 八、目录结构

```
BeHoly/
├── app/
│   ├── build.gradle.kts          (debug / v21 / release 三种构建类型)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/sensitive_words.txt
│       │   ├── java/com/example/beholy/
│       │   │   ├── BeHolyApp.kt              (Application: InAppLogger 初始化 + 一次性迁移)
│       │   │   ├── data/
│       │   │   │   ├── db/                    (AppDatabase, HitDao, HitRecordEntity — Room)
│       │   │   │   ├── Constants.kt
│       │   │   │   ├── DailyVerse.kt
│       │   │   │   ├── DetectionResult.kt
│       │   │   │   ├── RepentanceRecord.kt
│       │   │   │   └── SensitiveWordDictionary.kt
│       │   │   ├── detection/
│       │   │   │   ├── text/                  (AhoCorasick, TextDetector)
│       │   │   │   └── TierClassifier.kt
│       │   │   ├── service/                   (MonitoringService, BeHolyAccessibilityService)
│       │   │   ├── ui/
│       │   │   │   ├── stats/                 (DetectionStatsActivity + 日/周/月 Fragment + PagerAdapter)
│       │   │   │   ├── AccessibilityGuardActivity.kt
│       │   │   │   ├── BeHolyAdminReceiver.kt
│       │   │   │   ├── GraceActivity.kt
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── PermissionHelper.kt
│       │   │   │   ├── RepentanceActivity.kt
│       │   │   │   ├── RepentanceFormActivity.kt
│       │   │   │   └── RepentanceListActivity.kt
│       │   │   └── util/
│       │   │       ├── DeviceOwnerHelper.kt
│       │   │       ├── DisposalExecutor.kt
│       │   │       ├── HitLogger.kt           (操作日志 → detection_log.txt)
│       │   │       ├── InAppLogger.kt         (内存日志 + 崩溃日志持久化到 cacheDir)
│       │   │       ├── JsonlToDbMigrator.kt   (一次性迁移 detection_log.txt → Room)
│       │   │       ├── MaskUtils.kt           (敏感词掩码)
│       │   │       ├── MonitorState.kt        (节流 / 累计命中 / 冷静期 / 阻断期)
│       │   │       ├── RepentanceStore.kt     (悔改记录 JSONL + 导入导出)
│       │   │       └── StreakStore.kt         (连续守护天数)
│       │   └── res/                           (layout, values, xml/*, drawable, menu)
│       ├── test/                              (单元测试:Robolectric / Mockito)
│       └── androidTest/                       (仪表化测试:RepentanceStore / Accessibility 处置)
├── docs/                                      (mermaid 类图/时序图 + 增量设计/PRD + 优化评审)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── keystore.properties                         (gitignored,release 签名配置)
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
- **敏感词掩码**：日志与悔改记录中命中的敏感词只显示首字，其余以 `*` 代替，避免明文暴露。
- 本工具面向**个人自主管教**，不是医疗、心理或临床产品，也不构成对任何行为的判定。

---

## 十一、已知限制 / 后续方向

- 检测仅覆盖界面文字，无法识别无文字的成人图片 / 视频（如需，可评估重新引入 TFLite 图像模型分支）。
- 设备所有者配置为开发者级操作（adb），对普通用户门槛高；卸载需先移除 DO。
- 分级黑名单与高危词当前为空，所有命中默认走累计升级逻辑。
- 命中累计计数基于 Room 数据库「今日」查询，跨进程重启不丢失；但阻断期/冷静期状态为进程内内存（`MonitorState`），进程重启后清零（命中记录已持久化到 Room，统计不受影响）。
- 缺少「同伴 / 小组问责」与「跨设备同步」——若做信仰问责社群，这是最大的差异化扩展方向。
- 依赖无障碍服务，部分厂商 ROM 需手动加白保活；Google Play 对"用无障碍做监控"类应用有政策限制，分发渠道需评估。

---

## 十二、关联文档

- `docs/incremental_design_accessibility.md` / `docs/incremental_prd_accessibility.md`
- `docs/incremental_design_repentance.md` / `docs/incremental_prd_repentance.md`
- `docs/optimization_review.md`
- `docs/class-diagram.mermaid` / `docs/sequence-diagram.mermaid`

---

> 免责声明：本软件仅供个人灵修自主管教使用。使用者需自行了解所在地区对无障碍服务、设备管理员及内容过滤的法律与平台政策要求。
