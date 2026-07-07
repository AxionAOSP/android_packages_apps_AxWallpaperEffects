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
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate

private const val TAG = "DepthEstimator"
private const val MODEL_FILE = "depth_model.tflite"

class DepthEstimator(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    fun init() {
        try {
            val fd = context.assets.openFd(MODEL_FILE)
            val stream = FileInputStream(fd.fileDescriptor)
            val channel = stream.channel
            val model =
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)

            val options = Interpreter.Options().apply { setNumThreads(4) }
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate enabled")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, using CPU", e)
                gpuDelegate = null
            }

            interpreter = Interpreter(model, options)
            val inputShape = interpreter!!.getInputTensor(0).shape()
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            Log.d(
                TAG,
                "Model loaded — input: ${inputShape.contentToString()}, output: ${outputShape.contentToString()}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load depth model", e)
        }
    }

    fun estimateDepth(bitmap: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        return try {
            val inputShape = interp.getInputTensor(0).shape()
            val inputH = inputShape[1]
            val inputW = inputShape[2]

            val resized = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
            val inputBuffer =
                ByteBuffer.allocateDirect(inputH * inputW * 3 * 4).order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputW * inputH)
            resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            if (resized !== bitmap) resized.recycle()

            for (pixel in pixels) {
                inputBuffer.putFloat(Color.red(pixel) / 255f)
                inputBuffer.putFloat(Color.green(pixel) / 255f)
                inputBuffer.putFloat(Color.blue(pixel) / 255f)
            }
            inputBuffer.rewind()

            val outputShape = interp.getOutputTensor(0).shape()
            val outputH = outputShape[1]
            val outputW = outputShape[2]
            val outputBuffer =
                ByteBuffer.allocateDirect(outputH * outputW * 4).order(ByteOrder.nativeOrder())

            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val depthValues = FloatArray(outputH * outputW)
            var minDepth = Float.MAX_VALUE
            var maxDepth = Float.NEGATIVE_INFINITY
            for (i in depthValues.indices) {
                val d = outputBuffer.getFloat()
                depthValues[i] = d
                if (d < minDepth) minDepth = d
                if (d > maxDepth) maxDepth = d
            }

            val range = maxDepth - minDepth
            val resultPixels = IntArray(outputH * outputW)
            for (i in depthValues.indices) {
                val normalized = if (range > 0f) (depthValues[i] - minDepth) / range else 0.5f
                val v = (normalized * 255f).toInt().coerceIn(0, 255)
                resultPixels[i] = Color.argb(255, v, v, v)
            }

            val depthBitmap = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888)
            depthBitmap.setPixels(resultPixels, 0, outputW, 0, 0, outputW, outputH)

            Log.d(TAG, "Depth estimated: ${outputW}x${outputH}, range [$minDepth, $maxDepth]")
            depthBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Depth estimation failed", e)
            null
        }
    }

    fun release() {
        try {
            interpreter?.close()
        } catch (_: Exception) {}
        try {
            gpuDelegate?.close()
        } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
    }
}
