package com.example.beholy.util

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * Bitmap 复用工具：提供简单的复用池，避免每次截屏都新建 Bitmap 导致 GC 抖动。
 *
 * 实现说明：
 * - 维护一个按「尺寸」缓存的 Bitmap 池（LruCache，key = "w x h"）；
 * - [acquireReusable]：优先返回池中同尺寸且未回收的 Bitmap（并 eraseColor 清空），
 *   否则新建 ARGB_8888 的 Bitmap；
 * - [releaseReusable]：将不再使用的 Bitmap 放回池中以便复用。
 *
 * 注意：池容量固定较小（4 个），避免缓存无限增长；Bitmap 在复用前会被清空像素，
 * 因此调用方无需关心上一次的内容。
 */
object BitmapUtils {

    private val pool: LruCache<String, Bitmap> = LruCache(4)

    private fun key(w: Int, h: Int) = "${w}x${h}"

    /**
     * 获取一个指定尺寸的 Bitmap（优先复用池中同尺寸对象）。
     */
    @Synchronized
    fun acquireReusable(width: Int, height: Int): Bitmap {
        val cached = pool.get(key(width, height))
        return if (cached != null && !cached.isRecycled) {
            cached.eraseColor(0)
            cached
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * 归还一个 Bitmap 到复用池（仅当未回收时）。
     */
    @Synchronized
    fun releaseReusable(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        pool.put(key(bitmap.width, bitmap.height), bitmap)
    }
}
