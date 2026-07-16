package com.example.beholy.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.beholy.data.Constants
import com.example.beholy.data.RepentanceRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 悔改反思日志「存储 + 序列化 + 距上次」功能的仪表化测试（instrumented test）。
 *
 * 运行方式（需连真机/模拟器，本环境无法实跑）：
 *   ./gradlew connectedAndroidTest
 *
 * 设计要点：
 * - 每个用例用 @Before / @After 双重清除 repentance_records.jsonl，避免用例间串扰。
 * - 时间基准尽量用固定毫秒值（如 1_700_000_000_000L），规避真实时钟抖动导致边界断言不稳定。
 * - 断言集中在「行为」层面（关键字段一致、文案规则正确），不依赖实现细节。
 */
@RunWith(AndroidJUnit4::class)
class RepentanceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // 前置清理：保证每个用例都在干净文件上开始
        context.deleteFile(Constants.REPENTANCE_FILE)
    }

    @After
    fun tearDown() {
        // 后置清理：避免污染后续用例 / 其它测试
        context.deleteFile(Constants.REPENTANCE_FILE)
    }

    /** 构造一条「完整」的反思记录（mood/method 非空 List，含 note/reflection/avoidance）。 */
    private fun makeRecord(createdAt: Long = System.currentTimeMillis()): RepentanceRecord {
        return RepentanceRecord(
            hitTime = 1_700_000_000_000L,
            reason = "成人画面、敏感文字",
            mood = listOf("羞耻", "空虚"),
            moodNote = "心里很难受",
            method = listOf("深夜独处", "无意识刷视频"),
            methodNote = "一个人时最容易跌倒",
            reflection = "我得罪了神，也伤害了自己",
            avoidancePlan = "睡前把手机放在客厅充电",
            createdAt = createdAt
        )
    }

    // ============================================================
    // 1. save + getLast 往返一致性
    // ============================================================
    @Test
    fun testSaveAndGetLast() = runBlocking {
        val r = makeRecord()
        RepentanceStore.save(context, r)

        val last = RepentanceStore.getLast(context)
        assertNotNull("getLast 不应为 null", last)
        last!!

        // 关键字段与写入完全一致
        assertEquals(r.hitTime, last.hitTime)
        assertEquals(r.reason, last.reason)
        assertEquals(r.mood, last.mood)
        assertEquals(r.moodNote, last.moodNote)
        assertEquals(r.method, last.method)
        assertEquals(r.methodNote, last.methodNote)
        assertEquals(r.reflection, last.reflection)
        assertEquals(r.avoidancePlan, last.avoidancePlan)
        assertEquals(r.createdAt, last.createdAt)
    }

    // ============================================================
    // 2. formatSinceLast：首次（无记录）→ "首次"
    // ============================================================
    @Test
    fun testFormatSinceLastFirstTime() = runBlocking {
        context.deleteFile(Constants.REPENTANCE_FILE)
        val now = System.currentTimeMillis()
        val result = RepentanceStore.formatSinceLast(context, now)
        assertEquals("首次", result)
    }

    // ============================================================
    // 3. formatSinceLast：就在刚才 / 分钟级（极小 diff）
    // ============================================================
    @Test
    fun testFormatSinceLastJustNow() = runBlocking {
        context.deleteFile(Constants.REPENTANCE_FILE)
        val now = 1_700_000_000_000L
        RepentanceStore.save(context, makeRecord(createdAt = now))

        // diff = +1000ms（>0 但远不足 1 分钟）→ 分钟级文案，且不应以"天"开头
        val small = RepentanceStore.formatSinceLast(context, now + 1000L)
        assertTrue("分钟级文案不应以'天'开头，实际=$small", !small.startsWith("天"))
        assertTrue("应含'分钟'，实际=$small", small.contains("分钟"))

        // diff = -5000ms（<0）→ "就在刚才"
        val past = RepentanceStore.formatSinceLast(context, now - 5000L)
        assertEquals("就在刚才", past)
    }

    // ============================================================
    // 4. formatSinceLast：天/时/分 向下聚合
    // ============================================================
    @Test
    fun testFormatSinceLastDays() = runBlocking {
        context.deleteFile(Constants.REPENTANCE_FILE)
        val now = 1_700_000_000_000L

        // 3天2小时12分钟
        val createdA = now - 3L * 86_400_000L - 2L * 3_600_000L - 12L * 60_000L
        RepentanceStore.save(context, makeRecord(createdAt = createdA))
        val a = RepentanceStore.formatSinceLast(context, now)
        assertTrue("应含'3天'，实际=$a", a.contains("3天"))
        assertTrue("应含'2小时'，实际=$a", a.contains("2小时"))

        // 5000 分钟 = 300_000_000ms = 3天11小时20分钟（验证 天/时/分 换算正确）
        val createdB = now - 5000L * 60_000L
        RepentanceStore.save(context, makeRecord(createdAt = createdB))
        val b = RepentanceStore.formatSinceLast(context, now)
        assertTrue("应含'3天'，实际=$b", b.contains("3天"))
        assertTrue("应含'小时'，实际=$b", b.contains("小时"))
        assertTrue("应含'20分钟'，实际=$b", b.contains("20分钟"))
    }

    // ============================================================
    // 5. RepentanceRecord 序列化 / 反序列化 往返一致
    // ============================================================
    @Test
    fun testRepentanceRecordJsonRoundTrip() {
        val r = makeRecord()
        val json: JSONObject = r.toJson()
        val back = RepentanceRecord.fromJson(json)

        assertEquals(r.hitTime, back.hitTime)
        assertEquals(r.reason, back.reason)
        assertEquals(r.mood, back.mood)
        assertEquals(r.moodNote, back.moodNote)
        assertEquals(r.method, back.method)
        assertEquals(r.methodNote, back.methodNote)
        assertEquals(r.reflection, back.reflection)
        assertEquals(r.avoidancePlan, back.avoidancePlan)
        assertEquals(r.createdAt, back.createdAt)
    }

    // ============================================================
    // 6. getLast 容错：跳过损坏行，返回最后一条合法记录
    // ============================================================
    @Test
    fun testGetLastIgnoresCorruptLine() = runBlocking {
        context.deleteFile(Constants.REPENTANCE_FILE)
        val file = File(context.filesDir, Constants.REPENTANCE_FILE)
        val valid = makeRecord(createdAt = 123456789L)

        // 先写一行非法 JSON，再写一行合法 JSONObject
        file.writeText("!!!not_valid_json!!!\n" + valid.toJson().toString() + "\n")

        val last = RepentanceStore.getLast(context)
        assertNotNull("损坏行应被跳过，返回合法记录", last)
        last!!

        assertEquals(valid.createdAt, last.createdAt)
        assertEquals(valid.reason, last.reason)
        assertEquals(valid.mood, last.mood)
        assertEquals(valid.method, last.method)
        assertEquals(valid.reflection, last.reflection)
        assertEquals(valid.avoidancePlan, last.avoidancePlan)
    }

    // ============================================================
    // 7. 边界补充：diff == 0 → "就在刚才"
    // ============================================================
    @Test
    fun testFormatSinceLastZeroDiff() = runBlocking {
        context.deleteFile(Constants.REPENTANCE_FILE)
        val now = 1_700_000_000_000L
        RepentanceStore.save(context, makeRecord(createdAt = now))
        // diff == 0 → "就在刚才"
        assertEquals("就在刚才", RepentanceStore.formatSinceLast(context, now))
    }
}
