package com.example.beholy.data

/**
 * 全局常量定义。集中管理分级处置阈值、冷静期时长、服务指令 action 与 extra key，
 * 禁止在业务代码里硬编码字符串字面量或魔数（见增量设计 §8 跨文件共享约定）。
 *
 * 本增量移除了图像/模型相关常量（IMAGE_NSFW_THRESHOLD、NSFW_MODEL_FILE、
 * MODEL_INPUT_SIZE、IMAGE_MEAN_BGR、CAPTURE_PIXEL_FORMAT），改由 AccessibilityService
 * 节点文本检测 + 分级处置常量取代。
 */
object Constants {
    // ===== 分级处置层级 =====
    const val TIER_NONE: Int = 0
    const val TIER1: Int = 1
    const val TIER2: Int = 2
    const val TIER3: Int = 3

    // ===== 命中来源 =====
    const val SOURCE_TEXT: String = "text"
    const val SOURCE_PACKAGE: String = "package"

    // ===== 冷静期与阈值 =====
    /** 冷静期时长（毫秒）：Tier2 封禁后的强制反思时长，默认 5 分钟 */
    const val COOLDOWN_PERIOD_MS: Long = 300_000L

    /** 同包累计命中达到该次数升级为 Tier2 */
    const val TIER2_HIT_THRESHOLD: Int = 3

    /** 同包累计命中达到该次数升级为 Tier3 */
    const val TIER3_HIT_THRESHOLD: Int = 5

    /** 累计命中统计的时间窗口（毫秒）：超出窗口的命中不计入升级 */
    const val TIER_WINDOW_MS: Long = 600_000L

    /** Accessibility 同包最小处理间隔（毫秒）：避免事件风暴导致刷屏误命中 */
    const val ACCESSIBILITY_SCAN_THROTTLE_MS: Long = 800L

    // ===== 无障碍关闭劝诫守卫 =====
    /** 劝诫警告冷却（毫秒）：避免设置页内事件风暴反复弹出 */
    const val A11Y_GUARD_COOLDOWN_MS: Long = 30_000L

    // ===== 包名过滤白名单（跳过检测） =====

    /** 跳过检测的包名前缀（包名以此开头即跳过，如系统应用） */
    val SKIP_PACKAGE_PREFIXES: Set<String> = setOf(
        "com.android"
    )

    /** 跳过检测的完整包名列表 */
    val SKIP_PACKAGE_NAMES: Set<String> = setOf(
        "com.meizu.flyme.launcher",
        "com.meizu.assistant",
        "com.meizu.sceneinfo",
        "com.tencent.wetype",      // ← 新增
        "com.meizu.suggestion",    // ← 新增
        "com.meizu.mstore",        // ← 魅族应用商店内不触发敏感词检测
        "com.meizu.net.pedometer"  // ← 魅族计步器
    )

    /**
     * 跳过检测：包名包含以下子串（不区分大小写）即跳过。
     * 用于各类圣经/读经 App（如 YouVersion=com.youversion.lifechurch.bible、
     * Crosswire=org.crosswire.android.bible、Logos=com.logos.bible 等），
     * 避免读经时被误判。
     */
    val SKIP_PACKAGE_CONTAINS: Set<String> = setOf(
        "bible"
    )

    /**
     * 强制检测名单（即便匹配上方跳过规则，也仍要检测）。
     * 浏览器能访问任意网页，是高危场景，绝不能因「com.android 系统应用前缀」被跳过。
     * 默认包含 AOSP 浏览器 com.android.browser 与 Chrome com.android.chrome；
     * 如设备自带浏览器是其他包名（如魅族 com.meizu.media.browser），
     * 在此追加即可，无需改动跳过逻辑。
     */
    val FORCE_DETECT_PACKAGE_NAMES: Set<String> = setOf(
        "com.android.browser",
        "com.android.chrome"
    )

    // ===== 通知 =====
    const val NOTIFICATION_CHANNEL_ID: String = "beholy_monitor_channel"
    /** 前台服务常驻通知 ID（金句） */
    const val NOTIFICATION_DAILY_ID: Int = 1001
    /** 前台服务警示通知 ID（悔改/处置期间使用，与金句分开避免覆盖） */
    const val NOTIFICATION_ALERT_ID: Int = 1003
    /** Hit 兜底通知 ID（ repentance 兜底 FullScreenIntent ） */
    const val HIT_NOTIFICATION_ID: Int = 1002

    /** 金句通知开关持久化：仅用户主动点击「显示每日金句」后才允许显示金句通知 */
    const val PREFS_MONITOR: String = "beholy_monitor"
    const val KEY_DAILY_ENABLED: String = "daily_enabled"

    // ===== 悔改链路透传 extra（沿用既有，禁止改动 key） =====
    const val EXTRA_REASON: String = "extra_reason"
    const val EXTRA_HIT_TIME: String = "extra_hit_time"
    const val REPENTANCE_FILE: String = "repentance_records.jsonl"

    // ===== 词库 =====
    const val SENSITIVE_WORDS_FILE: String = "sensitive_words.txt"

    // ===== 日志 TAG（供 Logcat 使用） =====
    const val LOG_TAG: String = "BeHoly"
}
