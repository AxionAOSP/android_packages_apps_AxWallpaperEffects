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
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class PortraitMattingInference(context: Context) : ModelInference(context) {

    override fun getModelFile(): String = "portrait_matting_mask_1024_768.tflite"

    suspend fun convertBitmapToInputTensorImage(bitmap: Bitmap): TensorImage =
        withContext(Dispatchers.Default) {
            val shape = interpreter.getInputTensor(0).shape()
            val processor =
                ImageProcessor.Builder()
                    .add(ResizeOp(shape[1], shape[2], ResizeOp.ResizeMethod.BILINEAR))
                    .add(CastOp(DataType.FLOAT32))
                    .add(NormalizeOp(floatArrayOf(0f, 0f, 0f), floatArrayOf(255f, 255f, 255f)))
                    .build()
            val tensorImage = TensorImage(DataType.UINT8)
            tensorImage.load(bitmap)
            processor.process(tensorImage)
        }

    suspend fun convertBitmapToInputMaskBuffer(bitmap: Bitmap): ByteBuffer =
        withContext(Dispatchers.Default) {
            val shape = interpreter.getInputTensor(1).shape()
            val scaled = Bitmap.createScaledBitmap(bitmap, shape[2], shape[1], true)
            val buffer = TensorBuffer.createFixedSize(shape, DataType.UINT8)
            buffer.buffer.rewind()
            scaled.copyPixelsToBuffer(buffer.buffer)
            if (scaled !== bitmap) scaled.recycle()

            val processor =
                TensorProcessor.Builder()
                    .add(CastOp(DataType.FLOAT32))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            processor.process(buffer).buffer
        }

    suspend fun convertMaskOutputTensorToBitmaps(): Array<Bitmap> =
        withContext(Dispatchers.Default) {
            val outputIndexes = getOutputIndexes()
            val confidenceBitmap = convertConfidenceOutput(outputIndexes.first)
            val alphaBitmap = convertAlphaOutput(outputIndexes.second)
            arrayOf(confidenceBitmap, alphaBitmap)
        }

    private fun convertConfidenceOutput(outputIndex: Int): Bitmap {
        val shape = interpreter.getOutputTensor(outputIndex).shape()
        val height = shape[1]
        val width = shape[2]
        val tensorBuffer = outputTensorBuffers[outputIndex]!!
        val byteBuffer = tensorBuffer.buffer
        byteBuffer.rewind()

        val totalElements = shape.reduce { acc, v -> acc * v }
        val confidenceBuffer = ByteBuffer.allocateDirect(4 * totalElements)
        confidenceBuffer.order(ByteOrder.nativeOrder())

        while (byteBuffer.hasRemaining()) {
            val f = byteBuffer.float
            confidenceBuffer.putInt(
                Color.argb(
                    if (f > 0.99f) 1.0f else 0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                )
            )
        }

        confidenceBuffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(confidenceBuffer)
        return bitmap
    }

    private fun convertAlphaOutput(outputIndex: Int): Bitmap {
        val shape = interpreter.getOutputTensor(outputIndex).shape()
        val height = shape[1]
        val width = shape[2]
        val tensorBuffer = outputTensorBuffers[outputIndex]!!
        val byteBuffer = tensorBuffer.buffer
        byteBuffer.rewind()

        val totalElements = shape.reduce { acc, v -> acc * v }
        val alphaBuffer = ByteBuffer.allocateDirect(totalElements)
        alphaBuffer.order(ByteOrder.nativeOrder())

        while (byteBuffer.hasRemaining()) {
            val f = byteBuffer.float
            val alpha = (f.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt()
            alphaBuffer.put(alpha.toByte())
        }

        alphaBuffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        bitmap.copyPixelsFromBuffer(alphaBuffer)
        return bitmap
    }

    private fun getOutputIndexes(): Pair<Int, Int> {
        var alphaOutputIndex = 0
        var alphaOutputSize = 0
        var confidenceOutputIndex = 0
        var confidenceOutputSize = Int.MAX_VALUE
        for (i in 0 until interpreter.outputTensorCount) {
            val outputSize = interpreter.getOutputTensor(i).shape().fold(1) { acc, value ->
                acc * value
            }
            if (outputSize > alphaOutputSize) {
                alphaOutputIndex = i
                alphaOutputSize = outputSize
            }
            if (outputSize < confidenceOutputSize) {
                confidenceOutputIndex = i
                confidenceOutputSize = outputSize
            }
        }
        return Pair(confidenceOutputIndex, alphaOutputIndex)
    }
}
