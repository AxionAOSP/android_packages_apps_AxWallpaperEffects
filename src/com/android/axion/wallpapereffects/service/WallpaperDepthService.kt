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

package com.android.axion.wallpapereffects.service

import android.app.Service
import android.app.WallpaperManager
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.android.axion.wallpapereffects.util.DepthMaskUtils
import com.android.axion.wallpapereffects.util.PortraitSegmenter
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "WallpaperDepthService"
private const val SETTING_DEPTH_MASK = "ax_depth_subject_mask"
private const val SETTING_DEPTH_BOUNDS = "ax_depth_subject_bounds"
private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
private const val DE_PHOTO_FILE = "wallpaper.jpg"

class WallpaperDepthService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var processingThread: Thread? = null
    private var lastScreenW = 0
    private var lastScreenH = 0

    private val colorsListener =
        WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) != 0) {
                Log.d(TAG, "Wallpaper colors changed (which=$which), scheduling depth processing")
                scheduleProcess()
            }
        }

    private val enabledObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val enabled = Settings.Secure.getInt(contentResolver, SETTING_DEPTH_ENABLED, 0)
                Log.d(TAG, "Depth enabled setting changed: $enabled")
                scheduleProcess()
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        lastScreenW = resources.displayMetrics.widthPixels
        lastScreenH = resources.displayMetrics.heightPixels
        val wm = WallpaperManager.getInstance(this)
        wm.addOnColorsChangedListener(colorsListener, handler)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DEPTH_ENABLED),
            false,
            enabledObserver,
        )

        scheduleProcess()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w != lastScreenW || h != lastScreenH) {
            Log.d(TAG, "Screen dimensions changed ${lastScreenW}x${lastScreenH} -> ${w}x${h}")
            lastScreenW = w
            lastScreenH = h
            scheduleProcess()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
        contentResolver.unregisterContentObserver(enabledObserver)
        processingThread?.interrupt()
        super.onDestroy()
    }

    private fun scheduleProcess() {

        processingThread?.interrupt()
        processingThread =
            Thread {
                    try {
                        processWallpaper()
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Processing interrupted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process wallpaper depth", e)
                        clearMask()
                    }
                }
                .also { it.start() }
    }

    private fun processWallpaper() {
        val enabled = Settings.Secure.getInt(contentResolver, SETTING_DEPTH_ENABLED, 0)
        if (enabled != 1) {
            Log.d(TAG, "Depth clock disabled (enabled=$enabled), clearing stale mask")
            clearMask()
            return
        }

        val wm = WallpaperManager.getInstance(this)
        val info = wm.wallpaperInfo
        val isLiveWallpaper = info != null
        val isEffectsWallpaper = info?.component?.packageName == packageName
        val hasLockWallpaper = hasLockWallpaper(wm)
        Log.d(TAG, "wallpaperInfo=${info?.component}, isLive=$isLiveWallpaper")

        if (
            info != null &&
                info.component.packageName == packageName &&
                info.component.className.endsWith("MagicPortraitService") &&
                !hasLockWallpaper
        ) {
            Log.d(TAG, "MagicPortraitService active (manages own depth mask), skipping")
            return
        }

        clearMask()

        val bitmap =
            loadWallpaperBitmap(wm, isLiveWallpaper, isEffectsWallpaper, hasLockWallpaper)
                ?: run {
                    Log.w(TAG, "Could not load wallpaper bitmap")
                    clearMask()
                    return
                }
        Log.d(TAG, "Loaded wallpaper bitmap: ${bitmap.width}x${bitmap.height}")

        val cropped = centerCropToDisplay(bitmap)
        if (cropped !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        Log.d(TAG, "Cropped bitmap: ${cropped.width}x${cropped.height}")

        val segmenter = PortraitSegmenter(this)
        segmenter.init()
        Log.d(TAG, "Segmenter initialized, running segmentation...")

        try {
            val fg = segmenter.segment(cropped)
            Log.d(
                TAG,
                "Segmentation result: fg=${if (fg != null) "${fg.width}x${fg.height}" else "null"}",
            )
            if (fg != null) {
                val pathData = DepthMaskUtils.extractSubjectPath(fg)
                Log.d(
                    TAG,
                    "Path extraction: ${if (pathData != null) "${pathData.length} chars" else "null (no subject)"}",
                )
                if (pathData != null) {
                    Settings.Secure.putString(contentResolver, SETTING_DEPTH_MASK, pathData)
                    Settings.Secure.putString(contentResolver, SETTING_DEPTH_BOUNDS, null)
                    Log.d(TAG, "Published depth path (${pathData.length} chars)")
                } else {
                    clearMask()
                }
                fg.recycle()
            } else {
                Log.d(TAG, "No subject detected in wallpaper")
                clearMask()
            }
        } finally {
            segmenter.release()
            if (!cropped.isRecycled) cropped.recycle()
        }
    }

    private fun loadWallpaperBitmap(
        wm: WallpaperManager,
        isLiveWallpaper: Boolean,
        isEffectsWallpaper: Boolean,
        hasLockWallpaper: Boolean,
    ): Bitmap? {
        if (hasLockWallpaper) {
            try {
                wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { lockPfd ->
                    val bmp = BitmapFactory.decodeFileDescriptor(lockPfd.fileDescriptor)
                    if (bmp != null) {
                        Log.d(TAG, "Lock wallpaper: ${bmp.width}x${bmp.height}")
                        return bmp
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lock wallpaper load failed", e)
            }
            return null
        }

        if (isLiveWallpaper && isEffectsWallpaper) {
            val deBitmap = loadFromDeStorage()
            if (deBitmap != null) return deBitmap
        }

        try {
            val drawable = wm.getDrawable()
            Log.d(TAG, "WallpaperManager drawable: ${drawable?.javaClass?.simpleName}")
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                Log.d(
                    TAG,
                    "WM bitmap: ${if (bmp != null) "${bmp.width}x${bmp.height}" else "null"}",
                )
                return bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "WallpaperManager load failed", e)
        }

        if (!isLiveWallpaper) {
            val deBitmap = loadFromDeStorage()
            if (deBitmap != null) return deBitmap
        }

        return null
    }

    private fun hasLockWallpaper(wm: WallpaperManager): Boolean {
        return try {
            wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { true } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun loadFromDeStorage(): Bitmap? {
        return try {
            val deCtx = createDeviceProtectedStorageContext()
            val file = File(deCtx.filesDir, DE_PHOTO_FILE)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    Log.d(TAG, "DE storage bitmap: ${bmp.width}x${bmp.height}")
                }
                bmp
            } else {
                Log.d(TAG, "DE storage $DE_PHOTO_FILE not found")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "DE storage load failed", e)
            null
        }
    }

    private fun centerCropToDisplay(bitmap: Bitmap): Bitmap {

        val wm = getSystemService(WindowManager::class.java)
        val maxBounds = wm?.maximumWindowMetrics?.bounds
        val dm = resources.displayMetrics
        val dstW = maxBounds?.width() ?: dm.widthPixels
        val dstH = maxBounds?.height() ?: dm.heightPixels

        Log.d(
            TAG,
            "centerCropToDisplay: " +
                "maxBounds=${maxBounds?.width()}x${maxBounds?.height()} " +
                "displayMetrics=${dm.widthPixels}x${dm.heightPixels} " +
                "using=${dstW}x${dstH} " +
                "bitmap=${bitmap.width}x${bitmap.height}",
        )

        if (dstW <= 0 || dstH <= 0) return bitmap

        val srcW = bitmap.width
        val srcH = bitmap.height
        val srcAR = srcW.toFloat() / srcH
        val dstAR = dstW.toFloat() / dstH

        val cropRect =
            if (srcAR > dstAR) {

                val cropW = (srcH * dstAR).roundToInt().coerceAtMost(srcW)
                val left = (srcW - cropW) / 2
                Rect(left, 0, left + cropW, srcH)
            } else {

                val cropH = (srcW / dstAR).roundToInt().coerceAtMost(srcH)
                val top = (srcH - cropH) / 2
                Rect(0, top, srcW, top + cropH)
            }

        if (cropRect.width() >= srcW - 2 && cropRect.height() >= srcH - 2) {
            return bitmap
        }

        Log.d(
            TAG,
            "Center-crop: ${srcW}x${srcH} -> ${cropRect.width()}x${cropRect.height()} " +
                "(display ${dstW}x${dstH})",
        )
        return try {
            Bitmap.createBitmap(
                bitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Center-crop failed", e)
            bitmap
        }
    }

    private fun clearMask() {
        try {
            Settings.Secure.putString(contentResolver, SETTING_DEPTH_MASK, null)
            Settings.Secure.putString(contentResolver, SETTING_DEPTH_BOUNDS, null)
        } catch (_: Exception) {}
    }
}
