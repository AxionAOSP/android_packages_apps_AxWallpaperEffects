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
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.android.axion.wallpapereffects.service.shape.Shape
import com.android.axion.wallpapereffects.service.shape.ShapeEffectConstants
import com.android.axion.wallpapereffects.service.shape.ShapeEffectState
import com.android.axion.wallpapereffects.service.shape.ShapeRenderModel
import com.android.axion.wallpapereffects.util.RectUtils
import com.android.axion.wallpapereffects.util.ShapePositionHelper
import kotlin.math.min
import kotlin.math.roundToLong

class ShapePositionAnimationController(
    private val callback: ShapeRendererCallback,
    var shapePositionHelper: ShapePositionHelper? = null,
) : ShapeEffectAnimationController {

    private val animator = ValueAnimator.ofFloat(0f, 1f)
    var currentState: State? = null
        private set

    private var stableState: State? = null
    private var targetState: State? = null
    var isTapAnimationRunning = false
        private set

    sealed class State {
        abstract val bitmap: Bitmap
        abstract val surfaceSize: Size

        data class WithShape(
            override val bitmap: Bitmap,
            override val surfaceSize: Size,
            val shape: Shape,
            val shapeBounds: RectF,
        ) : State()

        data class WithoutShape(override val bitmap: Bitmap, override val surfaceSize: Size) :
            State()
    }

    fun getTapAnimationScaleProgress(): Float {
        if (!isTapAnimationRunning) return 0f
        val current = currentState as? State.WithShape ?: return 0f
        val stable = stableState as? State.WithShape ?: return 0f
        return min(
            1f,
            (current.shapeBounds.width() / stable.shapeBounds.width() - 1f) / 0.20000005f,
        )
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun onShapeEffectStateChanged(state: ShapeEffectState) {
        val newState =
            when {
                state is ShapeEffectState.WithShape && state.shape != Shape.NONE ->
                    State.WithShape(state.bitmap, state.surfaceSize, state.shape, state.shapeBounds)
                else -> State.WithoutShape(state.bitmap, state.surfaceSize)
            }

        val oldState = currentState
        stableState = newState

        if (oldState == null) {
            currentState = newState
            if (newState is State.WithShape) {
                updateBounds(newState.shapeBounds)
            }
            callback.onRedrawNeeded()
            return
        }

        if (oldState.surfaceSize != newState.surfaceSize || oldState.bitmap != newState.bitmap) {
            currentState = newState
            animator.cancel()
            if (newState is State.WithShape) {
                updateBounds(newState.shapeBounds)
            }
            callback.onRedrawNeeded()
            return
        }

        if (newState is State.WithoutShape && oldState is State.WithoutShape) return

        if (
            newState is State.WithoutShape &&
                animator.isRunning &&
                targetState is State.WithoutShape
        )
            return

        if (newState is State.WithShape) {
            if (oldState is State.WithShape) {
                currentState = oldState.copy(shape = newState.shape)
                if (oldState.shapeBounds == newState.shapeBounds) {
                    animator.cancel()
                    return
                }
            } else if (animator.isRunning) {
                val target = targetState
                if (target is State.WithShape && newState.shapeBounds == target.shapeBounds) return
            }
        }

        startShapeAnimator(
            newState,
            ShapeEffectConstants.SHAPE_ANIMATION_DURATION_MS,
            ShapeEffectConstants.SHAPE_BITMAP_ANIMATION_INTERPOLATOR,
            false,
        ) {}
    }

    override fun onTap() {
        val current = currentState as? State.WithShape ?: return
        val stable = stableState as? State.WithShape ?: return

        if (!animator.isRunning || isTapAnimationRunning) {
            val currentScale = current.shapeBounds.width() / stable.shapeBounds.width()
            val scaleFactor =
                ((1.2f - currentScale) * 0.100000024f / 0.20000005f + currentScale) / currentScale
            val center = PointF(current.shapeBounds.centerX(), current.shapeBounds.centerY())
            val scaledBounds = RectUtils.scaleAround(current.shapeBounds, center, scaleFactor)
            val tapTarget = current.copy(shapeBounds = scaledBounds)

            val progress = min(1f, (currentScale - 1f) / 0.08000004f)
            val duration = 250 + (-50.0 * progress).roundToLong()
            val endInterpolator =
                ShapeEffectConstants.createTapEndInterpolator(getTapAnimationScaleProgress())

            startShapeAnimator(
                tapTarget,
                duration,
                ShapeEffectConstants.SHAPE_BITMAP_TAP_ANIMATION_INTERPOLATOR,
                true,
            ) {
                startShapeAnimator(
                    stable,
                    ShapeEffectConstants.TAP_ANIMATION_DURATION_MS,
                    endInterpolator,
                    true,
                ) {}
            }
        }
    }

    override fun reset() {
        animator.cancel()
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        currentState = null
        stableState = null
        targetState = null
        isTapAnimationRunning = false
    }

    private fun startShapeAnimator(
        target: State,
        duration: Long,
        interpolator: TimeInterpolator,
        isTap: Boolean,
        onEnd: () -> Unit,
    ) {
        isTapAnimationRunning = false
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()

        val current = currentState
        if (current is State.WithoutShape && target is State.WithoutShape) {
            Log.w(TAG, "Attempted to start a no-op animator. Aborting.")
            return
        }
        if (current == null) {
            Log.w(TAG, "Started an animation with a null current state. Aborting.")
            return
        }

        val startWithShape =
            when (current) {
                is State.WithShape -> current
                is State.WithoutShape -> {
                    val targetWS = target as State.WithShape
                    State.WithShape(
                        target.bitmap,
                        target.surfaceSize,
                        targetWS.shape,
                        targetWS.shape.shapeGoneAwayBounds(targetWS.surfaceSize),
                    )
                }
            }

        val startBounds = startWithShape.shapeBounds
        val startWidth = startBounds.width()
        val startHeight = startBounds.height()

        val targetBounds =
            when (target) {
                is State.WithShape -> target.shapeBounds
                is State.WithoutShape ->
                    startWithShape.shape.shapeGoneAwayBounds(target.surfaceSize)
            }

        currentState = startWithShape
        targetState = target

        animator.duration = duration
        animator.interpolator = interpolator

        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            val w = lerp(startWidth, targetBounds.width(), fraction)
            val h = lerp(startHeight, targetBounds.height(), fraction)
            val l = lerp(startBounds.left, targetBounds.left, fraction)
            val t = lerp(startBounds.top, targetBounds.top, fraction)
            updateBounds(RectF(l, t, l + w, t + h))
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        if (targetState is State.WithoutShape) {
                            val cs = currentState
                            currentState =
                                if (cs != null) State.WithoutShape(cs.bitmap, cs.surfaceSize)
                                else null
                            callback.onShapeRenderModelUpdated { model ->
                                ShapeRenderModel.WithoutShape(
                                    model.bitmap,
                                    model.matrix,
                                    model.surfaceSize,
                                )
                            }
                        } else {
                            updateBounds(targetBounds)
                        }
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

    fun updateBounds(bounds: RectF) {
        val current = currentState
        if (current !is State.WithShape) {
            Log.w(TAG, "Attempted to update the bounds while the current state is WithoutShape")
            return
        }
        currentState = current.copy(shapeBounds = bounds)

        val shapeMatrix = Shape.matrixForBounds(bounds)

        val helper = shapePositionHelper
        val foregroundAlpha =
            if (helper != null) {
                val defaultWidth = helper.defaultShapeBounds(current.surfaceSize, 0f).width()
                val goneWidth = current.shape.shapeGoneAwayBounds(current.surfaceSize).width()
                val lerpWidth = defaultWidth + (goneWidth - defaultWidth) * 0.85f
                (1f - (bounds.width() - lerpWidth) / (goneWidth - lerpWidth)).coerceIn(0f, 1f)
            } else {
                1f
            }

        callback.onShapeRenderModelUpdated { model ->
            if (model is ShapeRenderModel.WithShape) {
                model.copy(shapeMatrix = shapeMatrix, foregroundAlpha = foregroundAlpha)
            } else model
        }
    }

    companion object {
        private const val TAG = "ShapePositionAnimCtrl"

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction
    }
}
