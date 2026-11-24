/*
 * StatusBarLyric
 * Copyright (C) 2021-2022 fkj@fkj233.cn
 * https://github.com/Block-Network/StatusBarLyric
 *
 * This software is free opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as
 * published by Block-Network contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/Block-Network/StatusBarLyric/blob/main/LICENSE>.
 */

package statusbar.lyric.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.view.Choreographer
import android.widget.TextView
import statusbar.lyric.config.XposedOwnSP.config
import statusbar.lyric.tools.LyricScrollPlan
import statusbar.lyric.tools.WordTiming
import kotlin.math.abs
import kotlin.math.exp

class LyricTextView(context: Context) : TextView(context), Choreographer.FrameCallback {
    private data class MeasuredWord(
        val startMs: Long,
        val endMs: Long,
        val xStart: Float,
        val xEnd: Float
    )

    private var isScrolling = false
    private var textLength = 0f
    private var viewWidth = 0f
    private var scrollSpeed = 4f
    private var currentX = 0f
    private var scrollPlan: LyricScrollPlan? = null
    private var measuredWords: List<MeasuredWord> = emptyList()

    // Timing anchors for frame-rate-independent scrolling
    private var startFrameNanos = 0L
    private var lastFrameNanos = 0L
    private var dynamicAnchorWallMs = 0L
    private var dynamicAnchorMediaMs = 0L
    private var dynamicPlaybackSpeed = 1.0f

    init {
        paint.style = Paint.Style.FILL_AND_STROKE
    }

    override fun onDetachedFromWindow() {
        stopScroll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
    }

    override fun setText(text: CharSequence, type: BufferType) {
        stopScroll()
        currentX = 0f
        val str = text.toString()
        textLength = getTextLength(str)
        val plan = scrollPlan
        measuredWords = if (plan is LyricScrollPlan.Dynamic && plan.words.isNotEmpty() && str.isNotEmpty()) {
            buildMeasuredWords(str, plan.words)
        } else {
            emptyList()
        }
        super.setText(text, type)
        if (str.isNotEmpty()) {
            startScroll()
        }
    }

    override fun setTextColor(color: Int) {
        paint.color = color
        postInvalidate()
    }

    fun setLinearGradient(shader: Shader?) {
        paint.shader = shader
        postInvalidate()
    }

    fun setStrokeWidth(width: Float) {
        paint.strokeWidth = width
        postInvalidate()
    }

    fun measureTextWidth(text: CharSequence): Float {
        return paint.measureText(text.toString())
    }

    fun setScrollPlan(plan: LyricScrollPlan) {
        scrollPlan = plan
        if (plan is LyricScrollPlan.Dynamic) {
            dynamicPlaybackSpeed = plan.playbackSpeed
            dynamicAnchorMediaMs = plan.initialMediaPosMs
        }
    }

    fun syncPlaybackPosition(positionMs: Long, playbackSpeed: Float) {
        val plan = scrollPlan as? LyricScrollPlan.Dynamic ?: return
        val safeSpeed = if (playbackSpeed.isFinite() && playbackSpeed in 0.25f..4.0f) playbackSpeed else 1.0f
        if (positionMs > 0L) {
            dynamicAnchorMediaMs = positionMs
            dynamicAnchorWallMs = System.nanoTime() / 1_000_000L
        }
        dynamicPlaybackSpeed = safeSpeed
        scrollPlan = plan.copy(playbackSpeed = safeSpeed)
    }

    override fun onDraw(canvas: Canvas) {
        if (width > 0) {
            viewWidth = width.toFloat()
        }
        val y = (height - (paint.descent() + paint.ascent())) / 2
        text?.let { canvas.drawText(it.toString(), currentX, y, paint) }
    }

    private val visibleRect = android.graphics.Rect()

    private fun resolveEffectiveWidth(plan: LyricScrollPlan?, elapsedMs: Long): Float {
        val layoutWidth = when {
            width > 0 -> width.toFloat()
            viewWidth > 0f -> viewWidth
            plan is LyricScrollPlan.Dynamic -> plan.targetWidthPx.toFloat()
            plan is LyricScrollPlan.Fixed -> plan.targetWidthPx.toFloat()
            else -> 0f
        }
        val animDuration = (plan as? LyricScrollPlan.Dynamic)?.animationDurationMs ?: 0L
        if (layoutWidth > 0f && isAttachedToWindow && isShown && elapsedMs >= animDuration) {
            if (getGlobalVisibleRect(visibleRect)) {
                val minValidPx = (24f * resources.displayMetrics.density).toInt()
                val clippedW = visibleRect.width().toFloat()
                if (clippedW >= minValidPx && clippedW < layoutWidth) {
                    return clippedW
                }
            }
        }
        return layoutWidth
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isScrolling) return
        if (startFrameNanos == 0L) {
            startFrameNanos = frameTimeNanos
            lastFrameNanos = frameTimeNanos
            dynamicAnchorWallMs = frameTimeNanos / 1_000_000L
        }

        val dtSec = ((frameTimeNanos - lastFrameNanos).coerceIn(0L, 100_000_000L)) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        val elapsedMs = (frameTimeNanos - startFrameNanos) / 1_000_000L

        val plan = scrollPlan
        val effectiveWidth = resolveEffectiveWidth(plan, elapsedMs)
        if (effectiveWidth <= 0f) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }

        val overflowPx = textLength - effectiveWidth
        if (overflowPx <= 0.5f) {
            currentX = 0f
            invalidate()
            stopScroll()
            return
        }

        val prevX = currentX
        when (plan) {
            is LyricScrollPlan.Dynamic -> updateDynamicScroll(plan, frameTimeNanos, elapsedMs, dtSec, effectiveWidth, overflowPx)
            is LyricScrollPlan.Fixed -> updateFixedScroll(plan.speedPxPerSec, plan.leadHoldMs, elapsedMs, dtSec, overflowPx)
            null -> updateFixedScroll(scrollSpeed.coerceAtLeast(0.5f) * 60f, config.animationDuration + 450L, elapsedMs, dtSec, overflowPx)
        }

        if (abs(currentX - prevX) >= 0.05f) {
            invalidate()
        }
        if (isScrolling) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateFixedScroll(
        speedPxPerSec: Float,
        leadHoldMs: Long,
        elapsedMs: Long,
        dtSec: Float,
        overflowPx: Float
    ) {
        if (elapsedMs < leadHoldMs) {
            currentX = 0f
            return
        }
        val nextX = currentX - speedPxPerSec * dtSec
        if (nextX <= -overflowPx) {
            currentX = -overflowPx
            invalidate()
            stopScroll()
        } else {
            currentX = nextX
        }
    }

    private fun updateDynamicScroll(
        plan: LyricScrollPlan.Dynamic,
        frameTimeNanos: Long,
        elapsedMs: Long,
        dtSec: Float,
        effectiveWidth: Float,
        overflowPx: Float
    ) {
        val words = measuredWords
        if (words.size >= 2) {
            // Tier 1: Word-by-word karaoke-synchronized scrolling with 150ms lookahead
            val nowWallMs = frameTimeNanos / 1_000_000L
            val elapsedSinceAnchorMs = (nowWallMs - dynamicAnchorWallMs).coerceAtLeast(0L)
            val mediaPosMs = dynamicAnchorMediaMs + ((elapsedSinceAnchorMs + 150L) * dynamicPlaybackSpeed)

            val cursorPx = computeWordCursorPx(words, mediaPosMs)
            // Keep the active word around ~30% from the left edge so upcoming words are always visible ahead of time.
            val targetScroll = (cursorPx - effectiveWidth * 0.30f).coerceIn(0f, overflowPx)
            val currentScroll = -currentX

            val followFactor = if (dtSec > 0f) 1f - exp(-16f * dtSec) else 1f
            val nextScroll = (currentScroll + (targetScroll - currentScroll) * followFactor)
                .coerceIn(0f, overflowPx)
            currentX = -nextScroll

            val lastWordEnd = words.last().endMs
            if (mediaPosMs > lastWordEnd + 350f && abs(nextScroll - targetScroll) < 0.5f) {
                currentX = -targetScroll
                invalidate()
                stopScroll()
            }
            return
        }

        // Tier 2 & Tier 3: Line-duration / adaptive-tempo synchronized scrolling
        val durationWallMs = plan.durationWallMs.coerceAtLeast(450L)
        val overflowRatio = overflowPx / effectiveWidth.coerceAtLeast(1f)
        // For very long lines (overflow >= visible width), start scrolling almost immediately
        // so the extra text doesn't get cut off at the end of a short bar.
        val baseLeadMs = minOf(
            (plan.animationDurationMs * 0.30f).toLong() + 30L,
            (durationWallMs * 0.09f).toLong()
        ).coerceIn(40L, 200L)
        val leadHoldMs = if (overflowRatio >= 1.2f) {
            minOf(baseLeadMs, 60L)
        } else if (overflowRatio >= 0.7f) {
            minOf(baseLeadMs, 110L)
        } else {
            baseLeadMs
        }
        val tailHoldMs = (durationWallMs * 0.18f).toLong().coerceIn(220L, 650L)
        val scrollWindowMs = maxOf(220L, durationWallMs - leadHoldMs - tailHoldMs)

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val rawSpeedPxPerSec = overflowPx / (scrollWindowMs / 1000f)
        val minSpeedPxPerSec = 20f * density * dynamicPlaybackSpeed
        // Allow up to 800 dp/s so dense/long lines inside a short rap bar finish cleanly without throttling.
        val maxSpeedPxPerSec = 800f * density * dynamicPlaybackSpeed
        val effectiveSpeedPxPerSec = rawSpeedPxPerSec.coerceIn(minSpeedPxPerSec, maxSpeedPxPerSec)
        val effectiveScrollMs = ((overflowPx / effectiveSpeedPxPerSec) * 1000f).toLong().coerceAtLeast(180L)

        if (elapsedMs <= leadHoldMs) {
            currentX = 0f
            return
        }

        val scrollElapsed = elapsedMs - leadHoldMs
        if (scrollElapsed >= effectiveScrollMs) {
            currentX = -overflowPx
            invalidate()
            stopScroll()
            return
        }

        val t = (scrollElapsed.toFloat() / effectiveScrollMs.toFloat()).coerceIn(0f, 1f)
        val eased = trapezoidalEase(t, ramp = 0.05f)
        currentX = -overflowPx * eased
    }

    /**
     * C1-continuous trapezoidal velocity profile: soft acceleration over the first [ramp] fraction,
     * steady constant reading speed across the middle `1 - 2 * ramp`, and soft deceleration at the end.
     */
    private fun trapezoidalEase(t: Float, ramp: Float): Float {
        val denom = 1f - ramp
        return when {
            t <= 0f -> 0f
            t >= 1f -> 1f
            t < ramp -> (t * t) / (2f * ramp * denom)
            t > 1f - ramp -> {
                val u = 1f - t
                1f - (u * u) / (2f * ramp * denom)
            }
            else -> (t - ramp * 0.5f) / denom
        }
    }

    private fun computeWordCursorPx(words: List<MeasuredWord>, mediaPosMs: Float): Float {
        val first = words.first()
        if (mediaPosMs <= first.startMs) return first.xStart
        for (i in words.indices) {
            val w = words[i]
            if (mediaPosMs <= w.endMs) {
                val span = (w.endMs - w.startMs).coerceAtLeast(1L).toFloat()
                val frac = ((mediaPosMs - w.startMs) / span).coerceIn(0f, 1f)
                return w.xStart + (w.xEnd - w.xStart) * frac
            }
            if (i + 1 < words.size && mediaPosMs < words[i + 1].startMs) {
                return w.xEnd
            }
        }
        return textLength
    }

    private fun buildMeasuredWords(fullText: String, words: List<WordTiming>): List<MeasuredWord> {
        val result = ArrayList<MeasuredWord>(words.size)
        var searchCursor = 0
        var matchedAll = true
        for (w in words) {
            val idx = fullText.indexOf(w.text, startIndex = searchCursor)
            if (idx < 0) {
                matchedAll = false
                break
            }
            val endIdx = (idx + w.text.length).coerceAtMost(fullText.length)
            val xStart = paint.measureText(fullText, 0, idx)
            val xEnd = paint.measureText(fullText, 0, endIdx)
            result.add(MeasuredWord(w.startMs, w.endMs, xStart, xEnd))
            searchCursor = endIdx
        }
        if (matchedAll && result.size >= 2) {
            return result
        }

        // Fallback: proportional cumulative widths scaled to actual rendered textLength
        val rawWidths = words.map { paint.measureText(it.text).coerceAtLeast(1f) }
        val sumWidth = rawWidths.sum().coerceAtLeast(1f)
        val scale = textLength / sumWidth
        var accX = 0f
        return words.mapIndexed { idx, w ->
            val xStart = accX
            val xEnd = (accX + rawWidths[idx] * scale).coerceAtMost(textLength)
            accX = xEnd
            MeasuredWord(w.startMs, w.endMs, xStart, xEnd)
        }
    }

    private fun startScroll() {
        isScrolling = true
        startFrameNanos = 0L
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopScroll() {
        isScrolling = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun getTextLength(text: CharSequence): Float {
        return paint.measureText(text.toString())
    }

    fun setScrollSpeed(speed: Float) {
        this.scrollSpeed = speed
    }
}