/*
 * Copyright (C) 2024 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.Bitmap
import android.util.Size
import android.view.Surface
import androidx.annotation.IntRange
import com.google.common.util.concurrent.ListenableFuture
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider



interface ISurfaceProcessor

/**
 * Interface for a surface processor that manages input and output surfaces.
 *
 * This interface allows for the creation, removal, and management of input and output surfaces
 * used in video processing.
 *
 * You can create your own implementation of this interface to handle custom effects or processing.
 */
interface ISurfaceProcessorInternal : ISurfaceProcessor, Releasable {
    fun createInputSurface(surfaceSize: Size, timebase: Timebase): Surface

    fun removeInputSurface(surface: Surface)

    fun setTimebase(surface: Surface, timebase: Timebase)

    fun addOutputSurface(surfaceOutput: ISurfaceOutput)

    fun removeOutputSurface(surfaceOutput: ISurfaceOutput)

    fun removeOutputSurface(surface: Surface)

    fun removeAllOutputSurfaces()

    fun snapshot(@IntRange(from = 0, to = 359) rotationDegrees: Int): ListenableFuture<Bitmap>

    /**
     * Sets or clears a bitmap that will be composited over the top-left corner of every rendered
     * frame.  Pass `null` to remove a previously set overlay.
     *
     * This call is thread-safe; the actual texture upload happens on the GL thread.
     *
     * Default implementation is a no-op so existing [ISurfaceProcessorInternal] implementations
     * are not broken.
     */
    fun setOverlayBitmap(bitmap: Bitmap?) { /* no-op by default */ }

    /**
     * Replaces the static overlay with a list of independently-positioned bitmaps.  The bitmaps
     * are stacked vertically (top-to-bottom) at the top-left corner of the frame, each rendered
     * in its own GL texture.  Pass an empty list to clear the overlay.
     *
     * Use [io.github.thibaultbee.streampack.core.elements.processing.video.overlay.TextOverlayBitmapFactory.createLayers]
     * to build the layer list with per-layer caching.
     *
     * This call is thread-safe; texture uploads happen on the GL thread.
     *
     * Default implementation is a no-op so existing [ISurfaceProcessorInternal] implementations
     * are not broken.
     */
    fun setOverlayBitmaps(bitmaps: List<Bitmap>) { /* no-op by default */ }

    /**
     * Sets or clears the ticker bitmap that will be animated (scrolled right-to-left) at the
     * bottom of every rendered frame.  Pass `null` to stop the ticker.
     *
     * This call is thread-safe; the actual texture upload happens on the GL thread.
     */
    fun setTickerBitmap(bitmap: Bitmap?) { /* no-op by default */ }

    /**
     * Sets corner image overlays: [link1] top-right, [link2] bottom-left, [link3] bottom-right.
     * Pass `null` for any slot to clear that corner. Thread-safe; upload happens on the GL thread.
     */
    fun setLinkBitmaps(link1: Bitmap?, link2: Bitmap?, link3: Bitmap?) { /* no-op by default */ }

    /**
     * Factory interface for creating instances of [ISurfaceProcessorInternal].
     */
    interface Factory {
        fun create(
            dynamicRangeProfile: DynamicRangeProfile,
            dispatcherProvider: IVideoDispatcherProvider
        ): ISurfaceProcessorInternal
    }
}
