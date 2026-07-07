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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class ForegroundEstimationInference(context: Context) : ModelInference(context) {

    override fun getModelFile(): String = "foreground_estimator_5680_512_512.tflite"

    suspend fun normalizeInputTensorImage(tensorImage: TensorImage): TensorImage =
        withContext(Dispatchers.Default) {
            val processor =
                ImageProcessor.Builder()
                    .add(NormalizeOp(floatArrayOf(0f, 0f, 0f), floatArrayOf(255f, 255f, 255f)))
                    .build()
            processor.process(tensorImage)
        }

    suspend fun convertBitmapToInputMask(maskBitmap: Bitmap): TensorBuffer =
        withContext(Dispatchers.Default) {
            val shape = interpreter.getInputTensor(1).shape()
            val scaled = Bitmap.createScaledBitmap(maskBitmap, shape[2], shape[1], true)
            val buffer = TensorBuffer.createFixedSize(shape, DataType.UINT8)
            buffer.buffer.rewind()
            scaled.copyPixelsToBuffer(buffer.buffer)
            if (scaled !== maskBitmap) scaled.recycle()

            val processor =
                TensorProcessor.Builder()
                    .add(CastOp(DataType.FLOAT32))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            processor.process(buffer)
        }
}
