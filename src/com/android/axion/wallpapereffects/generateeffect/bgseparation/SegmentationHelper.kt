/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.wallpapereffects.generateeffect.bgseparation

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SegmentationHelper {

    suspend fun getOriginalBitmapPixels(mask: Bitmap, guide: Bitmap, original: Bitmap): Bitmap =
        withContext(Dispatchers.Default) {
            val width = original.width
            val height = original.height
            val alpha = readAlpha(mask)
            val guideAlpha = readAlpha(guide)
            val selected = selectSubject(alpha, guideAlpha, width, height)
            val pixels = IntArray(width * height)
            original.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val outputAlpha = if (selected[i]) alpha[i] else 0
                pixels[i] = (outputAlpha shl 24) or (pixels[i] and RGB_MASK)
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

    private fun readAlpha(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return IntArray(pixels.size) { Color.alpha(pixels[it]) }
    }

    private fun selectSubject(
        alpha: IntArray,
        guideAlpha: IntArray,
        width: Int,
        height: Int,
    ): BooleanArray {
        val labels = IntArray(alpha.size)
        val queue = IntArray(alpha.size)
        var label = 1
        var bestLabel = 0
        var bestScore = 0f

        for (i in alpha.indices) {
            if (labels[i] != 0 || alpha[i] <= SUBJECT_ALPHA_THRESHOLD) continue

            val stats = labelComponent(i, label, alpha, guideAlpha, labels, queue, width, height)
            val score = stats.score(width, height)
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
            }
            label++
        }

        val selected = BooleanArray(alpha.size)
        if (bestLabel == 0) return selected

        for (i in labels.indices) {
            selected[i] = labels[i] == bestLabel
        }
        return selected
    }

    private fun labelComponent(
        start: Int,
        label: Int,
        alpha: IntArray,
        guideAlpha: IntArray,
        labels: IntArray,
        queue: IntArray,
        width: Int,
        height: Int,
    ): ComponentStats {
        val stats = ComponentStats()
        var head = 0
        var tail = 0
        queue[tail++] = start
        labels[start] = label

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            stats.add(x, y, guideAlpha[index])

            if (x > 0) {
                tail = enqueue(index - 1, label, alpha, labels, queue, tail)
            }
            if (x < width - 1) {
                tail = enqueue(index + 1, label, alpha, labels, queue, tail)
            }
            if (y > 0) {
                tail = enqueue(index - width, label, alpha, labels, queue, tail)
            }
            if (y < height - 1) {
                tail = enqueue(index + width, label, alpha, labels, queue, tail)
            }
        }

        return stats
    }

    private fun enqueue(
        index: Int,
        label: Int,
        alpha: IntArray,
        labels: IntArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (labels[index] != 0 || alpha[index] <= SUBJECT_ALPHA_THRESHOLD) return tail
        labels[index] = label
        queue[tail] = index
        return tail + 1
    }

    private fun ComponentStats.score(width: Int, height: Int): Float {
        if (area.toFloat() / (width * height) < MIN_SUBJECT_RATIO) return 0f

        val centerX = sumX.toFloat() / area / width
        val centerY = sumY.toFloat() / area / height
        val centerDistance = (abs(centerX - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val centerWeight = 1.25f - centerDistance * 0.45f
        val verticalWeight = 0.65f + centerY
        val edgeWeight = if (top <= height * 0.02f && centerY < 0.45f) 0.45f else 1f

        return (area + guideArea * GUIDE_WEIGHT) * centerWeight * verticalWeight * edgeWeight
    }

    private data class ComponentStats(
        var area: Int = 0,
        var guideArea: Int = 0,
        var sumX: Long = 0,
        var sumY: Long = 0,
        var left: Int = Int.MAX_VALUE,
        var top: Int = Int.MAX_VALUE,
        var right: Int = Int.MIN_VALUE,
        var bottom: Int = Int.MIN_VALUE,
    ) {
        fun add(x: Int, y: Int, guide: Int) {
            area++
            if (guide > GUIDE_ALPHA_THRESHOLD) guideArea++
            sumX += x.toLong()
            sumY += y.toLong()
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
    }

    private const val RGB_MASK = 0x00FFFFFF
    private const val SUBJECT_ALPHA_THRESHOLD = 32
    private const val GUIDE_ALPHA_THRESHOLD = 127
    private const val GUIDE_WEIGHT = 8f
    private const val MIN_SUBJECT_RATIO = 0.002f
}
