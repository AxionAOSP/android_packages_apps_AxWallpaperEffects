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

import android.graphics.Path
import android.os.VibrationEffect
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

object ShapeEffectConstants {

    val SHAPE_BITMAP_ANIMATION_INTERPOLATOR: Interpolator =
        createEmphasizedInterpolator()

    val SHAPE_BITMAP_TAP_ANIMATION_INTERPOLATOR: Interpolator =
        PathInterpolator(0.26873f, 0f, 0.45042f, 1f)

    val SHAPE_BITMAP_TAP_ANIMATION_END_INTERPOLATOR: Interpolator =
        PathInterpolator(0.26873f, 0f, 0.45042f, 1f)

    val SHAPE_BITMAP_TAP_ANIMATION_MAX_END_INTERPOLATOR: Interpolator = OvershootInterpolator(3.5f)

    val SHAPE_TAP_VIBRATION_EFFECT: VibrationEffect =
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.3f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.3f, 149)
            .compose()

    val SHAPE_COLOR_ANIMATION_INTERPOLATOR: Interpolator = LinearInterpolator()

    val SHAPE_MORPH_ANIMATION_INTERPOLATOR: Interpolator = OvershootInterpolator(1.2f)

    const val SHAPE_ANIMATION_DURATION_MS = 800L
    const val TAP_ANIMATION_DURATION_MS = 250L
    const val COLOR_ANIMATION_DURATION_MS = 150L

    fun createTapEndInterpolator(scaleProgress: Float): Interpolator {
        return Interpolator { input ->
            val base = SHAPE_BITMAP_TAP_ANIMATION_END_INTERPOLATOR.getInterpolation(input)
            val overshoot = SHAPE_BITMAP_TAP_ANIMATION_MAX_END_INTERPOLATOR.getInterpolation(input)
            base + (overshoot - base) * scaleProgress
        }
    }

    private fun createEmphasizedInterpolator(): Interpolator {
        val path = Path()
        path.moveTo(0f, 0f)
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        return PathInterpolator(path)
    }
}
