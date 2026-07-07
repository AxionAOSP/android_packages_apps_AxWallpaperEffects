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

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Trace
import android.util.Log
import android.util.Size
import com.android.axion.wallpapereffects.service.shape.Shape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ForegroundPositionerImpl(private val shapePositionHelper: ShapePositionHelper) {

    private val displaySize: Size =
        shapePositionHelper.tallestPortraitDisplaySize() ?: Size(1080, 2400)

    private val shapeBoundsCache = mutableMapOf<Float, Pair<Float, Float>>()

    fun positionImage(foregroundBitmap: Bitmap): ForegroundPositionModel {
        val tracing = Trace.isTagEnabled(Trace.TRACE_TAG_APP)
        if (tracing) Trace.traceBegin(Trace.TRACE_TAG_APP, "ForegroundPositioner#positionImage")
        try {
            val result = positionImageInternal(foregroundBitmap)
            shapeBoundsCache.clear()
            return result
        } finally {
            if (tracing) Trace.traceEnd(Trace.TRACE_TAG_APP)
        }
    }

    private fun positionImageInternal(bitmap: Bitmap): ForegroundPositionModel {
        val bitmapSize = Size(bitmap.width, bitmap.height)
        val bitmapCenter = PointF(bitmapSize.width / 2f, bitmapSize.height / 2f)
        val width = bitmap.width
        val height = bitmap.height

        val stripHeight = ceil(height / 50.0).toInt()
        val strips = mutableListOf<Pair<Int, Int>?>()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var fgTop = Int.MAX_VALUE
        var fgBottom = Int.MIN_VALUE
        var fgLeft = Int.MAX_VALUE
        var fgRight = Int.MIN_VALUE
        var hasForeground = false

        for (y in 0 until height) {
            var rowLeft = Int.MAX_VALUE
            var rowRight = Int.MIN_VALUE
            for (x in 0 until width) {
                if (Color.alpha(pixels[y * width + x]) > FOREGROUND_ALPHA_THRESHOLD) {
                    fgTop = min(fgTop, y)
                    rowLeft = min(rowLeft, x)
                    rowRight = x
                    fgBottom = y
                    hasForeground = true
                }
            }

            if (fgTop == Int.MAX_VALUE) continue

            if ((y - fgTop) % stripHeight == 0) {
                strips.add(null)
            }

            if (rowLeft != Int.MAX_VALUE) {
                val current = strips.last()
                val updated =
                    if (current == null) {
                        Pair(rowLeft, rowRight)
                    } else {
                        Pair(min(current.first, rowLeft), max(current.second, rowRight))
                    }
                strips[strips.size - 1] = updated
                fgLeft = min(fgLeft, updated.first)
                fgRight = max(fgRight, updated.second)
            }
        }

        val foregroundBounds = Rect(fgLeft, fgTop, fgRight, fgBottom)

        val defaultCrop = CropHelper.centerAlign(displaySize, bitmapSize)
        val defaultShapeBounds = shapePositionHelper.defaultShapeBounds(displaySize, 0f)

        val shapeBoundsInBitmap =
            RectF(defaultShapeBounds).apply {
                offset(
                    bitmapCenter.x - defaultShapeBounds.centerX(),
                    bitmapCenter.y - defaultShapeBounds.centerY(),
                )
            }
        val scaleForBitmap = (defaultCrop.height() * 1.1111112f) / displaySize.height
        val scaledShapeBounds =
            RectUtils.scaleAround(
                shapeBoundsInBitmap,
                PointF(shapeBoundsInBitmap.centerX(), shapeBoundsInBitmap.centerY()),
                scaleForBitmap,
            )

        Log.i(
            TAG,
            "Start positionImage:\n  bitmap dimensions: $bitmapSize,\n  device portrait dimensions: $displaySize,\n  default crop: $defaultCrop,\n  default shape bounds for portrait: $scaledShapeBounds,\n  foreground bounds: $foregroundBounds",
        )

        if (!hasForeground) {
            return ForegroundPositionModel(
                shapeBoundsWithinBitmap =
                    Rect(
                        scaledShapeBounds.left.roundToInt(),
                        scaledShapeBounds.top.roundToInt(),
                        scaledShapeBounds.right.roundToInt(),
                        scaledShapeBounds.bottom.roundToInt(),
                    ),
                normalizedCutLine = null,
                shouldDrawForeground = false,
                foregroundOverlap = 0f,
            )
        }

        if (foregroundBounds.top == 0) {
            return fallback(bitmapSize, foregroundBounds, scaledShapeBounds)
        }

        var bestResult: ForegroundPositionModel? = null
        var bestZoom: Int? = null
        var bestOverlap: Int? = null
        var minLoss = Float.MAX_VALUE

        for (zoomPct in 90 downTo 60 step 5) {
            for (overlapPct in 30 downTo 10 step 5) {
                val overlap = overlapPct / 100f
                val shapeHeight =
                    (defaultCrop.height() *
                        shapePositionHelper.defaultShapeBounds(displaySize, overlap).height() /
                        displaySize.height) * 100f / zoomPct

                val shapeBitmapTop = (shapeHeight * overlap).roundToInt() + foregroundBounds.top
                val shapeSize = shapeHeight.roundToInt()
                val shapeBitmapBottom = shapeSize + shapeBitmapTop

                Log.v(
                    TAG,
                    "Trying zoom=$zoomPct, overlap=$overlapPct: top=$shapeBitmapTop, bottom=$shapeBitmapBottom",
                )

                val shapeMargin = shapeHeight * 0.11f
                if (
                    shapeBitmapTop < shapeMargin ||
                        shapeBitmapBottom > bitmapSize.height - shapeMargin
                ) {
                    Log.v(TAG, "  => Shape lacks vertical margin. Skipping.")
                    continue
                }

                val fgCenterX = foregroundBounds.centerX()
                val centerMargin = (shapeSize / 2f + 0.07f * shapeSize).roundToInt()
                val minCenterX = centerMargin
                val maxCenterX = bitmapSize.width - centerMargin

                if (minCenterX > maxCenterX) continue

                val preferredCenterX = fgCenterX.coerceIn(minCenterX, maxCenterX)

                val result =
                    findCutLine(
                        foregroundBounds,
                        strips,
                        stripHeight,
                        shapeBitmapTop,
                        shapeBitmapBottom,
                        shapeSize,
                        preferredCenterX,
                        minCenterX,
                        maxCenterX,
                    )

                if (result != null) {
                    val (shapeBounds, cutLine) = result
                    val model =
                        ForegroundPositionModel(
                            shapeBoundsWithinBitmap = shapeBounds,
                            normalizedCutLine = cutLine,
                            shouldDrawForeground = true,
                            foregroundOverlap = overlap,
                        )

                    val loss = ((30 - overlapPct) / 20f) + 5f * ((90 - zoomPct) / 30f).pow(2)
                    Log.v(
                        TAG,
                        "  => Found valid solution: bounds=$shapeBounds, loss=$loss (minLoss=$minLoss), cutLine=$cutLine",
                    )

                    if (bestResult == null || loss < minLoss) {
                        bestResult = model
                        bestZoom = zoomPct
                        bestOverlap = overlapPct
                        minLoss = loss
                    }
                } else {
                    Log.v(TAG, "  => Could not find a valid cut line. Skipping.")
                }
            }
        }

        if (bestResult == null) {
            return fallback(bitmapSize, foregroundBounds, scaledShapeBounds)
        }

        Log.d(
            TAG,
            "Found valid solution:\n  shape bounds: ${bestResult.shapeBoundsWithinBitmap},\n  normalized cut line: ${bestResult.normalizedCutLine},\n  zoom: $bestZoom, overlap: $bestOverlap",
        )
        return bestResult
    }

    private fun findCutLine(
        fgBounds: Rect,
        strips: List<Pair<Int, Int>?>,
        stripHeight: Int,
        shapeTop: Int,
        shapeBottom: Int,
        shapeSize: Int,
        preferredCenterX: Int,
        minCenterX: Int,
        maxCenterX: Int,
    ): Pair<Rect, Float>? {
        val halfSize = shapeSize / 2
        val shapeCenter = (shapeTop + shapeBottom) / 2
        var bestPair: Pair<Rect, Float>? = null

        var scanY = shapeCenter + (shapeSize * 0.25f).roundToInt()
        while (scanY >= shapeTop) {
            val normalizedY = ((scanY - shapeTop).toFloat() / shapeSize) - 0.5f

            if (normalizedY < -0.42f) break

            val shapeBounds = getShapeHorizontalBounds(normalizedY)
            val shapeWidth = ((shapeBounds.second - shapeBounds.first) * shapeSize).toInt()

            val stripIdx =
                if (scanY in fgBounds.top..fgBounds.bottom) {
                    (scanY - fgBounds.top) / stripHeight
                } else {
                    -1
                }

            val strip = if (stripIdx >= 0 && stripIdx < strips.size) strips[stripIdx] else null

            if (strip == null) {

                val centerX = preferredCenterX.coerceIn(minCenterX, maxCenterX)
                val left = centerX - halfSize
                return Pair(Rect(left, shapeTop, left + shapeSize, shapeBottom), normalizedY)
            }

            val fgWidth = strip.second - strip.first
            if (fgWidth <= shapeWidth) {

                val maxValidCenter =
                    (strip.first.toFloat() - shapeBounds.first * shapeSize).roundToInt()

                val minValidCenter =
                    (strip.second.toFloat() - shapeBounds.second * shapeSize).roundToInt()

                if (maxValidCenter >= minValidCenter) {
                    val centerX = preferredCenterX.coerceIn(minValidCenter, maxValidCenter)

                    if (
                        centerX in minCenterX..maxCenterX &&
                            (bestPair == null ||
                                abs(centerX - preferredCenterX) <
                                    abs(bestPair.first.centerX() - preferredCenterX))
                    ) {
                        val left = centerX - halfSize
                        val candidate =
                            Pair(Rect(left, shapeTop, left + shapeSize, shapeBottom), normalizedY)
                        bestPair = candidate
                        if (centerX == preferredCenterX) return bestPair
                    }
                }
            }

            scanY -= stripHeight
        }

        return bestPair
    }

    private fun getShapeHorizontalBounds(normalizedY: Float): Pair<Float, Float> {
        shapeBoundsCache[normalizedY]?.let {
            return it
        }

        var tightLeft = Float.NEGATIVE_INFINITY
        var tightRight = Float.POSITIVE_INFINITY
        for (shape in Shape.AVAILABLE_SHAPES) {
            val bounds = shape.horizontalBounds(normalizedY)
            if (bounds != null) {
                tightLeft = max(tightLeft, bounds.first)
                tightRight = min(tightRight, bounds.second)
            }
        }

        val result = Pair(tightLeft, tightRight)
        shapeBoundsCache[normalizedY] = result
        return result
    }

    companion object {
        private const val TAG = "ForegroundPositioner"

        private const val FOREGROUND_ALPHA_THRESHOLD = 0

        fun fallback(
            bitmapSize: Size,
            fgBounds: Rect,
            defaultShapeBounds: RectF,
        ): ForegroundPositionModel {
            val shapeW = defaultShapeBounds.width()
            val shapeH = defaultShapeBounds.height()

            val fgCenterX = fgBounds.exactCenterX()
            val margin = 0.07f * shapeW
            val halfW = shapeW / 2f
            val minCenterX = halfW + margin
            val maxCenterX = bitmapSize.width - halfW - margin

            val centerX =
                if (minCenterX > maxCenterX) {
                    Log.w(TAG, "Not enough horizontal margins: centering horizontally")
                    defaultShapeBounds.centerX()
                } else {
                    fgCenterX.coerceIn(minCenterX, maxCenterX)
                }

            val preferredTop = max(0f, fgBounds.top - 0.08f * shapeH)
            val maxVerticalDistance = 1f * shapeH
            val halfH = shapeH / 2f
            val preferredCenterY =
                (preferredTop + halfH).coerceIn(
                    defaultShapeBounds.centerY() - maxVerticalDistance,
                    defaultShapeBounds.centerY() + maxVerticalDistance,
                )

            val topMargin = halfH + shapeH * 0.11f
            val bottomMargin = bitmapSize.height - halfH - shapeH * 0.11f

            val centerY =
                if (topMargin > bottomMargin) {
                    Log.i(TAG, "Not enough vertical margins: centering vertically")
                    defaultShapeBounds.centerY()
                } else {
                    preferredCenterY.coerceIn(topMargin, bottomMargin)
                }

            val result =
                RectF(defaultShapeBounds).apply { offset(centerX - centerX(), centerY - centerY()) }

            Log.d(
                TAG,
                "Return fallback: foregroundCenterX=$fgCenterX\n  centerX=$centerX, centerY=$centerY\n  bounds=$result",
            )

            return ForegroundPositionModel(
                shapeBoundsWithinBitmap =
                    Rect(
                        result.left.roundToInt(),
                        result.top.roundToInt(),
                        result.right.roundToInt(),
                        result.bottom.roundToInt(),
                    ),
                normalizedCutLine = null,
                shouldDrawForeground = false,
                foregroundOverlap = 0f,
            )
        }
    }
}

data class ForegroundPositionModel(
    val shapeBoundsWithinBitmap: Rect,
    val normalizedCutLine: Float?,
    val shouldDrawForeground: Boolean,
    val foregroundOverlap: Float,
) {
    fun getCropWithShape(displaySize: Size, bitmapSize: Size, shapeBounds: RectF): RectF {
        val scale = shapeBoundsWithinBitmap.height() / shapeBounds.height()
        val displayRect = RectF(0f, 0f, displaySize.width.toFloat(), displaySize.height.toFloat())
        val center = PointF(displayRect.centerX(), displayRect.centerY())
        val scaledDisplay = RectUtils.scale(displayRect, scale)
        val scaledShape = RectUtils.scaleAround(shapeBounds, center, scale)
        scaledDisplay.offset(
            shapeBoundsWithinBitmap.exactCenterX() - scaledShape.centerX(),
            shapeBoundsWithinBitmap.exactCenterY() - scaledShape.centerY(),
        )
        return scaledDisplay
    }

    fun getCropWithoutShape(
        displaySize: Size,
        bitmapSize: Size,
        shapePositionHelper: ShapePositionHelper,
        isRtl: Boolean,
    ): RectF {
        if (
            bitmapSize.width.toFloat() / bitmapSize.height <=
                displaySize.width.toFloat() / displaySize.height
        ) {
            return CropHelper.centerAlign(displaySize, bitmapSize)
        }
        val crop =
            getCropWithShape(
                displaySize,
                bitmapSize,
                shapePositionHelper.defaultShapeBounds(displaySize, foregroundOverlap),
            )
        val centerX = crop.centerX()
        val halfWidth = (crop.width() * bitmapSize.height / crop.height()) / 2f
        val result =
            RectF(centerX - halfWidth, 0f, centerX + halfWidth, bitmapSize.height.toFloat())

        if (result.left < 0f) result.offsetTo(0f, result.top)
        if (result.right > bitmapSize.width)
            result.offsetTo(bitmapSize.width - result.width(), result.top)

        return CropHelper.addWidthForParallax(result, bitmapSize, isRtl)
    }

    fun flattenToString(): String {
        if (!shouldDrawForeground && normalizedCutLine == null) return ""
        return "${shapeBoundsWithinBitmap.flattenToString()}|$normalizedCutLine|$shouldDrawForeground|$foregroundOverlap"
    }
}
