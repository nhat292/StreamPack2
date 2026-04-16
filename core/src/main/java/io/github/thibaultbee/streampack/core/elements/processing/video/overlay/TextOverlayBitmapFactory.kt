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
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/**
 * Builds a single [Bitmap] that composites all live-stream overlay elements:
 *  – match/group label (text3, yellow bar)
 *  – two player scoreboard rows (text1 / text2) with turn indicator, match score,
 *    set score, point and tie-break columns
 *  – event/venue info (text4)
 *  – ticker text bar
 *
 * All layout constants (colours, font sizes, padding, column widths) mirror the
 * reference implementation in `Texture2DProgram.kt`.
 *
 * Caching: the bitmap is only rebuilt when [OverlayParams] changes.  Pass the returned
 * bitmap to `streamer.videoInput.processor.setOverlayBitmap(bitmap)`.
 *
 * Note: `link1`, `link2`, `link3` are remote image URLs used by the old GL renderer.
 *       They are accepted as parameters for API compatibility but are **not rendered**
 *       inside this bitmap; handle them separately if needed.
 */
object TextOverlayBitmapFactory {

    // ── Layout constants ──────────────────────────────────────────────────
    private const val TEXT_SIZE = 30f
    private const val TEXT3_SIZE = 30f
    private const val TEXT4_SIZE = 25f
    private const val TICKER_SIZE = 28f
    private const val PADDING = 14f
    private const val BORDER_WIDTH = 1f

    // Colours
    private val COLOR_PLAYER_BG = Color.parseColor("#146094")
    private val COLOR_TEXT3_BG = Color.parseColor("#FDC738")
    private val COLOR_TEXT3_TEXT = Color.parseColor("#101D54")
    private val COLOR_TEXT4_BG = Color.parseColor("#146094")
    private val COLOR_MATCH_SCORE_BG = Color.WHITE
    private val COLOR_SCORE_BG = Color.parseColor("#064C9B")
    private val COLOR_POINT_BG = Color.YELLOW
    private val COLOR_POINT_TEXT = Color.RED
    private val COLOR_TB_BG = Color.RED
    private val COLOR_BORDER = Color.parseColor("#FDC738")
    private val COLOR_TICKER_BG = Color.parseColor("#66000000")

    // ── Cache ─────────────────────────────────────────────────────────────
    private var lastParams: OverlayParams? = null
    private var cachedBitmap: Bitmap? = null

    // ── Public data class ─────────────────────────────────────────────────

    /**
     * All parameters that drive the overlay layout.
     */
    data class OverlayParams(
        val text1: String = "",
        val text2: String = "",
        val text3: String = "",
        val text4: String = "",
        val score1: String = "",
        val turn1: String = "",
        val score2: String = "",
        val turn2: String = "",
        val link1: String = "",
        val link2: String = "",
        val link3: String = "",
        val matchScore1: String = "",
        val matchScore2: String = "",
        val point1: String? = null,
        val point2: String? = null,
        val isTennis: Boolean = false,
        val tbScore1: String? = null,
        val tbScore2: String? = null,
        val tickerText: String = "",
    )

    // ── Public API ────────────────────────────────────────────────────────

    fun create(
        text1: String = "",
        text2: String = "",
        text3: String = "",
        text4: String = "",
        score1: String = "",
        turn1: String = "",
        score2: String = "",
        turn2: String = "",
        link1: String = "",
        link2: String = "",
        link3: String = "",
        matchScore1: String = "",
        matchScore2: String = "",
        point1: String? = null,
        point2: String? = null,
        isTennis: Boolean = false,
        tbScore1: String? = null,
        tbScore2: String? = null,
        tickerText: String = "",
    ): Bitmap? = create(
        OverlayParams(
            text1, text2, text3, text4,
            score1, turn1, score2, turn2,
            link1, link2, link3,
            matchScore1, matchScore2,
            point1, point2,
            isTennis,
            tbScore1, tbScore2,
            tickerText,
        )
    )

    fun create(params: OverlayParams): Bitmap? {
        if (params == lastParams && cachedBitmap?.isRecycled == false) {
            return cachedBitmap
        }
        cachedBitmap = buildBitmap(params)
        lastParams = params
        return cachedBitmap
    }

    // ── Internal model ────────────────────────────────────────────────────

    /** Pre-measured label row (text3, text4, ticker). */
    private data class LabelSection(
        val text: String,
        val textSize: Float,
        val textColor: Int,
        val bgColor: Int,
        val border: Boolean,
        val w: Int,
        val h: Int,
        val glyphTop: Int,  // Paint.getTextBounds top (negative = ascent above baseline)
    )

    /**
     * Describes the shared column structure used by BOTH player scoreboard rows.
     * All columns are determined by combining the needs of both rows so they always
     * have the same width and their score columns align vertically.
     */
    private data class ScoreboardLayout(
        val nameColW: Int,
        val rowH: Int,
        val glyphTop: Int,
        val hasTurn: Boolean,
        val hasMatchScore: Boolean,
        val hasScore: Boolean,
        val hasPoint: Boolean,
        val hasTb: Boolean,
        val turnColW: Float,
        val matchScoreColW: Float,
        val scoreColW: Float,
        val pointColW: Float,
        val tbColW: Float,
    ) {
        /**
         * Total pixel width of a scoreboard row.
         *
         * Layout (mirrors reference Texture2DProgram):
         *   LEFT_BORDER + LEFT_PAD + nameCol
         *   + (LEADING_PAD + turnCol)?
         *   + matchScoreCol? + scoreCol? + pointCol? + tbCol?
         *   + RIGHT_PAD + RIGHT_BORDER
         *
         * The leading padding before the turn block "consumes" the right-side trailing
         * padding, so the last block always ends flush at the border — exactly like the
         * reference.
         */
        val rowWidth: Int
            get() {
                var w = BORDER_WIDTH + PADDING + nameColW
                if (hasTurn) w += PADDING + turnColW   // PADDING = leading pad before turn
                if (hasMatchScore) w += matchScoreColW
                if (hasScore) w += scoreColW
                if (hasPoint) w += pointColW
                if (hasTb) w += tbColW
                w += PADDING + BORDER_WIDTH            // right side pad + border
                return w.toInt()
            }
    }

    // ── Measurement helpers ───────────────────────────────────────────────

    private fun measureLabel(
        text: String, textSize: Float, textColor: Int, bgColor: Int, border: Boolean,
    ): LabelSection {
        val paint = textPaint(textColor, textSize)
        val b = Rect()
        paint.getTextBounds(text, 0, text.length, b)
        val w = (b.width() + PADDING * 2 + BORDER_WIDTH * 2).toInt().coerceAtLeast(1)
        val h = (b.height() + PADDING * 2 + BORDER_WIDTH * 2).toInt().coerceAtLeast(1)
        return LabelSection(text, textSize, textColor, bgColor, border, w, h, b.top)
    }

    private fun sampleColWidth(paint: Paint, sample: String): Float {
        val r = Rect()
        paint.getTextBounds(sample, 0, sample.length, r)
        return r.width().toFloat()
    }

    /**
     * Builds the shared scoreboard column layout from both player rows.
     * Returns null if neither text1 nor text2 is set.
     */
    private fun buildScoreboardLayout(p: OverlayParams): ScoreboardLayout? {
        if (p.text1.isEmpty() && p.text2.isEmpty()) return null

        val paint = textPaint(Color.WHITE, TEXT_SIZE)

        // Row height from a reference glyph with both ascender and descender
        val glyphBounds = Rect()
        paint.getTextBounds("Ag", 0, 2, glyphBounds)
        val glyphH = glyphBounds.height()
        val glyphTop = glyphBounds.top
        val rowH = (glyphH + PADDING * 2 + BORDER_WIDTH * 2).toInt().coerceAtLeast(1)

        // Name column: max pixel width of both player names
        val nameBounds = Rect()
        var nameColW = 0
        for (name in listOf(p.text1, p.text2).filter { it.isNotEmpty() }) {
            paint.getTextBounds(name, 0, name.length, nameBounds)
            nameColW = maxOf(nameColW, nameBounds.width())
        }

        // A column appears if EITHER row uses it so both rows are the same width
        val hasTurn = p.turn1.isNotEmpty() || p.turn2.isNotEmpty() || p.isTennis
        val hasMatchScore = p.matchScore1.isNotEmpty() || p.matchScore2.isNotEmpty()
        val hasScore = p.score1.isNotEmpty() || p.score2.isNotEmpty()
        val hasPoint = p.point1 != null || p.point2 != null
        val hasTb = p.tbScore1 != null || p.tbScore2 != null

        return ScoreboardLayout(
            nameColW = nameColW,
            rowH = rowH,
            glyphTop = glyphTop,
            hasTurn = hasTurn,
            hasMatchScore = hasMatchScore,
            hasScore = hasScore,
            hasPoint = hasPoint,
            hasTb = hasTb,
            turnColW = if (hasTurn) sampleColWidth(paint, "000") else 0f,
            matchScoreColW = if (hasMatchScore) sampleColWidth(paint, "000") else 0f,
            scoreColW = if (hasScore) sampleColWidth(paint, "000") else 0f,
            pointColW = if (hasPoint) sampleColWidth(paint, "0000") else 0f,
            tbColW = if (hasTb) sampleColWidth(paint, "000") else 0f,
        )
    }

    // ── Bitmap construction ───────────────────────────────────────────────

    private fun buildBitmap(p: OverlayParams): Bitmap? {
        // Measure optional label sections
        val text3 = if (p.text3.isNotEmpty())
            measureLabel(p.text3, TEXT3_SIZE, COLOR_TEXT3_TEXT, COLOR_TEXT3_BG, border = false)
        else null

        val text4 = if (p.text4.isNotEmpty())
            measureLabel(p.text4, TEXT4_SIZE, Color.WHITE, COLOR_TEXT4_BG, border = true)
        else null

        val ticker = if (p.tickerText.isNotEmpty())
            measureLabel(p.tickerText, TICKER_SIZE, Color.WHITE, COLOR_TICKER_BG, border = false)
        else null

        // Shared scoreboard layout (null when neither player row is set)
        val scoreLayout = buildScoreboardLayout(p)

        if (scoreLayout == null && text3 == null && text4 == null && ticker == null) return null

        val scoreboardRowCount =
            (if (p.text1.isNotEmpty()) 1 else 0) + (if (p.text2.isNotEmpty()) 1 else 0)

        // Canvas width: max of all row widths; label rows stretch to fill
        val canvasWidth = maxOf(
            scoreLayout?.rowWidth ?: 0,
            text3?.w ?: 0,
            text4?.w ?: 0,
            ticker?.w ?: 0,
        )
        if (canvasWidth <= 0) return null

        val canvasHeight =
            (text3?.h ?: 0) +
            (scoreLayout?.rowH ?: 0) * scoreboardRowCount +
            (text4?.h ?: 0) +
            (ticker?.h ?: 0)
        if (canvasHeight <= 0) return null

        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var yOffset = 0

        // 1. Match/group label (yellow bar, text3)
        if (text3 != null) {
            drawLabel(canvas, text3, yOffset, canvasWidth)
            yOffset += text3.h
        }

        // 2. Scoreboard rows (both use the same shared layout)
        if (scoreLayout != null) {
            if (p.text1.isNotEmpty()) {
                drawScoreboardRow(
                    canvas, p.text1, p.turn1, p.matchScore1,
                    p.score1, p.point1, p.tbScore1, p.isTennis,
                    scoreLayout, yOffset, canvasWidth,
                )
                yOffset += scoreLayout.rowH
            }
            if (p.text2.isNotEmpty()) {
                drawScoreboardRow(
                    canvas, p.text2, p.turn2, p.matchScore2,
                    p.score2, p.point2, p.tbScore2, p.isTennis,
                    scoreLayout, yOffset, canvasWidth,
                )
                yOffset += scoreLayout.rowH
            }
        }

        // 3. Venue/info row (text4)
        if (text4 != null) {
            drawLabel(canvas, text4, yOffset, canvasWidth)
            yOffset += text4.h
        }

        // 4. Ticker bar
        if (ticker != null) {
            drawLabel(canvas, ticker, yOffset, canvasWidth)
        }

        return bitmap
    }

    // ── Draw routines ─────────────────────────────────────────────────────

    private fun drawLabel(canvas: Canvas, s: LabelSection, yOffset: Int, canvasWidth: Int) {
        val top = yOffset.toFloat()
        val bottom = top + s.h

        // Background stretches to full canvas width
        canvas.drawRect(
            BORDER_WIDTH, top + BORDER_WIDTH,
            canvasWidth - BORDER_WIDTH, bottom - BORDER_WIDTH,
            fillPaint(s.bgColor),
        )

        if (s.border) {
            canvas.drawRect(
                BORDER_WIDTH / 2, top + BORDER_WIDTH / 2,
                canvasWidth - BORDER_WIDTH / 2, bottom - BORDER_WIDTH / 2,
                strokePaint(COLOR_BORDER, BORDER_WIDTH),
            )
        }

        // baseline = yOffset + padding + borderWidth - glyphTop
        // glyphTop is negative so subtracting it adds the ascent distance.
        val baseline = yOffset + PADDING + BORDER_WIDTH - s.glyphTop
        canvas.drawText(s.text, PADDING + BORDER_WIDTH, baseline, textPaint(s.textColor, s.textSize))
    }

    /**
     * Draws one player scoreboard row using the shared [layout].
     *
     * Column order (matches reference Texture2DProgram.kt):
     *   name | [turn] | [matchScore] | [score] | [point] | [tieBreak]
     *
     * The background always extends to [canvasWidth] so both rows look identical in width.
     * Every optional column is drawn based on the shared layout flags, even if this
     * individual row's value is empty — ensuring columns align between the two player rows.
     */
    private fun drawScoreboardRow(
        canvas: Canvas,
        name: String,
        turn: String,
        matchScore: String,
        score: String,
        point: String?,
        tbScore: String?,
        isTennis: Boolean,
        layout: ScoreboardLayout,
        yOffset: Int,
        canvasWidth: Int,
    ) {
        val top = yOffset.toFloat()
        val bottom = top + layout.rowH

        // Background extends to canvasWidth (not just row width) for consistent appearance
        canvas.drawRect(
            BORDER_WIDTH, top + BORDER_WIDTH,
            canvasWidth - BORDER_WIDTH, bottom - BORDER_WIDTH,
            fillPaint(COLOR_PLAYER_BG),
        )

        // Border
        canvas.drawRect(
            BORDER_WIDTH / 2, top + BORDER_WIDTH / 2,
            canvasWidth - BORDER_WIDTH / 2, bottom - BORDER_WIDTH / 2,
            strokePaint(COLOR_BORDER, BORDER_WIDTH),
        )

        val paint = textPaint(Color.WHITE, TEXT_SIZE)
        val baseline = yOffset + PADDING + BORDER_WIDTH - layout.glyphTop

        var cursorX = PADDING + BORDER_WIDTH

        // Player name
        canvas.drawText(name, cursorX, baseline, paint)
        cursorX += layout.nameColW

        // Turn indicator — drawn for both rows whenever the column is active,
        // even if this row's turn is empty (background only, no indicator).
        if (layout.hasTurn) {
            cursorX += PADDING   // leading padding before turn block (matches reference)
            val bw = layout.turnColW
            val rect = RectF(cursorX, top + BORDER_WIDTH, cursorX + bw, bottom - BORDER_WIDTH)
            canvas.drawRect(rect, fillPaint(COLOR_PLAYER_BG))
            if (turn.isNotEmpty()) {
                if (isTennis) {
                    val radius = min(rect.width(), rect.height()) / 3.5f
                    canvas.drawCircle(rect.centerX(), rect.centerY(), radius, fillPaint(Color.GREEN))
                } else {
                    canvas.drawText(
                        turn, rect.centerX(), baseline,
                        textPaint(Color.WHITE, TEXT_SIZE, Paint.Align.CENTER),
                    )
                }
            }
            cursorX += bw
        }

        // Match score (white bg, black text)
        if (layout.hasMatchScore) {
            val bw = layout.matchScoreColW
            val rect = RectF(cursorX, top + BORDER_WIDTH, cursorX + bw, bottom - BORDER_WIDTH)
            canvas.drawRect(rect, fillPaint(COLOR_MATCH_SCORE_BG))
            if (matchScore.isNotEmpty()) {
                canvas.drawText(
                    matchScore, rect.centerX(), baseline,
                    textPaint(Color.BLACK, TEXT_SIZE, Paint.Align.CENTER),
                )
            }
            cursorX += bw
        }

        // Set score (dark-blue bg, white text)
        if (layout.hasScore) {
            val bw = layout.scoreColW
            val rect = RectF(cursorX, top + BORDER_WIDTH, cursorX + bw, bottom - BORDER_WIDTH)
            canvas.drawRect(rect, fillPaint(COLOR_SCORE_BG))
            if (score.isNotEmpty()) {
                canvas.drawText(
                    score, rect.centerX(), baseline,
                    textPaint(Color.WHITE, TEXT_SIZE, Paint.Align.CENTER),
                )
            }
            cursorX += bw
        }

        // Point (yellow bg, red text)
        if (layout.hasPoint) {
            val bw = layout.pointColW
            val rect = RectF(cursorX, top + BORDER_WIDTH, cursorX + bw, bottom - BORDER_WIDTH)
            canvas.drawRect(rect, fillPaint(COLOR_POINT_BG))
            if (!point.isNullOrEmpty()) {
                canvas.drawText(
                    point, rect.centerX(), baseline,
                    textPaint(COLOR_POINT_TEXT, TEXT_SIZE, Paint.Align.CENTER),
                )
            }
            cursorX += bw
        }

        // Tie-break (red bg, white text)
        if (layout.hasTb) {
            val bw = layout.tbColW
            val rect = RectF(cursorX, top + BORDER_WIDTH, cursorX + bw, bottom - BORDER_WIDTH)
            canvas.drawRect(rect, fillPaint(COLOR_TB_BG))
            if (!tbScore.isNullOrEmpty()) {
                canvas.drawText(
                    tbScore, rect.centerX(), baseline,
                    textPaint(Color.WHITE, TEXT_SIZE, Paint.Align.CENTER),
                )
            }
        }
    }

    // ── Paint factories ───────────────────────────────────────────────────

    private fun textPaint(color: Int, size: Float, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = size
            this.textAlign = align
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

    private fun strokePaint(color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        }
}
