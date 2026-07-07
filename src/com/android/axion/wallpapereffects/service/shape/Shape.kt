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

package com.android.axion.wallpapereffects.service.shape

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.Size
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class Shape {
    NONE,
    PILL,
    SQUARE,
    ARCH,
    COOKIE_4_SIDED,
    COOKIE_6_SIDED;

    private val cachedShapeGoneAwayBounds = mutableMapOf<Size, RectF>()

    val roundedPolygon: RoundedPolygon by lazy { createRoundedPolygon() }

    val path: Path by lazy { roundedPolygon.toPath() }

    fun horizontalBounds(normalizedY: Float): Pair<Float, Float>? {
        val line =
            Path().apply {
                moveTo(-1f, normalizedY)
                lineTo(1f, normalizedY)
            }

        val strokePaint =
            Paint().apply {
                strokeWidth = 1e-5f
                style = Paint.Style.STROKE
            }

        val shapeFill = Path()
        strokePaint.getFillPath(path, shapeFill)

        val lineFill = Path()
        strokePaint.getFillPath(line, lineFill)

        val intersection = Path()
        if (!intersection.op(shapeFill, lineFill, Path.Op.INTERSECT)) {
            return null
        }

        val bounds = RectF()
        intersection.computeBounds(bounds, true)

        return Pair(bounds.left, bounds.right)
    }

    fun shapeGoneAwayBounds(surfaceSize: Size): RectF {
        cachedShapeGoneAwayBounds[surfaceSize]?.let {
            return it
        }

        val minDim = min(surfaceSize.width, surfaceSize.height)
        val halfW = (surfaceSize.width / 2f).roundToInt()
        val halfH = (surfaceSize.height / 2f).roundToInt()
        val surfaceRect = Rect(-halfW, -halfH, halfW, halfH)
        val surfaceRegion = Region(surfaceRect)

        var lo = 1f * minDim
        var hi = 5f * minDim
        val matrix = Matrix()
        val transformedPath = Path()
        val pathRegion = Region()
        val clipRegion = Region()

        while (hi - lo > 10f) {
            val mid = (lo + hi) / 2f
            matrix.reset()
            matrix.postScale(mid, mid)
            val halfMid = ceil(mid / 2f).toInt()
            clipRegion.set(-halfMid, -halfMid, halfMid, halfMid)
            transformedPath.reset()
            path.transform(matrix, transformedPath)
            pathRegion.setPath(transformedPath, clipRegion)
            pathRegion.op(surfaceRect, Region.Op.INTERSECT)
            if (pathRegion == surfaceRegion) {
                hi = mid
            } else {
                lo = mid
            }
        }

        val centerX = surfaceSize.width / 2f
        val centerY = surfaceSize.height / 2f
        val minF = min(surfaceSize.width, surfaceSize.height).toFloat()
        val halfMin = minF * 0.5f
        val baseRect =
            RectF(-halfMin + centerX, -halfMin + centerY, halfMin + centerX, halfMin + centerY)

        val scaleFactor = hi / minF
        val center = PointF(baseRect.centerX(), baseRect.centerY())
        val result =
            RectF(
                center.x + (baseRect.left - center.x) * scaleFactor,
                center.y + (baseRect.top - center.y) * scaleFactor,
                center.x + (baseRect.right - center.x) * scaleFactor,
                center.y + (baseRect.bottom - center.y) * scaleFactor,
            )

        cachedShapeGoneAwayBounds[surfaceSize] = result
        return result
    }

    private fun createRoundedPolygon(): RoundedPolygon {

        val centerMatrix = Matrix().apply { postTranslate(-0.5f, -0.5f) }
        return when (this) {
            NONE -> RoundedPolygon(numVertices = 4)
            PILL ->
                buildCustomPolygon(
                        points = floatArrayOf(0.961f, 0.039f, 1.001f, 0.428f, 1.0f, 0.609f),
                        roundings =
                            listOf(
                                CornerRounding(0.426f),
                                CornerRounding.Unrounded,
                                CornerRounding(1.0f),
                            ),
                        reps = 2,
                        mirroring = true,
                    )
                    .normalized()
                    .transformed(centerMatrix)
            SQUARE ->
                RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(0.3f))
                    .normalized()
                    .transformed(centerMatrix)
            ARCH -> {
                val rotMatrix = Matrix()
                rotMatrix.postRotate(-135f)
                RoundedPolygon(
                        numVertices = 4,
                        perVertexRounding =
                            listOf(
                                CornerRounding(1.0f),
                                CornerRounding(1.0f),
                                CornerRounding(0.2f),
                                CornerRounding(0.2f),
                            ),
                    )
                    .transformed(rotMatrix)
                    .normalized()
                    .transformed(centerMatrix)
            }
            COOKIE_4_SIDED -> {
                val cookie4Matrix =
                    Matrix().apply {
                        postTranslate(-0.5f, -0.5f)
                        postScale(1.1353f, 1.1353f)
                    }
                buildCustomPolygon(
                        points = floatArrayOf(1.237f, 1.236f, 0.500f, 0.918f),
                        roundings = listOf(CornerRounding(0.258f), CornerRounding(0.233f)),
                        reps = 4,
                        mirroring = false,
                    )
                    .normalized()
                    .transformed(cookie4Matrix)
            }
            COOKIE_6_SIDED -> {
                val cookie6Matrix =
                    Matrix().apply {
                        postTranslate(-0.5f, -0.5f)
                        postScale(1.1109f, 1.1109f)
                    }
                buildCustomPolygon(
                        points = floatArrayOf(0.723f, 0.884f, 0.500f, 1.099f),
                        roundings = listOf(CornerRounding(0.394f), CornerRounding(0.398f)),
                        reps = 6,
                        mirroring = false,
                    )
                    .normalized()
                    .transformed(cookie6Matrix)
            }
        }
    }

    companion object {
        val AVAILABLE_SHAPES: List<Shape> = entries.filter { it != NONE }

        fun matrixForBounds(bounds: RectF): Matrix {
            val matrix = Matrix()
            val size = min(bounds.width(), bounds.height())
            matrix.postScale(size, size)
            matrix.postTranslate(bounds.centerX(), bounds.centerY())
            return matrix
        }
    }
}

private fun buildCustomPolygon(
    points: FloatArray,
    roundings: List<CornerRounding>,
    reps: Int,
    mirroring: Boolean = false,
    cx: Float = 0.5f,
    cy: Float = 0.5f,
): RoundedPolygon {
    val numPts = points.size / 2

    val allVertices = mutableListOf<Float>()
    val allRoundings = mutableListOf<CornerRounding>()

    if (mirroring) {
        val angles = FloatArray(numPts)
        val distances = FloatArray(numPts)
        for (i in 0 until numPts) {
            val dx = points[i * 2] - cx
            val dy = points[i * 2 + 1] - cy
            angles[i] = atan2(dy, dx) * 180f / PI.toFloat()
            distances[i] = sqrt(dx * dx + dy * dy)
        }

        val actualReps = reps * 2
        val sectionAngle = 360f / actualReps

        for (sector in 0 until actualReps) {
            for (index in 0 until numPts) {
                val i = if (sector % 2 == 0) index else (numPts - 1 - index)
                if (i > 0 || sector % 2 == 0) {
                    val angleDeg =
                        sectionAngle * sector +
                            if (sector % 2 == 0) angles[i]
                            else sectionAngle - angles[i] + 2f * angles[0]
                    val a = angleDeg / 360f * 2f * PI.toFloat()
                    allVertices.add(cx + cos(a) * distances[i])
                    allVertices.add(cy + sin(a) * distances[i])
                    allRoundings.add(roundings[i])
                }
            }
        }
    } else {
        for (idx in 0 until numPts * reps) {
            val ptIdx = idx % numPts
            val rotAngle = (idx / numPts) * 360f / reps
            val a = rotAngle / 360f * 2f * PI.toFloat()
            val dx = points[ptIdx * 2] - cx
            val dy = points[ptIdx * 2 + 1] - cy
            allVertices.add(cx + dx * cos(a) - dy * sin(a))
            allVertices.add(cy + dx * sin(a) + dy * cos(a))
            allRoundings.add(roundings[ptIdx])
        }
    }

    return RoundedPolygon(
        vertices = allVertices.toFloatArray(),
        perVertexRounding = allRoundings,
        centerX = cx,
        centerY = cy,
    )
}
