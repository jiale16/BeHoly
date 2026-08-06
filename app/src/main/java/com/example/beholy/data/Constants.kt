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
    /** 冷静期时长（毫秒）：DO 下 Tier2 封禁后的强制反思时长，默认 5 分钟 */
    const val COOLDOWN_PERIOD_MS: Long = 300_000L

    /** 同包累计命中达到该次数升级为 Tier2 */
    const val TIER2_HIT_THRESHOLD: Int = 3

    /** 同包累计命中达到该次数升级为 Tier3 */
    const val TIER3_HIT_THRESHOLD: Int = 5

    /** 累计命中统计的时间窗口（毫秒）：超出窗口的命中不计入升级 */
    const val TIER_WINDOW_MS: Long = 600_000L

    /**
     * 非 DO 下的持续打断时长阶梯（毫秒）：命中后该时长内违规包回前台立刻 HOME。
     * 由同包累计命中次数驱动，等级递进体现「越陷越深，呼唤越恳切」。
     * - 第 1-2 次：30 秒——初次跌倒，给一个短暂的打断与悔改呼召
     * - 第 3-4 次（达 TIER2_HIT_THRESHOLD）：2 分钟——反复跌倒，延长冷静
     * - 第 5+ 次（达 TIER3_HIT_THRESHOLD）：5 分钟——深陷其中，给足停顿与对质时间
     *
     * DO 下不使用此阶梯（DO 走封禁+锁屏，有更强的系统级约束）。
     */
    val BLOCK_DURATION_BY_HIT_COUNT: List<Pair<IntRange, Long>> = listOf(
        1..2 to 30_000L,
        3..4 to 120_000L,
        5..Int.MAX_VALUE to 300_000L
    )

    /**
     * 悔改页最小停留时长阶梯（毫秒）：悔改页在此时长内「返回 BeHoly」按钮置灰倒计时。
     * 非 DO 下把「冷静期」从系统锁屏语义转移到 UI 约束——强迫用户停顿悔改，而非立即点掉。
     * 与 [BLOCK_DURATION_BY_HIT_COUNT] 同步递进。
     */
    val MIN_STAY_BY_HIT_COUNT: List<Pair<IntRange, Long>> = listOf(
        1..2 to 10_000L,
        3..4 to 30_000L,
        5..Int.MAX_VALUE to 60_000L
    )

    /**
     * 根据同包累计命中次数查询命中次数阶梯表，返回对应档位的值。
     * 用于 [BLOCK_DURATION_BY_HIT_COUNT] / [MIN_STAY_BY_HIT_COUNT]。
     * @param hitCount 当前累计命中次数（≥1）
     * @param ladder 阶梯表（IntRange → 值）
     * @return 对应档位的值；hitCount<1 时返回阶梯表首档的值
     */
    fun resolveFromHitCount(hitCount: Int, ladder: List<Pair<IntRange, Long>>): Long {
        if (hitCount < 1) return ladder.firstOrNull()?.second ?: 0L
        return ladder.firstOrNull { hitCount in it.first }?.second
            ?: ladder.lastOrNull()?.second
            ?: 0L
    }

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

    /** UI 状态持久化（日志显隐、权限引导标记等） */
    const val PREFS_UI: String = "beholy_ui"
    /** 悬浮窗权限引导标记：已引导过则不再自动弹出，避免反复打扰。用户可从菜单手动重新授权。 */
    const val KEY_OVERLAY_PROMPTED: String = "overlay_permission_prompted"

    // ===== 悔改链路透传 extra（沿用既有，禁止改动 key） =====
    const val EXTRA_REASON: String = "extra_reason"
    const val EXTRA_HIT_TIME: String = "extra_hit_time"
    /**
     * 本次命中的同包累计次数（非 DO 下悔改页强度递进依据）。
     * 由 [com.example.beholy.util.DisposalExecutor] 写入，透传到 RepentanceActivity。
     */
    const val EXTRA_HIT_COUNT: String = "extra_hit_count"
    const val REPENTANCE_FILE: String = "repentance_records.jsonl"

    // ===== 词库 =====
    const val SENSITIVE_WORDS_FILE: String = "sensitive_words.txt"

    /**
     * 连写安全短语豁免：这些短语整体出现时，只是对成人内容的「描述 / 归类」
     * （如第三方 App 的「举报页面」用「色情低俗」作为分类标签），并非成人内容本身。
     * 匹配时，若某个敏感词（如「色情」）的【所有】出现位置都落在这类短语区间内，
     * 才视为豁免，避免子串匹配误报；敏感词若出现在短语之外（如「色情网站」），仍正常命中。
     * 如需扩充豁免短语，直接在此追加即可，无需改动匹配逻辑。
     */
    val SAFE_PHRASES: Set<String> = setOf(
        "色情低俗"
    )

    // ===== 日志 TAG（供 Logcat 使用） =====
    const val LOG_TAG: String = "BeHoly"
}
