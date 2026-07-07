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

package com.android.axion.wallpapereffects.service.shape.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.android.axion.wallpapereffects.service.shape.ShapeEffectConstants
import com.android.axion.wallpapereffects.service.shape.ShapeEffectState
import com.android.axion.wallpapereffects.service.shape.ShapeRenderModel
import com.android.axion.wallpapereffects.util.CropHelper
import com.android.axion.wallpapereffects.util.RectUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class BitmapPositionAnimationController(
    private val callback: ShapeRendererCallback,
    private val shapePositionController: ShapePositionAnimationController,
) : ShapeEffectAnimationController {

    private val animator = ValueAnimator.ofFloat(0f, 1f)
    private var currentPosition: Position? = null
    private var stablePosition: Position? = null
    private var targetPosition: Position? = null
    var isTapAnimationRunning = false
        private set

    data class Position(
        val xOffsetForParallax: Float,
        val crop: RectF,
        val cropWithParallax: RectF,
        val isWithShape: Boolean,
        val bitmap: Bitmap,
        val surfaceSize: Size,
    )

    override fun isRunning(): Boolean = animator.isRunning

    override fun onShapeEffectStateChanged(state: ShapeEffectState) {
        val bitmap = state.bitmap
        val surfaceSize = state.surfaceSize
        val isWithShape = state is ShapeEffectState.WithShape
        val xOffset = if (state is ShapeEffectState.WithoutShape) state.xOffset else 0f
        val crop = state.crop
        val cropWithParallax =
            if (isWithShape) crop
            else
                CropHelper.applyParallax(
                    xOffset,
                    (state as ShapeEffectState.WithoutShape).crop,
                    surfaceSize,
                )

        val position = Position(xOffset, crop, cropWithParallax, isWithShape, bitmap, surfaceSize)
        stablePosition = position

        val current = currentPosition
        if (current == null) {
            updateRendererMatrix(position)
            callback.onRedrawNeeded()
            return
        }

        val bitmapSame = bitmap == current.bitmap
        val surfaceSame = surfaceSize == current.surfaceSize
        val shapeTypeChanged = isWithShape != current.isWithShape
        val cropSame = RectUtils.areAlmostEqual(crop, current.crop)
        val cropParallaxSame = RectUtils.areAlmostEqual(cropWithParallax, current.cropWithParallax)
        val xOffsetSame = xOffset == current.xOffsetForParallax

        if (bitmapSame && surfaceSame && cropSame && cropParallaxSame) {
            currentPosition = position
            return
        }

        if (!bitmapSame || !surfaceSame) {
            animator.cancel()
            updateRendererMatrix(position)
            return
        }

        if (!xOffsetSame && !cropParallaxSame && !shapeTypeChanged && cropSame) {
            if (animator.isRunning) {
                targetPosition = position
                return
            } else {
                updateRendererMatrix(position)
                callback.onRedrawNeeded()
                return
            }
        }

        if (cropParallaxSame) {
            animator.cancel()
            currentPosition = position
        } else {
            if (animator.isRunning && targetPosition == position) return
            startMatrixAnimator(
                position,
                ShapeEffectConstants.SHAPE_ANIMATION_DURATION_MS,
                ShapeEffectConstants.SHAPE_BITMAP_ANIMATION_INTERPOLATOR,
                false,
                0L,
            ) {}
        }
    }

    override fun onTap() {
        val current = currentPosition ?: return
        val stable = stablePosition ?: return
        if (!current.isWithShape || !stable.isWithShape) return

        if (!animator.isRunning || isTapAnimationRunning) {
            val scale = stable.cropWithParallax.width() / current.cropWithParallax.width()
            val inverseTapScale = scale / ((1.08f - scale) * 0.049999952f / 0.08000004f + scale)
            val center =
                PointF(current.cropWithParallax.centerX(), current.cropWithParallax.centerY())
            val scaledCrop =
                RectUtils.scaleAround(current.cropWithParallax, center, inverseTapScale)

            val tapTarget = current.copy(cropWithParallax = scaledCrop)

            val shapeProgress = shapePositionController.getTapAnimationScaleProgress()
            val progress = max(shapeProgress, min(1f, (scale - 1f) / 0.08000004f))
            val duration = 250 + (-50.0 * progress).roundToLong()
            val startDelay = 100 + (-100.0 * progress).roundToLong()
            val endInterpolator = ShapeEffectConstants.createTapEndInterpolator(progress)

            startMatrixAnimator(
                tapTarget,
                duration,
                ShapeEffectConstants.SHAPE_BITMAP_TAP_ANIMATION_INTERPOLATOR,
                true,
                startDelay,
            ) {
                startMatrixAnimator(
                    stable,
                    ShapeEffectConstants.TAP_ANIMATION_DURATION_MS,
                    endInterpolator,
                    true,
                    0L,
                ) {}
            }
        }
    }

    override fun reset() {
        animator.cancel()
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        currentPosition = null
        stablePosition = null
        targetPosition = null
        isTapAnimationRunning = false
    }

    private fun startMatrixAnimator(
        target: Position,
        duration: Long,
        interpolator: TimeInterpolator,
        isTap: Boolean,
        startDelay: Long,
        onEnd: () -> Unit,
    ) {
        isTapAnimationRunning = false
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()

        val current = currentPosition
        if (current == null) {
            Log.w(TAG, "Started an animation with a null current position. Aborting.")
            return
        }

        val startWidth = current.cropWithParallax.width()
        val startHeight = current.cropWithParallax.height()
        val startPosition = current

        targetPosition = target
        animator.duration = duration
        animator.interpolator = interpolator
        animator.startDelay = startDelay

        animator.addUpdateListener { anim ->
            val tp = targetPosition ?: return@addUpdateListener
            val fraction = anim.animatedValue as Float
            val w = lerp(startWidth, tp.cropWithParallax.width(), fraction)
            val h = lerp(startHeight, tp.cropWithParallax.height(), fraction)
            val l = lerp(startPosition.cropWithParallax.left, tp.cropWithParallax.left, fraction)
            val t = lerp(startPosition.cropWithParallax.top, tp.cropWithParallax.top, fraction)
            updateRendererMatrix(tp.copy(cropWithParallax = RectF(l, t, l + w, t + h)))
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        targetPosition?.let { updateRendererMatrix(it) }
                    }
                    callback.onAnimationStop()
                    isTapAnimationRunning = false
                    if (!canceled) onEnd()
                }

                override fun onAnimationPause(animation: Animator) {
                    callback.onAnimationStop()
                }

                override fun onAnimationResume(animation: Animator) {
                    callback.onAnimationStart()
                }
            }
        )

        isTapAnimationRunning = isTap
        callback.onAnimationStart()
        animator.start()
    }

    private fun updateRendererMatrix(position: Position) {
        currentPosition = position
        val matrix = Matrix()
        val crop = position.cropWithParallax
        matrix.preTranslate(-crop.left, -crop.top)
        val scale = position.surfaceSize.width / crop.width()
        matrix.postScale(scale, scale)
        callback.onShapeRenderModelUpdated { model ->
            when (model) {
                is ShapeRenderModel.WithShape -> model.copy(matrix = matrix)
                is ShapeRenderModel.WithoutShape -> model.copy(matrix = matrix)
            }
        }
    }

    companion object {
        private const val TAG = "BitmapPositionAnimCtrl"

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction
    }
}
