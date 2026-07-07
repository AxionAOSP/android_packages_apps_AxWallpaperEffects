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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Trace
import android.util.Log
import android.util.Size
import com.android.axion.wallpapereffects.service.shape.animation.ShapeEffectAnimationController
import com.android.axion.wallpapereffects.util.CropHelper
import com.android.axion.wallpapereffects.util.LstarHelper
import kotlin.math.roundToInt

class ShapeRenderer {

    var additionalScale: Float = 1f
    var shapeRenderModel: ShapeRenderModel? = null
        private set

    var shapeEffectState: ShapeEffectState? = null
        private set

    var redrawNeeded: Boolean = false
        private set

    var shapeEffectAnimationControllers: List<ShapeEffectAnimationController> = emptyList()
    var onRedrawNeeded: (() -> Unit)? = null
    var onAnimationEndCallback: (() -> Unit)? = null

    fun draw(canvas: Canvas) {
        val scale = additionalScale
        val noScale = scale == 1f

        if (!noScale) {
            canvas.save()
            canvas.translate(canvas.width / 2f, canvas.height / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-canvas.width / 2f, -canvas.height / 2f)
        }

        try {
            val model = shapeRenderModel
            if (model == null) {
                Log.w(TAG, "Skipping drawing frame: shapeRenderModel is null.")
                return
            }

            when (model) {
                is ShapeRenderModel.WithoutShape -> {
                    val tracing = Trace.isTagEnabled(Trace.TRACE_TAG_APP)
                    if (tracing) Trace.traceBegin(Trace.TRACE_TAG_APP, "ShapeRenderer#drawFrame")
                    try {
                        canvas.drawBitmap(model.bitmap, model.matrix, null)
                    } finally {
                        if (tracing) Trace.traceEnd(Trace.TRACE_TAG_APP)
                    }
                }
                is ShapeRenderModel.WithShape -> {
                    val tracing = Trace.isTagEnabled(Trace.TRACE_TAG_APP)
                    if (tracing)
                        Trace.traceBegin(Trace.TRACE_TAG_APP, "ShapeRenderer#drawFrameWithShape")
                    try {

                        canvas.drawColor(model.shapeColor)

                        canvas.save()
                        val transformedPath = Path()
                        model.shapePath.transform(model.shapeMatrix, transformedPath)
                        canvas.clipPath(transformedPath)
                        canvas.drawBitmap(model.bitmap, model.matrix, null)
                        canvas.restore()

                        if (
                            model.shouldDrawForeground &&
                                model.segmentationModel is SegmentationModel.Loaded &&
                                model.foregroundAlpha > 0f
                        ) {
                            canvas.save()
                            val cutLine = model.normalizedCutLine ?: 0f
                            val cutRect = RectF(-1f, -1f, -1f, cutLine)
                            model.shapeMatrix.mapRect(cutRect)
                            cutRect.left = 0f
                            cutRect.top = 0f
                            cutRect.right = model.surfaceSize.width.toFloat()
                            canvas.clipRect(cutRect)

                            val paint =
                                Paint().apply {
                                    alpha = (model.foregroundAlpha * 255f).roundToInt()
                                }
                            canvas.drawBitmap(model.segmentationModel.bitmap, model.matrix, paint)
                            canvas.restore()
                        }
                    } finally {
                        if (tracing) Trace.traceEnd(Trace.TRACE_TAG_APP)
                    }
                }
            }
        } finally {
            if (!noScale) {
                canvas.restore()
            }
        }
    }

    fun getInitialShapeRenderModel(state: ShapeEffectState): ShapeRenderModel {
        return when (state) {
            is ShapeEffectState.WithoutShape -> {
                ShapeRenderModel.WithoutShape(
                    bitmap = state.bitmap,
                    matrix =
                        CropHelper.getCropMatrix(
                            1f,
                            CropHelper.applyParallax(state.xOffset, state.crop, state.surfaceSize),
                            state.surfaceSize,
                        ),
                    surfaceSize = state.surfaceSize,
                )
            }
            is ShapeEffectState.WithShape -> {
                val cropMatrix = CropHelper.getCropMatrix(1f, state.crop, state.surfaceSize)
                val shapeColor =
                    LstarHelper.colorWithLstar(state.shapeChipColor, state.shapeColorSliderValue)
                ShapeRenderModel.WithShape(
                    bitmap = state.bitmap,
                    matrix = cropMatrix,
                    surfaceSize = state.surfaceSize,
                    segmentationModel = state.segmentationModel,
                    shapePath = state.shape.path,
                    shapeColor = shapeColor,
                    shouldDrawForeground = state.shouldDrawForeground,
                    foregroundAlpha = 1f,
                    normalizedCutLine = state.normalizedCutLine,
                    shapeMatrix = Shape.matrixForBounds(state.shapeBounds),
                )
            }
        }
    }

    fun handleShapeRenderModelChanged(newState: ShapeEffectState, forceRedraw: Boolean = false) {
        redrawNeeded = forceRedraw

        if (shapeEffectState == null) {
            shapeRenderModel = getInitialShapeRenderModel(newState)
            redrawNeeded = true
        }

        var currentModel = shapeRenderModel!!

        val bitmapChanged = currentModel.bitmap != newState.bitmap
        val surfaceChanged = currentModel.surfaceSize != newState.surfaceSize

        if (bitmapChanged || surfaceChanged) {
            currentModel =
                when (currentModel) {
                    is ShapeRenderModel.WithShape ->
                        currentModel.copy(
                            bitmap = newState.bitmap,
                            surfaceSize = newState.surfaceSize,
                            segmentationModel =
                                if (bitmapChanged) SegmentationModel.Unloaded
                                else currentModel.segmentationModel,
                        )
                    is ShapeRenderModel.WithoutShape ->
                        currentModel.copy(
                            bitmap = newState.bitmap,
                            surfaceSize = newState.surfaceSize,
                        )
                }
            shapeRenderModel = currentModel
            redrawNeeded = true
        }

        if (
            currentModel is ShapeRenderModel.WithoutShape && newState is ShapeEffectState.WithShape
        ) {
            shapeRenderModel = getInitialShapeRenderModel(newState)
        }

        if (currentModel is ShapeRenderModel.WithShape && newState is ShapeEffectState.WithShape) {
            if (!redrawNeeded) {
                redrawNeeded =
                    currentModel.shouldDrawForeground != newState.shouldDrawForeground ||
                        currentModel.segmentationModel != newState.segmentationModel ||
                        currentModel.normalizedCutLine != newState.normalizedCutLine
            }

            shapeRenderModel =
                currentModel.copy(
                    segmentationModel = newState.segmentationModel,
                    shouldDrawForeground = newState.shouldDrawForeground,
                    normalizedCutLine = newState.normalizedCutLine,
                )
        }

        shapeEffectState = newState

        for (controller in shapeEffectAnimationControllers) {
            controller.onShapeEffectStateChanged(newState)
        }

        if (redrawNeeded) {
            onRedrawNeeded?.invoke()
        }
    }

    fun goToStateWithoutShape(
        bitmap: Bitmap,
        crop: RectF,
        surfaceSize: Size,
        xOffset: Float,
        onEnd: (() -> Unit)? = null,
    ) {
        val state = ShapeEffectState.WithoutShape(bitmap, crop, surfaceSize, xOffset)
        onAnimationEndCallback = onEnd
        handleShapeRenderModelChanged(state, false)
        if (!isAnyAnimationRunning()) {
            onAnimationEndCallback = null
            onEnd?.invoke()
        }
    }

    fun isAnimationRunning(cls: Class<out ShapeEffectAnimationController>): Boolean {
        return shapeEffectAnimationControllers.firstOrNull { cls.isInstance(it) }?.isRunning()
            ?: false
    }

    fun isAnyAnimationRunning(): Boolean {
        if (shapeEffectAnimationControllers.isEmpty()) return false
        return shapeEffectAnimationControllers.any { it.isRunning() }
    }

    fun updateRenderModel(model: ShapeRenderModel) {
        shapeRenderModel = model
        redrawNeeded = true
        onRedrawNeeded?.invoke()
    }

    companion object {
        private const val TAG = "ShapeRenderer"
    }
}
