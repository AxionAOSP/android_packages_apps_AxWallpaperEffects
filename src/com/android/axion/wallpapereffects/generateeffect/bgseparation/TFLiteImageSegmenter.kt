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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TFLiteImageSegmenter(private val context: Context) {

    suspend fun getForegroundImage(image: Bitmap): Bitmap = coroutineScope {
        val startTime = System.currentTimeMillis()

        val raidInference = RaidSegmentationInference(context)
        val portraitMattingInference = PortraitMattingInference(context)

        try {
            val segmentationInputDeferred = async {
                raidInference.convertBitmapToInputTensorImage(image)
            }
            val mattingInputDeferred = async {
                portraitMattingInference.convertBitmapToInputTensorImage(image)
            }

            val segmentationInput = segmentationInputDeferred.await()
            ensureActive()

            val raidInputBuffer = segmentationInput.tensorBuffer.buffer
            raidInference.run(arrayOf(raidInputBuffer))
            ensureActive()

            val raidMaskBitmap = raidInference.convertMaskOutputTensorToBitmap()
            val raidDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Segmentation complete ${raidDuration}ms")

            val mattingMaskBuffer =
                portraitMattingInference.convertBitmapToInputMaskBuffer(raidMaskBitmap)
            val mattingInput = mattingInputDeferred.await()
            ensureActive()

            val mattingImageBuffer = mattingInput.tensorBuffer.buffer
            portraitMattingInference.run(arrayOf(mattingImageBuffer, mattingMaskBuffer))
            ensureActive()

            val mattingBitmaps = portraitMattingInference.convertMaskOutputTensorToBitmaps()
            val confidenceBitmap = mattingBitmaps[0]
            val alphaBitmap = mattingBitmaps[1]
            val mattingDuration = System.currentTimeMillis() - startTime - raidDuration
            Log.d(TAG, "Matting complete ${mattingDuration}ms")

            val scaledMasks =
                withContext(Dispatchers.Default) {
                    Pair(
                        Bitmap.createScaledBitmap(alphaBitmap, image.width, image.height, true),
                        Bitmap.createScaledBitmap(confidenceBitmap, image.width, image.height, true),
                    )
                }

            val result =
                SegmentationHelper.getOriginalBitmapPixels(
                    mask = scaledMasks.first,
                    guide = scaledMasks.second,
                    original = image,
                )

            val totalDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Total segmentation pipeline: ${totalDuration}ms")

            result
        } finally {
            raidInference.close()
            portraitMattingInference.close()
        }
    }

    companion object {
        private const val TAG = "TFLiteImageSegmenter"
    }
}
