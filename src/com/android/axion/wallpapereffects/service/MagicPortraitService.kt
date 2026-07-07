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

import android.app.WallpaperColors
import android.app.wallpaper.WallpaperDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import com.android.axion.wallpapereffects.generateeffect.bgseparation.TFLiteImageSegmenter
import com.android.axion.wallpapereffects.service.shape.RotationDirection
import com.android.axion.wallpapereffects.service.shape.SegmentationModel
import com.android.axion.wallpapereffects.service.shape.Shape
import com.android.axion.wallpapereffects.service.shape.ShapeEffectConstants
import com.android.axion.wallpapereffects.service.shape.ShapeEffectState
import com.android.axion.wallpapereffects.service.shape.ShapeRenderModel
import com.android.axion.wallpapereffects.service.shape.ShapeRenderer
import com.android.axion.wallpapereffects.service.shape.animation.BitmapPositionAnimationController
import com.android.axion.wallpapereffects.service.shape.animation.ShapeColorAnimationController
import com.android.axion.wallpapereffects.service.shape.animation.ShapeMorphAnimationController
import com.android.axion.wallpapereffects.service.shape.animation.ShapePositionAnimationController
import com.android.axion.wallpapereffects.service.shape.animation.ShapeRendererCallback
import com.android.axion.wallpapereffects.util.CropHelper
import com.android.axion.wallpapereffects.util.DepthMaskUtils
import com.android.axion.wallpapereffects.util.ForegroundPositionModel
import com.android.axion.wallpapereffects.util.ForegroundPositionerImpl
import com.android.axion.wallpapereffects.util.RectUtils
import com.android.axion.wallpapereffects.util.ShapePositionHelper
import com.google.android.torus.canvas.engine.CanvasWallpaperEngine
import com.google.android.torus.core.content.ConfigurationChangeListener
import com.google.android.torus.core.engine.TorusEngine
import com.google.android.torus.core.engine.listener.TorusTouchListener
import com.google.android.torus.core.wallpaper.LiveWallpaper
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperEventListener
import com.google.android.torus.core.wallpaper.listener.LiveWallpaperKeyguardEventListener
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MagicPortraitService"
private const val ACTION_RELOAD = "com.android.axion.wallpapereffects.RELOAD_WALLPAPER"

private const val SETTING_SHAPE = "ax_effect_portrait_shape"
private const val SETTING_COLOR = "ax_effect_portrait_color"
private const val SETTING_LSTAR = "ax_effect_portrait_lstar"
private const val SETTING_DEPTH_MASK = "ax_depth_subject_mask"
private const val SETTING_DEPTH_BOUNDS = "ax_depth_subject_bounds"
private const val DEFAULT_LSTAR = 45f

private const val PHOTO_FILE = "wallpaper.jpg"
private const val FOREGROUND_FILE = "effect_foreground.png"
private const val FOREGROUND_META_FILE = "effect_foreground_v1.meta"
private const val LEGACY_FOREGROUND_META_FILE = "effect_foreground.meta"
private const val FOREGROUND_CACHE_VERSION = "portrait-v1"

private data class PhotoToken(val cacheKey: String, val photoSize: Size, val marker: Any = Any())

class MagicPortraitService : LiveWallpaper() {

    override fun getWallpaperEngine(
        context: Context,
        surfaceHolder: SurfaceHolder,
        description: WallpaperDescription?,
    ): TorusEngine {
        return PortraitEngine(context, surfaceHolder, description)
    }

    inner class PortraitEngine(
        private val context: Context,
        surfaceHolder: SurfaceHolder,
        private val wallpaperDescription: WallpaperDescription?,
    ) :
        CanvasWallpaperEngine(surfaceHolder, true),
        ConfigurationChangeListener,
        LiveWallpaperEventListener,
        LiveWallpaperKeyguardEventListener,
        TorusTouchListener {

        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private val shapeRenderer = ShapeRenderer()
        private lateinit var shapePositionController: ShapePositionAnimationController
        private lateinit var bitmapPositionController: BitmapPositionAnimationController
        private lateinit var shapeMorphController: ShapeMorphAnimationController
        private lateinit var shapeColorController: ShapeColorAnimationController

        private val shapePositionHelper = ShapePositionHelper(context)

        private var photoBitmap: Bitmap? = null
        private var foregroundBitmap: Bitmap? = null
        private var positionModel: ForegroundPositionModel? = null
        private var surfaceSize: Size? = null
        private var xOffset: Float = 0f
        private var isOnLockscreen = false
        private var isPaused = true
        private var isRtl =
            context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        private var lockscreenBounds: RectF? = null

        private var currentShapeIndex = 0
        private var currentColor = 0xFF2196F3.toInt()
        private var currentLstar = DEFAULT_LSTAR
        private var previousShape: Shape = Shape.NONE

        private var vibratorManager: VibratorManager? = null

        private var cachedDepthPathData: String? = null
        private var activeDepthPathData: String? = null
        private var depthMaskActive = false
        private var ownsDepthState = false

        private var foregroundLoadJob: Job? = null
        private var segmentationJob: Job? = null
        private var activePhotoToken: PhotoToken? = null

        private val handler = Handler(Looper.getMainLooper())
        private val settingsObserver =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    readSettings()
                    updateShapeEffectState()
                }
            }

        private val reloadReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    Log.d(TAG, "Reload broadcast received")
                    loadPhotoAndSegment()
                }
            }

        private val rendererCallback =
            object : ShapeRendererCallback {
                override fun onRedrawNeeded() {
                    this@PortraitEngine.requestRender()
                }

                override fun onAnimationStart() {
                    if (isPaused) return
                    startUpdateLoop()
                }

                override fun onAnimationStop() {
                    if (!shapeRenderer.isAnyAnimationRunning()) {
                        stopUpdateLoop()
                        shapeRenderer.onAnimationEndCallback?.invoke()
                        shapeRenderer.onAnimationEndCallback = null
                    }
                }

                override fun onShapeRenderModelUpdated(
                    updater: (ShapeRenderModel) -> ShapeRenderModel
                ) {
                    val current = shapeRenderer.shapeRenderModel ?: return
                    shapeRenderer.updateRenderModel(updater(current))
                }
            }

        private fun requestRender() {
            if (isPaused) return
            startUpdateLoop()
            handler.postDelayed(
                { if (!shapeRenderer.isAnyAnimationRunning()) stopUpdateLoop() },
                32,
            )
        }

        override fun onCreate(isFirstActiveInstance: Boolean) {
            super.onCreate(isFirstActiveInstance)
            Log.d(TAG, "onCreate")

            vibratorManager = context.getSystemService(VibratorManager::class.java)

            shapePositionController =
                ShapePositionAnimationController(rendererCallback, shapePositionHelper)
            bitmapPositionController =
                BitmapPositionAnimationController(rendererCallback, shapePositionController)
            shapeMorphController = ShapeMorphAnimationController(rendererCallback)
            shapeColorController = ShapeColorAnimationController(rendererCallback)

            shapeRenderer.shapeEffectAnimationControllers =
                listOf(
                    bitmapPositionController,
                    shapePositionController,
                    shapeMorphController,
                    shapeColorController,
                )
            shapeRenderer.onRedrawNeeded = { requestRender() }
            shapeRenderer.additionalScale =
                if (isPreview()) shapePositionHelper.maxWallpaperScale else 1f

            val cr = context.contentResolver
            cr.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_SHAPE),
                false,
                settingsObserver,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_COLOR),
                false,
                settingsObserver,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_LSTAR),
                false,
                settingsObserver,
            )

            context.registerReceiver(
                reloadReceiver,
                IntentFilter(ACTION_RELOAD),
                Context.RECEIVER_EXPORTED,
            )

            readSettings()
            loadForegroundIfNeeded()
        }

        override fun onResume() {
            super.onResume()
            Log.d(TAG, "onResume")
            isPaused = false
            loadForegroundIfNeeded()
            updateShapeEffectState()
        }

        override fun onPause() {
            super.onPause()
            Log.d(TAG, "onPause")
            isPaused = true
            stopUpdateLoop()
        }

        override fun onDestroy(isLastActiveInstance: Boolean) {
            super.onDestroy(isLastActiveInstance)
            Log.d(TAG, "onDestroy")

            scope.cancel()
            context.contentResolver.unregisterContentObserver(settingsObserver)
            try {
                context.unregisterReceiver(reloadReceiver)
            } catch (_: Exception) {}

            updateDepthState(false)

            shapeRenderer.shapeEffectAnimationControllers.forEach { it.reset() }
            shapeRenderer.onAnimationEndCallback = null
        }

        override fun onResize(size: Size) {
            super.onResize(size)
            Log.d(TAG, "onResize: $size")
            surfaceSize = size
            updateShapeEffectState()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            isRtl = newConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL
            updateShapeEffectState()
        }

        override fun onUpdate(elapsedMs: Long, deltaMs: Long) {
            super.onUpdate(elapsedMs, deltaMs)
            render { canvas -> shapeRenderer.draw(canvas) }
        }

        override fun onOffsetChanged(xOffset: Float, xOffsetStep: Float) {
            this.xOffset = xOffset
            updateShapeEffectState()
        }

        override fun shouldZoomOutWallpaper(): Boolean = true

        override fun onZoomChanged(zoomLevel: Float) {}

        override fun onWallpaperReapplied() {}

        override fun onSleep(extras: Bundle) {}

        override fun onWake(extras: Bundle) {
            loadForegroundIfNeeded()
            updateShapeEffectState()
        }

        override fun onTouchEvent(event: MotionEvent) {}

        override fun onKeyguardAppearing() {
            Log.d(TAG, "onKeyguardAppearing")
            isOnLockscreen = true
            loadForegroundIfNeeded()
            updateShapeEffectState()
        }

        override fun onKeyguardGoingAway() {
            Log.d(TAG, "onKeyguardGoingAway")
            isOnLockscreen = false
            updateShapeEffectState()
        }

        override fun onLockscreenFocalAreaTap(x: Int, y: Int) {
            Log.d(TAG, "Tap ($x, $y) on lockscreen")
            if (isPaused) return

            val state = shapePositionController.currentState
            if (state !is ShapePositionAnimationController.State.WithShape) return
            if (state.shape == Shape.NONE) return

            val shapeBounds = state.shapeBounds
            val fx = x.toFloat()
            val fy = y.toFloat()

            if (!shapeBounds.contains(fx, fy)) return

            val normalizedX = (fx - shapeBounds.centerX()) / shapeBounds.width()
            val normalizedY = (fy - shapeBounds.centerY()) / shapeBounds.height()
            val hBounds = state.shape.horizontalBounds(normalizedY)
            if (hBounds == null || normalizedX < hBounds.first || normalizedX > hBounds.second)
                return

            shapeRenderer.shapeEffectAnimationControllers.forEach { it.onTap() }

            if (
                shapePositionController.isTapAnimationRunning ||
                    bitmapPositionController.isTapAnimationRunning
            ) {
                vibratorManager
                    ?.defaultVibrator
                    ?.vibrate(ShapeEffectConstants.SHAPE_TAP_VIBRATION_EFFECT)
            }
        }

        override fun onLockscreenLayoutChanged(extras: Bundle) {

            val left = extras.getFloat("wallpaperFocalAreaLeft", extras.getFloat("left", -1f))
            val top = extras.getFloat("wallpaperFocalAreaTop", extras.getFloat("top", -1f))
            val right = extras.getFloat("wallpaperFocalAreaRight", extras.getFloat("right", -1f))
            val bottom = extras.getFloat("wallpaperFocalAreaBottom", extras.getFloat("bottom", -1f))
            if (left >= 0f && top >= 0f && right > left && bottom > top) {
                lockscreenBounds = RectF(left, top, right, bottom)
            } else {
                lockscreenBounds = null
            }
            updateShapeEffectState()
        }

        private fun readSettings() {
            val content = wallpaperDescription?.content
            if (isPreview() && content != null) {

                currentShapeIndex = content.getInt("portrait_shape", 0)
                currentColor = content.getInt("portrait_color", 0xFF2196F3.toInt())
                currentLstar =
                    content.getDouble("portrait_lstar", DEFAULT_LSTAR.toDouble()).toFloat()
            } else {

                val cr = context.contentResolver
                currentShapeIndex = Settings.Secure.getInt(cr, SETTING_SHAPE, 0)
                currentColor = Settings.Secure.getInt(cr, SETTING_COLOR, 0xFF2196F3.toInt())
                currentLstar = Settings.Secure.getFloat(cr, SETTING_LSTAR, DEFAULT_LSTAR)
            }
        }

        private fun getShape(): Shape {
            return when (currentShapeIndex) {
                0 -> Shape.PILL
                1 -> Shape.SQUARE
                2 -> Shape.ARCH
                3 -> Shape.COOKIE_4_SIDED
                4 -> Shape.COOKIE_6_SIDED
                else -> Shape.PILL
            }
        }

        private fun getRotationDirection(oldShape: Shape, newShape: Shape): RotationDirection {
            if (oldShape == Shape.NONE || newShape == Shape.NONE) return RotationDirection.NONE
            val oldIdx = Shape.AVAILABLE_SHAPES.indexOf(oldShape)
            val newIdx = Shape.AVAILABLE_SHAPES.indexOf(newShape)
            return if (newIdx > oldIdx) RotationDirection.CLOCKWISE
            else RotationDirection.COUNTERCLOCKWISE
        }

        private fun loadForegroundIfNeeded() {
            if (foregroundLoadJob?.isActive == true || segmentationJob?.isActive == true) return
            if (photoBitmap == null || foregroundBitmap == null || positionModel == null) {
                loadPhotoAndSegment()
            }
        }

        private fun loadPhotoAndSegment() {
            foregroundLoadJob?.cancel()
            segmentationJob?.cancel()
            segmentationJob = null
            foregroundLoadJob =
                scope.launch {
                    try {
                        val photo =
                            loadPhotoFromStorage()
                                ?: run {
                                    Log.w(TAG, "No photo found in DE storage")
                                    activePhotoToken = null
                                    photoBitmap = null
                                    clearForegroundState()
                                    return@launch
                                }
                        val photoBitmap = photo.first
                        val photoCacheKey = photo.second
                        val token =
                            PhotoToken(
                                photoCacheKey,
                                Size(photoBitmap.width, photoBitmap.height),
                            )
                        activePhotoToken = token
                        this@PortraitEngine.photoBitmap = photoBitmap
                        clearForegroundState()
                        notifyWallpaperColorsChanged()
                        updateShapeEffectState()

                        val cachedFg = loadForegroundFromStorage(token)
                        if (!isActivePhoto(token)) return@launch
                        if (cachedFg != null) {
                            foregroundBitmap = cachedFg
                            publishDepthMask(cachedFg)
                            positionImage(cachedFg)
                            if (!isActivePhoto(token)) return@launch
                            updateShapeEffectState()
                            return@launch
                        }

                        runSegmentation(photoBitmap, token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading photo", e)
                    }
                }
        }

        private fun runSegmentation(bitmap: Bitmap, token: PhotoToken) {
            segmentationJob?.cancel()
            segmentationJob =
                scope.launch {
                    try {
                        val fg =
                            withContext(Dispatchers.IO) {
                                TFLiteImageSegmenter(context).getForegroundImage(bitmap)
                            }
                        if (!isActivePhoto(token)) return@launch
                        foregroundBitmap = fg

                        saveForegroundToStorage(fg, token.cacheKey)
                        if (!isActivePhoto(token)) return@launch

                        publishDepthMask(fg)

                        positionImage(fg)
                        if (!isActivePhoto(token)) return@launch

                        updateShapeEffectState()
                        Log.d(TAG, "Segmentation complete, foreground: ${fg.width}x${fg.height}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Segmentation failed", e)
                    }
                }
        }

        private fun clearForegroundState() {
            foregroundBitmap = null
            positionModel = null
            cachedDepthPathData = null
            updateDepthState(false)
        }

        private fun isActivePhoto(token: PhotoToken): Boolean {
            return activePhotoToken == token
        }

        private fun publishDepthMask(fg: Bitmap) {
            try {
                cachedDepthPathData = DepthMaskUtils.extractSubjectPath(fg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract depth path", e)
                cachedDepthPathData = null
            }
        }

        private fun updateDepthState(showDepth: Boolean) {
            if (isPreview()) return

            val wantActive = showDepth && cachedDepthPathData != null
            val nextPath = if (wantActive) cachedDepthPathData else null
            if (!wantActive && ownsDepthState && hasForeignDepthState()) {
                ownsDepthState = false
                depthMaskActive = false
                activeDepthPathData = null
                return
            }

            if (
                ownsDepthState &&
                    wantActive == depthMaskActive &&
                    nextPath == activeDepthPathData
            ) {
                return
            }

            ownsDepthState = true
            depthMaskActive = wantActive
            activeDepthPathData = nextPath
            try {
                Settings.Secure.putString(
                    context.contentResolver,
                    SETTING_DEPTH_MASK,
                    nextPath,
                )
                Settings.Secure.putString(context.contentResolver, SETTING_DEPTH_BOUNDS, null)
            } catch (_: Exception) {}
        }

        private fun hasForeignDepthState(): Boolean {
            return Settings.Secure.getString(context.contentResolver, SETTING_DEPTH_MASK) !=
                activeDepthPathData
        }

        private suspend fun positionImage(fg: Bitmap) {
            positionModel =
                withContext(Dispatchers.Default) {
                    val positioner = ForegroundPositionerImpl(shapePositionHelper)
                    positioner.positionImage(fg)
                }
            Log.d(TAG, "Position model: $positionModel")
        }

        private suspend fun loadPhotoFromStorage(): Pair<Bitmap, String>? =
            withContext(Dispatchers.IO) {
                val deContext = context.createDeviceProtectedStorageContext()
                val file = File(deContext.filesDir, PHOTO_FILE)
                if (!file.exists()) return@withContext null
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
                Pair(bitmap, createForegroundCacheKey(file))
            }

        private suspend fun loadForegroundFromStorage(token: PhotoToken): Bitmap? =
            withContext(Dispatchers.IO) {
                val deContext = context.createDeviceProtectedStorageContext()
                val file = File(deContext.filesDir, FOREGROUND_FILE)
                val metaFile = File(deContext.filesDir, FOREGROUND_META_FILE)
                val legacyMetaFile = File(deContext.filesDir, LEGACY_FOREGROUND_META_FILE)
                if (!file.exists()) return@withContext null
                val storedKey =
                    if (metaFile.exists()) runCatching { metaFile.readText() }.getOrNull()
                    else null
                if (storedKey != token.cacheKey) {
                    deleteForegroundCache(file, metaFile, legacyMetaFile)
                    return@withContext null
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run {
                    deleteForegroundCache(file, metaFile, legacyMetaFile)
                    return@withContext null
                }
                if (
                    bitmap.width != token.photoSize.width ||
                        bitmap.height != token.photoSize.height
                ) {
                    bitmap.recycle()
                    deleteForegroundCache(file, metaFile, legacyMetaFile)
                    return@withContext null
                }
                bitmap
            }

        private fun deleteForegroundCache(file: File, metaFile: File, legacyMetaFile: File) {
            file.delete()
            metaFile.delete()
            legacyMetaFile.delete()
        }

        private suspend fun saveForegroundToStorage(bitmap: Bitmap, photoCacheKey: String) =
            withContext(Dispatchers.IO) {
                try {
                    val deContext = context.createDeviceProtectedStorageContext()
                    val file = File(deContext.filesDir, FOREGROUND_FILE)
                    file.outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    File(deContext.filesDir, FOREGROUND_META_FILE).writeText(photoCacheKey)
                    File(deContext.filesDir, LEGACY_FOREGROUND_META_FILE).delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save foreground", e)
                }
            }

        private fun createForegroundCacheKey(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") {
                ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            }
            return "$FOREGROUND_CACHE_VERSION:${file.length()}:${file.lastModified()}:$hash"
        }

        private fun updateShapeEffectState() {
            val bitmap = photoBitmap ?: return
            val size = surfaceSize ?: return

            val shape = getShape()
            val fg = foregroundBitmap
            val pm = positionModel
            val segModel =
                if (fg != null) SegmentationModel.Loaded(fg) else SegmentationModel.Unloaded

            val rotationDir = getRotationDirection(previousShape, shape)
            previousShape = shape

            updateDepthState(true)

            val shouldShowShape = shape != Shape.NONE && (isOnLockscreen || isPreview())
            if (!shouldShowShape) {

                val crop =
                    if (pm != null) {
                        pm.getCropWithoutShape(
                            size,
                            Size(bitmap.width, bitmap.height),
                            shapePositionHelper,
                            isRtl,
                        )
                    } else {
                        CropHelper.centerAlign(
                            size,
                            Size(bitmap.width, bitmap.height),
                            true,
                            isRtl,
                        )
                    }

                val state =
                    ShapeEffectState.WithoutShape(
                        bitmap = bitmap,
                        crop = crop,
                        surfaceSize = size,
                        xOffset = xOffset,
                    )
                shapeRenderer.handleShapeRenderModelChanged(state)
            } else {

                val bitmapSize = Size(bitmap.width, bitmap.height)
                val overlap = pm?.foregroundOverlap ?: 0f

                val displayShapeBounds =
                    computeShapeBoundsForDisplay(size, overlap, lockscreenBounds)

                val crop =
                    pm?.getCropWithShape(size, bitmapSize, displayShapeBounds)
                        ?: CropHelper.centerAlign(size, bitmapSize)

                val state =
                    ShapeEffectState.WithShape(
                        bitmap = bitmap,
                        crop = crop,
                        surfaceSize = size,
                        segmentationModel = segModel,
                        shape = shape,
                        shapeChipColor = currentColor,
                        shapeColorSliderValue = currentLstar,
                        shouldDrawForeground = fg != null && pm?.shouldDrawForeground == true,
                        shapeBounds = displayShapeBounds,
                        normalizedCutLine = pm?.normalizedCutLine,
                        rotationDirection = rotationDir,
                    )
                shapeRenderer.handleShapeRenderModelChanged(state)
            }

            requestRender()
        }

        private fun computeShapeBoundsForDisplay(
            surfaceSize: Size,
            foregroundOverlap: Float,
            lsBounds: RectF?,
        ): RectF {
            if (lsBounds == null) {
                return shapePositionHelper.defaultShapeBounds(surfaceSize, foregroundOverlap)
            }

            val adjustedBounds = RectF(lsBounds)
            adjustedBounds.top = lsBounds.top + shapePositionHelper.topMargin

            val aspectRatio = 1f / (foregroundOverlap + 1f)
            val centeredRect = RectUtils.largestCenteredRect(adjustedBounds, aspectRatio)
            val maxWidth = shapePositionHelper.maxBoundsWidth(surfaceSize)

            val minWidth: Float
            if (shapePositionHelper.isFoldable || !shapePositionHelper.isLargeScreen) {
                val aspect = surfaceSize.width.toFloat() / surfaceSize.height
                if (aspect < 0.75f || aspect > 1.333f) {

                    minWidth =
                        0.9f *
                            ((kotlin.math.min(surfaceSize.width, surfaceSize.height) -
                                shapePositionHelper.horizontalMargin * 2f) /
                                shapePositionHelper.maxWallpaperScale)
                } else {

                    minWidth = kotlin.math.min(surfaceSize.width, surfaceSize.height) * 0.5f
                }
            } else {

                minWidth = kotlin.math.min(surfaceSize.width, surfaceSize.height) * 0.4f
            }

            val coercedWidth = centeredRect.width().coerceIn(minWidth, maxWidth)
            val shapeRect = RectF(0f, 0f, coercedWidth, coercedWidth / aspectRatio)

            val surfaceRect =
                RectF(0f, 0f, surfaceSize.width.toFloat(), surfaceSize.height.toFloat())
            shapeRect.offset(
                surfaceRect.left + (surfaceRect.width() - shapeRect.width()) / 2f,
                surfaceRect.top + (surfaceRect.height() - shapeRect.height()) / 2f,
            )

            if (adjustedBounds.top <= shapeRect.top || adjustedBounds.bottom < shapeRect.bottom) {
                val shift =
                    kotlin.math.min(
                        adjustedBounds.top - shapeRect.top,
                        adjustedBounds.bottom - shapeRect.bottom,
                    )
                shapeRect.offset(0f, shift)
            }

            return RectUtils.bottomSquare(shapeRect)
        }

        override fun computeWallpaperColors(): WallpaperColors? {

            val photoBmp = photoBitmap ?: return null
            return WallpaperColors.fromBitmap(photoBmp)
        }
    }
}
