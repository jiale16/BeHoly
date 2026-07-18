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
        "com.meizu.suggestion"     // ← 新增
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

    // ===== 通知 =====
    const val NOTIFICATION_CHANNEL_ID: String = "beholy_monitor_channel"
    const val NOTIFICATION_ID: Int = 1001
    const val HIT_NOTIFICATION_ID: Int = 1002

    // ===== 悔改链路透传 extra（沿用既有，禁止改动 key） =====
    const val EXTRA_REASON: String = "extra_reason"
    const val EXTRA_HIT_TIME: String = "extra_hit_time"
    const val REPENTANCE_FILE: String = "repentance_records.jsonl"

    // ===== 词库 =====
    const val SENSITIVE_WORDS_FILE: String = "sensitive_words.txt"

    // ===== 日志 TAG（供 Logcat 使用） =====
    const val LOG_TAG: String = "BeHoly"
}
