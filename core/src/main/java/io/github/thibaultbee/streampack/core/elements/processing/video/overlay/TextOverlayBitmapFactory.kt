/*
 * Copyright (C) 2025 StreamPack contributors.
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
package io.github.thibaultbee.streampack.core.elements.processing.video.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Creates a [Bitmap] containing styled text on a solid background, suitable for use as a
 * video overlay via [io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal.setOverlayBitmap].
 *
 * Example usage:
 * ```kotlin
 * val bitmap = TextOverlayBitmapFactory.create("Hello to live streaming platform")
 * streamer.videoInput.processor.setOverlayBitmap(bitmap)
 * ```
 */
object TextOverlayBitmapFactory {

    /**
     * Creates a bitmap with the given [text] drawn on a [backgroundColor] rectangle with [paddingPx]
     * padding on all sides.
     *
     * @param text             The text to render.
     * @param textSizePx       Font size in pixels. Defaults to 48px.
     * @param textColor        Text colour. Defaults to white.
     * @param backgroundColor  Background fill colour. Defaults to blue.
     * @param paddingPx        Padding in pixels between the text and the background edge.
     * @param typeface         Typeface to use. Defaults to [Typeface.DEFAULT_BOLD].
     * @return A [Bitmap.Config.ARGB_8888] bitmap containing the styled text label.
     */
    fun create(
        text: String,
        textSizePx: Float = 48f,
        textColor: Int = Color.WHITE,
        backgroundColor: Int = Color.BLUE,
        paddingPx: Float = 24f,
        typeface: Typeface = Typeface.DEFAULT_BOLD,
    ): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = textSizePx
            color = textColor
        }

        // Measure the text bounding box so we can size the bitmap exactly.
        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        val bitmapWidth = (textBounds.width() + 2 * paddingPx).toInt()
        val bitmapHeight = (textBounds.height() + 2 * paddingPx).toInt()

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw the background rectangle.
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), bgPaint)

        // Draw the text.  getTextBounds reports a top value that is negative (ascent above baseline),
        // so we offset by -textBounds.top to shift the baseline so glyphs sit inside the padding.
        val textX = paddingPx
        val textY = paddingPx - textBounds.top
        canvas.drawText(text, textX, textY, textPaint)

        return bitmap
    }
}
