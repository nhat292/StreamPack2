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
     * Pending bitmap to upload as the overlay texture, or `null` when there is no pending update.
     * Use [OverlayUpdate] to distinguish "set new bitmap" from "clear overlay".
     */
    private val mPendingOverlayUpdate = AtomicReference<OverlayUpdate?>(null)

    /** GL texture id for the overlay (GL_TEXTURE_2D). -1 means no overlay is active. */
    private var mOverlayTextureId = -1
    private var mOverlayWidth = 0
    private var mOverlayHeight = 0

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
     * Sets or clears the overlay bitmap that will be composited over every rendered frame.
     *
     * This method is thread-safe and may be called from any thread. The actual texture upload
     * happens on the GL thread during the next [render] call.
     *
     * @param bitmap The bitmap to use as overlay, or `null` to remove the current overlay.
     */
    fun setOverlayBitmap(bitmap: Bitmap?) {
        mPendingOverlayUpdate.set(
            if (bitmap != null) OverlayUpdate.Set(bitmap) else OverlayUpdate.Clear
        )
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
     * Checks for pending overlay/ticker updates, draws the static scoreboard overlay at the
     * top-left and animates the ticker (right-to-left) at the bottom.
     * Must be called on the GL thread, inside [render], after the main draw call.
     */
    private fun renderOverlay(outputSurface: OutputSurface) {
        // Apply any pending updates.
        val overlayUpdate = mPendingOverlayUpdate.getAndSet(null)
        when (overlayUpdate) {
            is OverlayUpdate.Set -> uploadOverlayBitmap(update = overlayUpdate, ticker = false)
            is OverlayUpdate.Clear -> releaseOverlayTexture()
            null -> {}
        }
        val tickerUpdate = mPendingTickerUpdate.getAndSet(null)
        when (tickerUpdate) {
            is OverlayUpdate.Set -> {
                uploadOverlayBitmap(update = tickerUpdate, ticker = true)
                mTickerX = TICKER_START_X  // restart from right each time the ticker changes
            }
            is OverlayUpdate.Clear -> releaseTickerTexture()
            null -> {}
        }

        val hasOverlay = mOverlayTextureId != -1
        val hasTicker = mTickerTextureId != -1
        if (!hasOverlay && !hasTicker) return

        val surfaceW = outputSurface.viewPortRect.width().toFloat()
        val surfaceH = outputSurface.viewPortRect.height().toFloat()
        if (surfaceW <= 0f || surfaceH <= 0f) return

        // Enable alpha blending for all overlay draws.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(mOverlayProgramHandle)
        checkGlErrorOrThrow("glUseProgram (overlay)")

        // Set up vertex / tex-coord arrays once; they are the same for both draws.
        GLES20.glEnableVertexAttribArray(mOverlayPositionLoc)
        GLES20.glVertexAttribPointer(mOverlayPositionLoc, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUF)
        GLES20.glEnableVertexAttribArray(mOverlayTexCoordLoc)
        GLES20.glVertexAttribPointer(mOverlayTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, mOverlayTexBuf)

        // ── 1. Static scoreboard overlay (top-left with margin) ────────────
        if (hasOverlay) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOverlayTextureId)
            GLES20.glUniform1i(mOverlayTextureLoc, 1)

            // Scale: overlay pixel dimensions as a fraction of the surface.
            val scaleX = mOverlayWidth / surfaceW
            val scaleY = mOverlayHeight / surfaceH
            // Margin: OVERLAY_MARGIN_PX pixels from the top-left corner.
            // In clip space the screen spans 2 units (-1..1), so 1 pixel = 2/surfaceW units.
            val marginX = OVERLAY_MARGIN_PX * 2f / surfaceW
            val marginY = OVERLAY_MARGIN_PX * 2f / surfaceH

            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            m[0]  = scaleX
            m[5]  = scaleY
            m[12] = -1f + scaleX + marginX   // left edge at (-1 + margin)
            m[13] =  1f - scaleY - marginY   // top  edge at ( 1 - margin)
            GLES20.glUniformMatrix4fv(mOverlayTransMatrixLoc, 1, false, m, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGlErrorOrThrow("glDrawArrays (scoreboard overlay)")
        }

        // ── 2. Ticker (scrolling right-to-left at the bottom) ─────────────
        if (hasTicker) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTickerTextureId)
            GLES20.glUniform1i(mOverlayTextureLoc, 2)

            // Keep the ticker's natural pixel height; compute scaleX to maintain aspect.
            val tickerScaleY = mTickerHeight / surfaceH
            val tickerScaleX = tickerScaleY * (mTickerWidth.toFloat() / mTickerHeight)

            // Advance the ticker one step to the left each frame.
            mTickerX -= TICKER_SPEED
            // Loop: once fully off-screen left, restart from fully off-screen right.
            if (mTickerX < -1f - tickerScaleX) mTickerX = 1f + tickerScaleX

            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            m[0]  = tickerScaleX
            m[5]  = tickerScaleY
            m[12] = mTickerX     // center translates each frame
            m[13] = TICKER_Y     // fixed near bottom
            GLES20.glUniformMatrix4fv(mOverlayTransMatrixLoc, 1, false, m, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGlErrorOrThrow("glDrawArrays (ticker)")
        }

        // Restore state.
        GLES20.glDisableVertexAttribArray(mOverlayPositionLoc)
        GLES20.glDisableVertexAttribArray(mOverlayTexCoordLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        activateExternalTexture(mExternalTextureId)
        mCurrentProgram?.use()
    }

    /**
     * Uploads [update]'s bitmap to a GL_TEXTURE_2D texture (creating it if necessary).
     * [ticker] = false  → scoreboard overlay (unit 1)
     * [ticker] = true   → ticker (unit 2)
     * Must be called on the GL thread.
     */
    private fun uploadOverlayBitmap(update: OverlayUpdate.Set, ticker: Boolean) {
        val bitmap = update.bitmap
        val unit = if (ticker) GLES20.GL_TEXTURE2 else GLES20.GL_TEXTURE1

        if (ticker) {
            if (mTickerTextureId == -1) {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0); mTickerTextureId = t[0]
            }
        } else {
            if (mOverlayTextureId == -1) {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0); mOverlayTextureId = t[0]
            }
        }

        val texId = if (ticker) mTickerTextureId else mOverlayTextureId
        GLES20.glActiveTexture(unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        AndroidGLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGlErrorOrThrow("texImage2D (${if (ticker) "ticker" else "overlay"})")

        if (ticker) { mTickerWidth = bitmap.width; mTickerHeight = bitmap.height }
        else        { mOverlayWidth = bitmap.width; mOverlayHeight = bitmap.height }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        activateExternalTexture(mExternalTextureId)
    }

    /** Deletes the scoreboard overlay texture. Must be called on the GL thread. */
    private fun releaseOverlayTexture() {
        if (mOverlayTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(mOverlayTextureId), 0)
            mOverlayTextureId = -1; mOverlayWidth = 0; mOverlayHeight = 0
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
        releaseOverlayTexture()
        releaseTickerTexture()
        mPendingTickerUpdate.set(null)
        if (mOverlayProgramHandle != -1) {
            GLES20.glDeleteProgram(mOverlayProgramHandle)
            mOverlayProgramHandle = -1
        }
        mPendingOverlayUpdate.set(null)

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
        /** Pixel margin from the top-left corner for the scoreboard overlay. */
        private const val OVERLAY_MARGIN_PX = 8f
        /** Vertical position of the ticker centre in clip space (near bottom). */
        private const val TICKER_Y = -0.92f
        /** Clip-space units the ticker moves left per frame. */
        private const val TICKER_SPEED = 0.003f
        /** Initial X position (fully off-screen right) when a new ticker starts. */
        private const val TICKER_START_X = 1.1f

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
}