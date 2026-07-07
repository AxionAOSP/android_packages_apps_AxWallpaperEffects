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

package com.android.axion.wallpapereffects.util

import android.content.Context
import android.content.res.Resources
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import kotlin.math.min

class ShapePositionHelper(context: Context) {

    val isLargeScreen: Boolean
    val isFoldable: Boolean
    val horizontalMargin: Int
    val maxWallpaperScale: Float
    val topMargin: Int
    val defaultDisplaySizes: List<Size>

    init {

        val configScale =
            try {
                val id =
                    Resources.getSystem()
                        .getIdentifier("config_wallpaperMaxScale", "dimen", "android")
                if (id != 0) context.resources.getFloat(id) else 0f
            } catch (e: Exception) {
                0f
            }
        maxWallpaperScale = if (configScale == 0f) 1f else configScale

        val density = context.resources.displayMetrics.density
        horizontalMargin = (16f * density).toInt()

        topMargin = (4f * density).toInt()

        val dm = context.getSystemService(DisplayManager::class.java)

        val builtInDisplays =
            dm?.getDisplays("android.hardware.display.category.ALL_INCLUDING_DISABLED")?.filter {
                it.type == 1
            } ?: emptyList()

        var large = false
        val sizes = mutableListOf<Size>()
        var largestMetrics: DisplayMetrics? = null
        var secondMetrics: DisplayMetrics? = null
        var largestArea = 0f

        for (display in builtInDisplays) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val area =
                (metrics.widthPixels.toFloat() / metrics.xdpi) *
                    (metrics.heightPixels.toFloat() / metrics.ydpi)
            if (metrics.widthPixels / metrics.density >= 600f) large = true
            if (area > largestArea) {
                if (largestMetrics != null) secondMetrics = largestMetrics
                largestMetrics = metrics
                largestArea = area
            } else if (largestMetrics != null) {
                secondMetrics = metrics
            }
            sizes.add(Size(metrics.widthPixels, metrics.heightPixels))
            sizes.add(Size(metrics.heightPixels, metrics.widthPixels))
        }

        isLargeScreen = large
        isFoldable = secondMetrics != null
        defaultDisplaySizes = sizes

        Log.d(
            TAG,
            "Init: maxWallpaperScale=$maxWallpaperScale, horizontalMargin=$horizontalMargin, " +
                "topMargin=$topMargin, isLargeScreen=$isLargeScreen, isFoldable=$isFoldable, " +
                "displaySizes=$defaultDisplaySizes",
        )
    }

    fun defaultShapeBounds(surfaceSize: Size, foregroundOverlap: Float): RectF {
        val rect = RectF(0f, 0f, surfaceSize.width.toFloat(), surfaceSize.height.toFloat())
        val maxWidth = maxBoundsWidth(surfaceSize)
        val centeredRect = RectUtils.largestCenteredRect(rect, 1f / (foregroundOverlap + 1f))
        return RectUtils.bottomSquare(
            RectUtils.scale(centeredRect, maxWidth / centeredRect.width())
        )
    }

    fun maxBoundsWidth(surfaceSize: Size): Float {
        if (isFoldable || !isLargeScreen) {
            val aspect = surfaceSize.width.toFloat() / surfaceSize.height
            if (aspect < 0.75f || aspect > 1.3333334f) {
                if (aspect > 1.3333334f) {
                    Log.w(
                        TAG,
                        "Except for tablets, the shape may be mispositioned in landscape mode.",
                    )
                }
                return (min(surfaceSize.width, surfaceSize.height) - horizontalMargin * 2f) /
                    maxWallpaperScale
            }
            return min(surfaceSize.width, surfaceSize.height) * 0.5f
        }

        return min(surfaceSize.width, surfaceSize.height) * 0.6f
    }

    fun tallestPortraitDisplaySize(): Size? {
        return defaultDisplaySizes.maxByOrNull { it.height.toFloat() / it.width }
    }

    companion object {
        private const val TAG = "ShapePositionHelper"
    }
}
