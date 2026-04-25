/*
 * Copyright 2022 The Android Open Source Project
 * Copyright 2024 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils as AndroidGLUtils
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.util.Pair
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.EMPTY_ATTRIBS
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.InputFormat
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.NO_OUTPUT_SURFACE
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.PIXEL_STRIDE
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.Program2D
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.SamplerShaderProgram
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.VERTEX_BUF
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkEglErrorOrLog
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkEglErrorOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkGlErrorOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkGlThreadOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkInitializedOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.chooseSurfaceAttrib
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createFloatBuffer
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createPBufferSurface
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createPrograms
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createWindowSurface
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.deleteFbo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.deleteTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.generateFbo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.generateTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.getSurfaceSize
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.glVersionNumber
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.loadShader
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GraphicDeviceInfo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.OutputSurface
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.logger.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10

/**
 * OpenGLRenderer renders texture image to the output surface.
 *
 *
 * OpenGLRenderer's methods must run on the same thread, so called GL thread. The GL thread is
 * locked as the thread running the [.init] method, otherwise an
 * [IllegalStateException] will be thrown when other methods are called.
 */
@WorkerThread
class OpenGlRenderer {
    protected val mInitialized: AtomicBoolean = AtomicBoolean(false)
    protected val mOutputSurfaceMap: MutableMap<Surface, OutputSurface> =
        HashMap()
    protected var mGlThread: Thread? = null
    protected var mEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    protected var mEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    protected var mSurfaceAttrib: IntArray = EMPTY_ATTRIBS
    protected var mEglConfig: EGLConfig? = null
    protected var mTempSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    protected var mCurrentSurface: Surface? = null
    protected var mProgramHandles: Map<InputFormat, Program2D> = emptyMap()
    protected var mCurrentProgram: Program2D? = null
    protected var mCurrentInputformat: InputFormat = InputFormat.UNKNOWN

    private var mExternalTextureId = -1

    // ── Overlay support ─────────────────────────────────────────────────────
    /**
     * Pending list of layer bitmaps to upload, or `null` when there is no pending update.
     * An empty list means "clear all layers".
     */
    private val mPendingLayersUpdate = AtomicReference<List<Bitmap>?>(null)

    /** Currently active static overlay layers (uploaded GL_TEXTURE_2D textures). */
    private var mStaticLayers: List<StaticLayer> = emptyList()

    // Overlay GL program handle and attribute/uniform locations.
    private var mOverlayProgramHandle = -1
    private var mOverlayPositionLoc = -1
    private var mOverlayTexCoordLoc = -1
    private var mOverlayTransMatrixLoc = -1
    private var mOverlayTextureLoc = -1

    // ── Ticker support ──────────────────────────────────────────────────────
    private val mPendingTickerUpdate = AtomicReference<OverlayUpdate?>(null)
    private var mTickerTextureId = -1
    private var mTickerWidth = 0
    private var mTickerHeight = 0
    /** Current horizontal center of the ticker in clip space; starts off-screen right. */
    private var mTickerX = TICKER_START_X
    // ── End ticker support ──────────────────────────────────────────────────

    // ── Link image corner overlays ───────────────────────────────────────────
    private val mPendingLinkUpdate = AtomicReference<LinkBitmaps?>(null)
    /** Textures for the 3 corner images: [0]=top-right, [1]=bottom-left, [2]=bottom-right. */
    private val mLinkLayers: Array<StaticLayer?> = arrayOfNulls(3)
    // ── End link image support ───────────────────────────────────────────────

    /**
     * Y-flipped texture coordinates for 2D bitmaps.
     * Android Bitmap row 0 (top) maps to GL texture t=0 (bottom), so we flip the t-axis here
     * to avoid an upside-down overlay.
     */
    private val mOverlayTexBuf = createFloatBuffer(
        floatArrayOf(
            0.0f, 1.0f,  // vertex 0 (bottom-left screen)  → bitmap top-left
            1.0f, 1.0f,  // vertex 1 (bottom-right screen) → bitmap top-right
            0.0f, 0.0f,  // vertex 2 (top-left screen)     → bitmap bottom-left
            1.0f, 0.0f,  // vertex 3 (top-right screen)    → bitmap bottom-right
        )
    )
    // ── End overlay support ──────────────────────────────────────────────────

    /**
     * Initializes the OpenGLRenderer
     *
     *
     * This is equivalent to calling [.init] without providing any
     * shader overrides. Default shaders will be used for the dynamic range specified.
     */
    fun init(dynamicRange: DynamicRangeProfile): GraphicDeviceInfo {
        return init(dynamicRange, emptyMap<InputFormat?, ShaderProvider>())
    }

    /**
     * Initializes the OpenGLRenderer
     *
     *
     * Initialization must be done before calling other methods, otherwise an
     * [IllegalStateException] will be thrown. Following methods must run on the same
     * thread as this method, so called GL thread, otherwise an [IllegalStateException]
     * will be thrown.
     *
     * @param dynamicRange    the dynamic range used to select default shaders.
     * @param shaderOverrides specific shader overrides for fragment shaders
     * per [InputFormat].
     * @return Info about the initialized graphics device.
     * @throws IllegalStateException    if the renderer is already initialized or failed to be
     * initialized.
     * @throws IllegalArgumentException if the ShaderProvider fails to create shader or provides
     * invalid shader string.
     */
    fun init(
        dynamicRange: DynamicRangeProfile,
        shaderOverrides: Map<InputFormat?, ShaderProvider?>
    ): GraphicDeviceInfo {
        checkInitializedOrThrow(mInitialized, false)
        val infoBuilder = GraphicDeviceInfo.Builder()
        try {
            var dynamicRangeCorrected = dynamicRange
            if (dynamicRange.isHdr) {
                val extensions = getExtensionsBeforeInitialized(dynamicRange)
                val glExtensions = requireNotNull(extensions.first)
                val eglExtensions = requireNotNull(extensions.second)
                if (!glExtensions.contains("GL_EXT_YUV_target")) {
                    Logger.w(TAG, "Device does not support GL_EXT_YUV_target. Fallback to SDR.")
                    dynamicRangeCorrected = DynamicRangeProfile.sdr
                }
                mSurfaceAttrib = chooseSurfaceAttrib(eglExtensions, dynamicRangeCorrected)
                infoBuilder.setGlExtensions(glExtensions)
                infoBuilder.setEglExtensions(eglExtensions)
            }
            createEglContext(dynamicRangeCorrected, infoBuilder)
            createTempSurface()
            makeCurrent(mTempSurface)
            infoBuilder.setGlVersion(glVersionNumber)
            mProgramHandles = createPrograms(dynamicRangeCorrected, shaderOverrides)
            mExternalTextureId = createTexture()
            useAndConfigureProgramWithTexture(mExternalTextureId)
            initOverlayProgram()
        } catch (e: IllegalStateException) {
            releaseInternal()
            throw e
        } catch (e: IllegalArgumentException) {
            releaseInternal()
            throw e
        }
        mGlThread = Thread.currentThread()
        mInitialized.set(true)
        return infoBuilder.build()
    }

    // ── Public overlay API ────────────────────────────────────────────────────

    /**
     * Replaces the static overlay with a list of independently-positioned bitmaps stacked
     * vertically at the top-left corner. Each bitmap gets its own GL texture unit (units 1–3).
     * An empty list clears the overlay.
     *
     * Thread-safe; upload happens on the GL thread during the next [render] call.
     */
    fun setOverlayBitmaps(bitmaps: List<Bitmap>) {
        mPendingLayersUpdate.set(bitmaps)
    }

    /**
     * Convenience wrapper: treats [bitmap] as a single overlay layer.
     * Pass `null` to clear the overlay.
     */
    fun setOverlayBitmap(bitmap: Bitmap?) {
        setOverlayBitmaps(listOfNotNull(bitmap))
    }

    /**
     * Sets or clears the ticker bitmap that will be scrolled right-to-left across the bottom
     * of every rendered frame.
     *
     * This method is thread-safe and may be called from any thread.
     */
    fun setTickerBitmap(bitmap: Bitmap?) {
        mPendingTickerUpdate.set(
            if (bitmap != null) OverlayUpdate.Set(bitmap) else OverlayUpdate.Clear
        )
    }

    /**
     * Sets corner image overlays from bitmaps.
     * - [link1] → top-right corner
     * - [link2] → bottom-left corner
     * - [link3] → bottom-right corner
     *
     * Pass `null` for any slot to clear that corner. Thread-safe; upload happens on the GL thread.
     */
    fun setLinkBitmaps(link1: Bitmap?, link2: Bitmap?, link3: Bitmap?) {
        mPendingLinkUpdate.set(LinkBitmaps(link1, link2, link3))
    }

    // ── Private overlay helpers ───────────────────────────────────────────────

    /**
     * Compiles and links the overlay GL program.  Must be called on the GL thread during [init].
     */
    private fun initOverlayProgram() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, OVERLAY_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, OVERLAY_FRAGMENT_SHADER)

        val program = GLES20.glCreateProgram()
        checkGlErrorOrThrow("glCreateProgram (overlay)")
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "Could not link overlay program: " + GLES20.glGetProgramInfoLog(program)
        }

        // Shaders are no longer needed once linked.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        mOverlayProgramHandle = program
        mOverlayPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        mOverlayTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        mOverlayTransMatrixLoc = GLES20.glGetUniformLocation(program, "uTransMatrix")
        mOverlayTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
    }

    /**
     * Checks for pending overlay/ticker updates, draws each static layer stacked vertically at
     * the top-left, then link images at their corners, then the scrolling ticker at the bottom.
     * Must be called on the GL thread, inside [render], after the main draw call.
     */
    private fun renderOverlay(outputSurface: OutputSurface) {
        // Apply pending updates (all processed before any drawing).
        mPendingLayersUpdate.getAndSet(null)?.let { uploadLayerBitmaps(it) }
        mPendingLinkUpdate.getAndSet(null)?.let { uploadLinkBitmaps(it) }

        val tickerUpdate = mPendingTickerUpdate.getAndSet(null)
        when (tickerUpdate) {
            is OverlayUpdate.Set -> {
                uploadOverlayBitmap(update = tickerUpdate, ticker = true)
                // Intentionally do NOT reset mTickerX — keep position as scrolling continues.
            }
            is OverlayUpdate.Clear -> releaseTickerTexture()
            null -> {}
        }

        val hasLayers = mStaticLayers.isNotEmpty()
        val hasLinks = mLinkLayers.any { it != null }
        val hasTicker = mTickerTextureId != -1
        if (!hasLayers && !hasLinks && !hasTicker) return

        val surfaceW = outputSurface.viewPortRect.width().toFloat()
        val surfaceH = outputSurface.viewPortRect.height().toFloat()
        if (surfaceW <= 0f || surfaceH <= 0f) return

        // Ticker geometry computed once — needed for link Y positioning below.
        // scaleX and scaleY are computed independently (bitmap px / surface px) so the ticker
        // renders at 1:1 pixel density on the output surface, avoiding horizontal upscaling blur.
        val tickerScaleY = if (hasTicker && mTickerHeight > 0) mTickerHeight.toFloat() / surfaceH else 0f
        val tickerScaleX = if (hasTicker && mTickerWidth > 0) mTickerWidth.toFloat() / surfaceW else 0f
        // Top edge of the ticker bar in clip space.
        val tickerTopEdge = TICKER_Y + tickerScaleY

        try {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(mOverlayProgramHandle)

            GLES20.glEnableVertexAttribArray(mOverlayPositionLoc)
            GLES20.glVertexAttribPointer(mOverlayPositionLoc, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUF)
            GLES20.glEnableVertexAttribArray(mOverlayTexCoordLoc)
            GLES20.glVertexAttribPointer(mOverlayTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, mOverlayTexBuf)

            // ── 1. Static text/score layers — top-left, scaled to 80% of natural pixel size ──
            if (hasLayers) {
                val marginX = OVERLAY_MARGIN_PX * 2f / surfaceW
                val marginY = OVERLAY_MARGIN_PX * 2f / surfaceH
                var clipY = 1f - marginY

                mStaticLayers.forEachIndexed { i, layer ->
                    val scaleX = layer.widthPx / surfaceW * OVERLAY_SCALE
                    val scaleY = layer.heightPx / surfaceH * OVERLAY_SCALE
                    val tx = -1f + scaleX + marginX
                    val ty = clipY - scaleY

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1 + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureId)
                    GLES20.glUniform1i(mOverlayTextureLoc, 1 + i)

                    val m = FloatArray(16)
                    Matrix.setIdentityM(m, 0)
                    m[0] = scaleX; m[5] = scaleY; m[12] = tx; m[13] = ty
                    GLES20.glUniformMatrix4fv(mOverlayTransMatrixLoc, 1, false, m, 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    clipY -= 2f * scaleY
                }
            }

            // ── 2. Link corner images ──
            // link1 → top-right corner.
            // link2 → bottom-left, link3 → bottom-right; both sit just above the ticker.
            if (hasLinks) {
                mLinkLayers.forEachIndexed { i, layer ->
                    if (layer == null) return@forEachIndexed

                    // Preserve the image's original pixel aspect ratio by deriving scaleY from
                    // scaleX in SCREEN pixels, then converting back to clip space.
                    // scaleX (clip) = desired_display_width_px / surfaceW
                    // scaleY (clip) = desired_display_height_px / surfaceH
                    //               = (desired_display_width_px / imageAspect) / surfaceH
                    //               = scaleX * surfaceW / (surfaceH * imageAspect)
                    val imageAspect = layer.widthPx.toFloat() / layer.heightPx.toFloat()
                    val scaleX = LINK_HALF_EXTENT
                    val scaleY = scaleX * surfaceW / (surfaceH * imageAspect)

                    val tx: Float
                    val ty: Float
                    when (i) {
                        0 -> {  // link1 — top-right corner
                            tx = 1f - scaleX - LINK_EDGE_MARGIN
                            ty = 1f - scaleY - LINK_EDGE_MARGIN
                        }
                        1 -> {  // link2 — bottom-left, above ticker, left edge padded
                            tx = -1f + scaleX + LINK_EDGE_MARGIN
                            ty = tickerTopEdge + LINK_TICKER_GAP + scaleY
                        }
                        else -> {  // link3 — bottom-right, above ticker, right edge padded
                            tx = 1f - scaleX - LINK_EDGE_MARGIN
                            ty = tickerTopEdge + LINK_TICKER_GAP + scaleY
                        }
                    }

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LINK_TEXTURE_BASE + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureId)
                    GLES20.glUniform1i(mOverlayTextureLoc, LINK_TEXTURE_BASE + i)

                    val m = FloatArray(16)
                    Matrix.setIdentityM(m, 0)
                    m[0] = scaleX; m[5] = scaleY; m[12] = tx; m[13] = ty
                    GLES20.glUniformMatrix4fv(mOverlayTransMatrixLoc, 1, false, m, 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }

            // ── 3. Ticker — scrolls right-to-left at the very bottom each frame ──
            if (hasTicker && tickerScaleY > 0f) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TICKER_TEXTURE_INDEX)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTickerTextureId)
                GLES20.glUniform1i(mOverlayTextureLoc, TICKER_TEXTURE_INDEX)

                mTickerX -= TICKER_SPEED
                // Loop: when fully off the left edge, reappear from the right.
                if (mTickerX < -1f - tickerScaleX) mTickerX = 1f + tickerScaleX

                val m = FloatArray(16)
                Matrix.setIdentityM(m, 0)
                m[0] = tickerScaleX; m[5] = tickerScaleY; m[12] = mTickerX; m[13] = TICKER_Y
                GLES20.glUniformMatrix4fv(mOverlayTransMatrixLoc, 1, false, m, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } finally {
            GLES20.glDisableVertexAttribArray(mOverlayPositionLoc)
            GLES20.glDisableVertexAttribArray(mOverlayTexCoordLoc)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            activateExternalTexture(mExternalTextureId)
            mCurrentProgram?.use()
        }
    }

    /**
     * Uploads each bitmap in [bitmaps] to successive GL texture units starting at GL_TEXTURE1.
     * Releases any previously active static layers first. Must be called on the GL thread.
     */
    private fun uploadLayerBitmaps(bitmaps: List<Bitmap>) {
        releaseStaticLayers()
        mStaticLayers = bitmaps.take(MAX_STATIC_LAYERS).mapIndexed { i, bitmap ->
            val texUnit = GLES20.GL_TEXTURE1 + i
            GLES20.glActiveTexture(texUnit)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
            val texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            AndroidGLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            checkGlErrorOrThrow("texImage2D (layer $i)")
            StaticLayer(texId, bitmap.width, bitmap.height)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        activateExternalTexture(mExternalTextureId)
    }

    /** Deletes all active static layer textures. Must be called on the GL thread. */
    private fun releaseStaticLayers() {
        if (mStaticLayers.isNotEmpty()) {
            val ids = mStaticLayers.map { it.textureId }.toIntArray()
            GLES20.glDeleteTextures(ids.size, ids, 0)
            mStaticLayers = emptyList()
        }
    }

    /**
     * Uploads [update]'s bitmap to the ticker GL_TEXTURE_2D texture (unit [TICKER_TEXTURE_INDEX]).
     * Must be called on the GL thread.
     */
    private fun uploadOverlayBitmap(update: OverlayUpdate.Set, ticker: Boolean) {
        val bitmap = update.bitmap
        val unit = if (ticker) GLES20.GL_TEXTURE0 + TICKER_TEXTURE_INDEX else GLES20.GL_TEXTURE1

        if (ticker) {
            if (mTickerTextureId == -1) {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0); mTickerTextureId = t[0]
            }
        }

        val texId = if (ticker) mTickerTextureId else return
        GLES20.glActiveTexture(unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        AndroidGLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGlErrorOrThrow("texImage2D (ticker)")

        mTickerWidth = bitmap.width; mTickerHeight = bitmap.height

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        activateExternalTexture(mExternalTextureId)
    }

    /**
     * Uploads link bitmaps to their dedicated texture units (GL_TEXTURE5–7).
     * A null slot releases the existing texture for that corner. Must be called on the GL thread.
     */
    private fun uploadLinkBitmaps(links: LinkBitmaps) {
        val bitmaps = listOf(links.link1, links.link2, links.link3)
        bitmaps.forEachIndexed { i, bitmap ->
            val existing = mLinkLayers[i]
            if (bitmap == null) {
                if (existing != null) {
                    GLES20.glDeleteTextures(1, intArrayOf(existing.textureId), 0)
                    mLinkLayers[i] = null
                }
            } else {
                if (existing != null) {
                    GLES20.glDeleteTextures(1, intArrayOf(existing.textureId), 0)
                }
                val texUnit = GLES20.GL_TEXTURE0 + LINK_TEXTURE_BASE + i
                GLES20.glActiveTexture(texUnit)
                val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
                val texId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                AndroidGLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                mLinkLayers[i] = StaticLayer(texId, bitmap.width, bitmap.height)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        activateExternalTexture(mExternalTextureId)
    }

    /** Deletes all active link corner textures. Must be called on the GL thread. */
    private fun releaseLinkLayers() {
        mLinkLayers.forEachIndexed { i, layer ->
            if (layer != null) {
                GLES20.glDeleteTextures(1, intArrayOf(layer.textureId), 0)
                mLinkLayers[i] = null
            }
        }
    }

    /** Deletes the ticker texture. Must be called on the GL thread. */
    private fun releaseTickerTexture() {
        if (mTickerTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(mTickerTextureId), 0)
            mTickerTextureId = -1; mTickerWidth = 0; mTickerHeight = 0
        }
    }

    // ── End overlay helpers ───────────────────────────────────────────────────

    /**
     * Releases the OpenGLRenderer
     *
     * @throws IllegalStateException if the caller doesn't run on the GL thread.
     */
    fun release() {
        if (!mInitialized.getAndSet(false)) {
            return
        }
        checkGlThreadOrThrow(mGlThread)
        releaseInternal()
    }

    /**
     * Register the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun registerOutputSurface(surface: Surface) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (!mOutputSurfaceMap.containsKey(surface)) {
            mOutputSurfaceMap[surface] = NO_OUTPUT_SURFACE
        }
    }

    /**
     * Unregister the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun unregisterOutputSurface(surface: Surface) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        removeOutputSurfaceInternal(surface, true)
    }

    val textureName: Int
        /**
         * Gets the texture name.
         *
         * @return the texture name
         * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
         * on the GL thread.
         */
        get() {
            checkInitializedOrThrow(mInitialized, true)
            checkGlThreadOrThrow(mGlThread)

            return mExternalTextureId
        }

    /**
     * Sets the input format.
     *
     *
     * This will ensure the correct sampler is used for the input.
     *
     * @param inputFormat The input format for the input texture.
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun setInputFormat(inputFormat: InputFormat) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (mCurrentInputformat !== inputFormat) {
            mCurrentInputformat = inputFormat
            useAndConfigureProgramWithTexture(mExternalTextureId)
        }
    }

    private fun activateExternalTexture(externalTextureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        checkGlErrorOrThrow("glBindTexture")
    }

    /**
     * Renders the texture image to the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized, the caller doesn't run
     * on the GL thread or the surface is not registered by
     * [.registerOutputSurface].
     */
    fun render(
        timestampNs: Long,
        textureTransform: FloatArray,
        surface: Surface
    ) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        var outputSurface: OutputSurface? = getOutSurfaceOrThrow(surface)

        // Workaround situations that out surface is failed to create or needs to be recreated.
        if (outputSurface === NO_OUTPUT_SURFACE) {
            outputSurface = createOutputSurfaceInternal(surface)
            if (outputSurface == null) {
                return
            }

            mOutputSurfaceMap[surface] = outputSurface
        }

        requireNotNull(outputSurface)

        // Set output surface.
        if (surface !== mCurrentSurface) {
            makeCurrent(outputSurface.eglSurface)
            mCurrentSurface = surface
            GLES20.glViewport(
                outputSurface.viewPortRect.left,
                outputSurface.viewPortRect.top,
                outputSurface.viewPortRect.width(),
                outputSurface.viewPortRect.height()
            )
            GLES20.glScissor(
                outputSurface.viewPortRect.left,
                outputSurface.viewPortRect.top,
                outputSurface.viewPortRect.width(),
                outputSurface.viewPortRect.height()
            )
        }


        // TODO(b/245855601): Upload the matrix to GPU when textureTransform is changed.
        val program: Program2D = requireNotNull(mCurrentProgram)
        if (program is SamplerShaderProgram) {
            // Copy the texture transformation matrix over.
            program.updateTextureMatrix(textureTransform)
        }

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        // Draw text/image overlay (if any) on top of the frame.
        // Wrapped in try-catch so that any GL error in the overlay path never prevents
        // eglPresentationTimeANDROID / eglSwapBuffers from running — dropped frames would
        // starve the video encoder and eventually cause the RTMP server to close the connection.
        try {
            renderOverlay(outputSurface)
        } catch (e: Exception) {
            Logger.e(TAG, "Overlay rendering failed, skipping overlay for this frame", e)
        }

        // Set timestamp
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, outputSurface.eglSurface, timestampNs)

        // Swap buffer
        if (!EGL14.eglSwapBuffers(mEglDisplay, outputSurface.eglSurface)) {
            Logger.w(
                TAG, "Failed to swap buffers with EGL error: 0x" + Integer.toHexString(
                    EGL14.eglGetError()
                )
            )
            removeOutputSurfaceInternal(surface, false)
        }
    }

    /**
     * Takes a snapshot of the current external texture and returns a Bitmap.
     *
     * @param size             the size of the output [Bitmap].
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    fun snapshot(size: Size, textureTransform: FloatArray): Bitmap {
        // Allocate buffer.
        val byteBuffer = ByteBuffer.allocateDirect(
            size.width * size.height * PIXEL_STRIDE
        )

        // Take a snapshot.
        snapshot(byteBuffer, size, textureTransform)
        byteBuffer.rewind()

        // Create a Bitmap and copy the bytes over.
        val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(byteBuffer)
        return bitmap
    }

    /**
     * Takes a snapshot of the current external texture and stores it in the given byte buffer.
     *
     *
     *  The image is stored as RGBA with pixel stride of 4 bytes and row stride of width * 4
     * bytes.
     *
     * @param byteBuffer       the byte buffer to store the snapshot.
     * @param size             the size of the output image.
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    private fun snapshot(
        byteBuffer: ByteBuffer, size: Size,
        textureTransform: FloatArray
    ) {
        check(byteBuffer.capacity() == size.width * size.height * 4) {
            "ByteBuffer capacity is not equal to width * height * 4."
        }
        check(byteBuffer.isDirect) { "ByteBuffer is not direct." }

        // Create and initialize intermediate texture.
        val texture: Int = generateTexture()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        checkGlErrorOrThrow("glBindTexture")
        // Configure the texture.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, size.width,
            size.height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null
        )
        checkGlErrorOrThrow("glTexImage2D")
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )

        // Create FBO.
        val fbo: Int = generateFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        checkGlErrorOrThrow("glBindFramebuffer")

        // Attach the intermediate texture to the FBO
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        checkGlErrorOrThrow("glFramebufferTexture2D")

        // Bind external texture (camera output).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mExternalTextureId)
        checkGlErrorOrThrow("glBindTexture")

        // Set scissor and viewport.
        mCurrentSurface = null
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glScissor(0, 0, size.width, size.height)

        val program: Program2D = requireNotNull(mCurrentProgram)
        if (program is SamplerShaderProgram) {
            // Upload transform matrix.
            program.updateTextureMatrix(textureTransform)
        }

        // Draw the external texture to the intermediate texture.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        // Read the pixels from the framebuffer
        GLES20.glReadPixels(
            0, 0, size.width, size.height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer
        )
        checkGlErrorOrThrow("glReadPixels")

        // Clean up
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        deleteTexture(texture)
        deleteFbo(fbo)
        // Set the external texture to be active.
        activateExternalTexture(mExternalTextureId)
    }

    // Returns a pair of GL extension (first) and EGL extension (second) strings.
    private fun getExtensionsBeforeInitialized(
        dynamicRangeToInitialize: DynamicRangeProfile
    ): Pair<String, String> {
        checkInitializedOrThrow(mInitialized, false)
        try {
            createEglContext(dynamicRangeToInitialize,  /*infoBuilder=*/null)
            createTempSurface()
            makeCurrent(mTempSurface)
            // eglMakeCurrent() has to be called before checking GL_EXTENSIONS.
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            val eglExtensions = EGL14.eglQueryString(mEglDisplay, EGL14.EGL_EXTENSIONS)
            return Pair(
                glExtensions ?: "", eglExtensions ?: ""
            )
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Failed to get GL or EGL extensions: " + e.message, e)
            return Pair("", "")
        } finally {
            releaseInternal()
        }
    }

    private fun createEglContext(
        dynamicRange: DynamicRangeProfile,
        infoBuilder: GraphicDeviceInfo.Builder?
    ) {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(mEglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY
            throw IllegalStateException("Unable to initialize EGL14")
        }

        infoBuilder?.setEglVersion(version[0].toString() + "." + version[1])

        val rgbBits = if (dynamicRange.isHdr) 10 else 8
        val alphaBits = if (dynamicRange.isHdr) 2 else 8
        val renderType = if (dynamicRange.isHdr)
            EGLExt.EGL_OPENGL_ES3_BIT_KHR
        else
            EGL14.EGL_OPENGL_ES2_BIT
        // TODO(b/319277249): It will crash on older Samsung devices for HDR video 10-bit
        //  because EGLExt.EGL_RECORDABLE_ANDROID is only supported from OneUI 6.1. We need to
        //  check by GPU Driver version when new OS is release.
        val recordableAndroid =
            if (dynamicRange.isHdr) EGL10.EGL_DONT_CARE else EGL14.EGL_TRUE
        val attribToChooseConfig = intArrayOf(
            EGL14.EGL_RED_SIZE, rgbBits,
            EGL14.EGL_GREEN_SIZE, rgbBits,
            EGL14.EGL_BLUE_SIZE, rgbBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, renderType,
            EGLExt.EGL_RECORDABLE_ANDROID, recordableAndroid,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                mEglDisplay, attribToChooseConfig, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) { "Unable to find a suitable EGLConfig" }
        val config = configs[0]
        val attribToCreateContext = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, if (dynamicRange.isHdr) 3 else 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            mEglDisplay, config, EGL14.EGL_NO_CONTEXT,
            attribToCreateContext, 0
        )
        checkEglErrorOrThrow("eglCreateContext")
        mEglConfig = config
        mEglContext = context

        // Confirm with query.
        val values = IntArray(1)
        EGL14.eglQueryContext(
            mEglDisplay, mEglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values,
            0
        )
        Log.d(TAG, "EGLContext created, client version " + values[0])
    }

    private fun createTempSurface() {
        mTempSurface = createPBufferSurface(
            mEglDisplay, requireNotNull(mEglConfig),  /*width=*/1,  /*height=*/
            1
        )
    }

    protected fun makeCurrent(eglSurface: EGLSurface) {
        check(
            EGL14.eglMakeCurrent(
                mEglDisplay,
                eglSurface,
                eglSurface,
                mEglContext
            )
        ) { "eglMakeCurrent failed" }
    }

    protected fun useAndConfigureProgramWithTexture(textureId: Int) {
        val program = requireNotNull(mProgramHandles[mCurrentInputformat]) {
            "Unable to configure program for input format: $mCurrentInputformat"
        }
        if (mCurrentProgram !== program) {
            mCurrentProgram = program
            program.use()
            Log.d(
                TAG, ("Using program for input format " + mCurrentInputformat + ": "
                        + mCurrentProgram)
            )
        }

        // Activate the texture
        activateExternalTexture(textureId)
    }

    private fun releaseInternal() {
        // Delete overlay resources.
        releaseStaticLayers()
        releaseTickerTexture()
        releaseLinkLayers()
        mPendingLayersUpdate.set(null)
        mPendingTickerUpdate.set(null)
        mPendingLinkUpdate.set(null)
        if (mOverlayProgramHandle != -1) {
            GLES20.glDeleteProgram(mOverlayProgramHandle)
            mOverlayProgramHandle = -1
        }

        // Delete program
        for (program in mProgramHandles.values) {
            program.delete()
        }
        mProgramHandles = emptyMap<InputFormat, Program2D>()
        mCurrentProgram = null

        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            // Destroy EGLSurfaces
            for (outputSurface in mOutputSurfaceMap.values) {
                if (outputSurface.eglSurface != EGL14.EGL_NO_SURFACE) {
                    if (!EGL14.eglDestroySurface(mEglDisplay, outputSurface.eglSurface)) {
                        checkEglErrorOrLog("eglDestroySurface")
                    }
                }
            }
            mOutputSurfaceMap.clear()

            // Destroy temp surface
            if (mTempSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mTempSurface)
                mTempSurface = EGL14.EGL_NO_SURFACE
            }

            // Destroy EGLContext and terminate display
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext)
                mEglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEglDisplay)
            mEglDisplay = EGL14.EGL_NO_DISPLAY
        }

        // Reset other members
        mEglConfig = null
        mExternalTextureId = -1
        mCurrentInputformat = InputFormat.UNKNOWN
        mCurrentSurface = null
        mGlThread = null
    }

    protected fun getOutSurfaceOrThrow(surface: Surface): OutputSurface {
        check(mOutputSurfaceMap.containsKey(surface)) {
            "The surface is not registered."
        }

        return requireNotNull(mOutputSurfaceMap[surface])
    }

    protected fun createOutputSurfaceInternal(
        surface: Surface
    ): OutputSurface? {
        val eglSurface = try {
            createWindowSurface(
                mEglDisplay, requireNotNull(mEglConfig), surface,
                mSurfaceAttrib
            )
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        }
        val size = getSurfaceSize(mEglDisplay, eglSurface)
        return OutputSurface(eglSurface, size.width, size.height)
    }

    protected fun removeOutputSurfaceInternal(surface: Surface, unregister: Boolean) {
        // Unmake current surface.
        if (mCurrentSurface === surface) {
            mCurrentSurface = null
            makeCurrent(mTempSurface)
        }

        // Remove cached EGL surface.
        val removedOutputSurface: OutputSurface = if (unregister) {
            mOutputSurfaceMap.remove(surface)!!
        } else {
            mOutputSurfaceMap.put(surface, NO_OUTPUT_SURFACE)!!
        }

        // Destroy EGL surface.
        if (removedOutputSurface !== NO_OUTPUT_SURFACE) {
            try {
                EGL14.eglDestroySurface(mEglDisplay, removedOutputSurface.eglSurface)
            } catch (e: RuntimeException) {
                Logger.w(TAG, "Failed to destroy EGL surface: " + e.message, e)
            }
        }
    }

    companion object {
        private const val TAG = "OpenGlRenderer"

        // ── Overlay / ticker constants ──────────────────────────────────────
        /** Maximum number of independent static overlay layers (GL_TEXTURE1..3). */
        private const val MAX_STATIC_LAYERS = 3
        /**
         * Texture unit index used by the ticker (glUniform1i value and GL_TEXTURE0 offset).
         * = MAX_STATIC_LAYERS + 1 = 4 → GL_TEXTURE4.
         */
        private const val TICKER_TEXTURE_INDEX = MAX_STATIC_LAYERS + 1
        /** Base texture unit index for link corner images (GL_TEXTURE5..7). */
        private const val LINK_TEXTURE_BASE = TICKER_TEXTURE_INDEX + 1

        /** Pixel margin from the top-left corner for the scoreboard overlay. */
        private const val OVERLAY_MARGIN_PX = 16f
        /** Scale factor applied to static text/score overlay layers (67.2% of natural pixel size). */
        private const val OVERLAY_SCALE = 0.672f
        /** Vertical position of the ticker centre in clip space (very bottom). */
        private const val TICKER_Y = -0.93f
        /** Clip-space units the ticker moves left per frame. */
        private const val TICKER_SPEED = 0.003f
        /** Initial X position (fully off-screen right) when a new ticker starts. */
        private const val TICKER_START_X = 1.1f

        // ── Link image constants ────────────────────────────────────────────
        /** Half-extent of each link image in clip-space X (≈ half screen width each).
         *  Since 0.45 < 0.5, link2 and link3 naturally have a ~20% screen-width gap between them. */
        private const val LINK_HALF_EXTENT = 0.45f
        /** Clip-space gap between the bottom of link2/link3 and the top of the ticker. */
        private const val LINK_TICKER_GAP = 0.02f
        /** Clip-space margin from screen edges for link1 (top-right). */
        private const val LINK_EDGE_MARGIN = 0.02f

        // ── Overlay shaders ─────────────────────────────────────────────────
        /**
         * Simple vertex shader for the 2D overlay quad.
         * Reuses the same full-screen vertex positions as the main pipeline; the [uTransMatrix]
         * uniform repositions and scales the quad to the desired overlay region.
         */
        private val OVERLAY_VERTEX_SHADER = """
            uniform mat4 uTransMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uTransMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        /**
         * Fragment shader that samples the overlay texture and outputs the colour with full alpha
         * support so transparent regions of the bitmap remain transparent.
         */
        private val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
        // ── End overlay shaders ─────────────────────────────────────────────
    }

    /** Represents a pending change to the overlay texture, consumed on the GL thread. */
    private sealed class OverlayUpdate {
        /** Upload [bitmap] as the new overlay texture. */
        data class Set(val bitmap: Bitmap) : OverlayUpdate()
        /** Remove the current overlay texture. */
        object Clear : OverlayUpdate()
    }

    /** Pending set of corner link bitmaps (null = clear that slot). */
    private data class LinkBitmaps(val link1: Bitmap?, val link2: Bitmap?, val link3: Bitmap?)

    /** One uploaded GL_TEXTURE_2D overlay layer. */
    private data class StaticLayer(val textureId: Int, val widthPx: Int, val heightPx: Int)
}