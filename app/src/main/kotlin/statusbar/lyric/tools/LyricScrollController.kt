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

package statusbar.lyric.tools

import kotlin.math.ceil

/**
 * Timed word segment supplied by SuperLyric 4.x (`Line.words`).
 */
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Scroll configuration computed for a single lyric line and consumed by [statusbar.lyric.view.LyricTextView].
 */
sealed class LyricScrollPlan {
    /**
     * Manual fixed-speed scrolling (refresh-rate independent, px/sec).
     */
    data class Fixed(
        val speedPxPerSec: Float,
        val leadHoldMs: Long,
        val targetWidthPx: Int
    ) : LyricScrollPlan()

    /**
     * Music-synchronized dynamic scrolling supporting:
     * 1. Word-by-word karaoke timing (`words` non-empty with valid spans)
     * 2. Line-duration timing (from `line.end`, next line start, or legacy `delay`)
     * 3. Adaptive per-song tempo estimation when a player pushes single sentences without timestamps
     */
    data class Dynamic(
        val durationWallMs: Long,
        val playbackSpeed: Float,
        val initialMediaPosMs: Long,
        val words: List<WordTiming>,
        val animationDurationMs: Long,
        val targetWidthPx: Int
    ) : LyricScrollPlan()
}

/**
 * Tracks per-song cadence and builds [LyricScrollPlan]s synchronized to music playback speed,
 * syllable/word density, SuperLyric timing metadata, and the user's layout configuration.
 */
class LyricTempoTracker {
    companion object {
        private const val DEFAULT_LINE_MEDIA_MS = 2600f
        private const val DEFAULT_LINE_UNITS = 10f
        private const val MIN_SAMPLE_ELAPSED_MS = 450L
        private const val MAX_SAMPLE_ELAPSED_MS = 10000L

        // Players typically fire lyric updates ~120-200ms after audio onset, and inter-line intervals
        // include breath/pause gaps between sentences. Compensating for both keeps scrolling on beat.
        private const val IPC_DELIVERY_LEAD_MS = 160f
        private const val EXPLICIT_END_DUTY = 0.90f
        private const val INTER_LINE_SINGING_DUTY = 0.78f

        /**
         * Estimates spoken/sung syllable-like reading units across CJK, Kana, Hangul, Latin,
         * Cyrillic, digits, and internal clause punctuation so different scripts map to
         * comparable singing durations.
         */
        fun estimateReadingUnits(text: String): Float {
            if (text.isBlank()) return 1f
            var units = 0f
            var latinRun = 0

            fun flushLatinRun() {
                if (latinRun <= 0) return
                units += when {
                    latinRun <= 3 -> 1.0f
                    latinRun <= 6 -> 1.6f
                    else -> (ceil(latinRun / 3.2f)).coerceAtMost(4.5f)
                }
                latinRun = 0
            }

            for (ch in text) {
                when {
                    isCjkOrSyllabic(ch) -> {
                        flushLatinRun()
                        units += 1.0f
                    }
                    ch.isLetterOrDigit() || ch == '\'' -> {
                        latinRun++
                    }
                    ch in ",.;!?，。、！？/—–~" -> {
                        flushLatinRun()
                        units += 0.45f
                    }
                    else -> {
                        flushLatinRun()
                    }
                }
            }
            flushLatinRun()
            return units.coerceAtLeast(1f)
        }

        private fun isCjkOrSyllabic(ch: Char): Boolean {
            val code = ch.code
            return (code in 0x4E00..0x9FFF) || // CJK Unified Ideographs
                (code in 0x3400..0x4DBF) ||    // CJK Extension A
                (code in 0x3040..0x309F) ||    // Hiragana
                (code in 0x30A0..0x30FF) ||    // Katakana
                (code in 0xAC00..0xD7AF) ||    // Hangul Syllables
                (code in 0x3130..0x318F)       // Hangul Compatibility Jamo
        }

        fun sanitizePlaybackSpeed(speed: Float): Float {
            return if (speed.isFinite() && speed in 0.25f..4.0f) speed else 1.0f
        }
    }

    private var currentSongKey: String = ""
    private var emaLineMediaMs: Float = DEFAULT_LINE_MEDIA_MS
    private var emaLineUnits: Float = DEFAULT_LINE_UNITS
    private var sampleCount: Int = 0
    private var lastLineWallMs: Long = 0L
    private var lastLineUnits: Float = 0f
    private var lastLyricText: String = ""

    fun reset() {
        currentSongKey = ""
        emaLineMediaMs = DEFAULT_LINE_MEDIA_MS
        emaLineUnits = DEFAULT_LINE_UNITS
        sampleCount = 0
        lastLineWallMs = 0L
        lastLineUnits = 0f
        lastLyricText = ""
    }

    /**
     * Records a newly arrived lyric line and computes its dynamic [LyricScrollPlan.Dynamic].
     */
    fun buildDynamicPlan(
        songKey: String,
        lyricText: String,
        nowWallMs: Long,
        playbackSpeedRaw: Float,
        positionMs: Long,
        lineStartMs: Long,
        lineEndMs: Long,
        nextLineStartMs: Long,
        songDurationMs: Long,
        legacyDelayMs: Int,
        words: List<WordTiming>,
        animationDurationMs: Long,
        targetWidthPx: Int
    ): LyricScrollPlan.Dynamic {
        val playbackSpeed = sanitizePlaybackSpeed(playbackSpeedRaw)

        if (songKey.isNotEmpty() && songKey != currentSongKey) {
            currentSongKey = songKey
            emaLineMediaMs = DEFAULT_LINE_MEDIA_MS
            emaLineUnits = DEFAULT_LINE_UNITS
            sampleCount = 0
            lastLineWallMs = 0L
            lastLineUnits = 0f
            lastLyricText = ""
        }

        val currentUnits = estimateReadingUnits(lyricText)

        // Learn per-song bar duration and average sentence density from inter-line wall-clock timing.
        if (lastLineWallMs > 0L && lastLineUnits > 0f && lyricText != lastLyricText) {
            val wallElapsed = nowWallMs - lastLineWallMs
            if (wallElapsed in MIN_SAMPLE_ELAPSED_MS..MAX_SAMPLE_ELAPSED_MS) {
                val mediaElapsed = (wallElapsed * playbackSpeed).coerceIn(600f, 8000f)
                val alpha = if (sampleCount < 2) 0.60f else 0.35f
                emaLineMediaMs = emaLineMediaMs * (1f - alpha) + mediaElapsed * alpha
                emaLineUnits = emaLineUnits * (1f - alpha) + lastLineUnits * alpha
                sampleCount++
            }
        }

        // Filter valid timed words (non-empty text, non-negative ordered span).
        val validWords = words.filter { it.text.isNotEmpty() && it.endMs > it.startMs }

        // Determine current playback anchor within this line.
        val effectiveStartMs = when {
            lineStartMs > 0L -> lineStartMs
            validWords.isNotEmpty() -> validWords.first().startMs
            else -> 0L
        }
        val anchorPosMs = when {
            positionMs > 0L && effectiveStartMs > 0L && positionMs >= effectiveStartMs -> positionMs
            effectiveStartMs > 0L -> effectiveStartMs
            positionMs > 0L -> positionMs
            else -> 0L
        }

        // Natural singing duration bounds for this sentence (in media ms).
        val minSingMediaMs = maxOf(650f, currentUnits * 95f)
        val maxSingMediaMs = maxOf(2800f, currentUnits * 380f).coerceAtMost(7500f)

        // Also learn bar duration from explicit line/word durations when available.
        val explicitSpanMs = when {
            lineEndMs > effectiveStartMs && effectiveStartMs >= 0L -> lineEndMs - effectiveStartMs
            validWords.size >= 2 && validWords.last().endMs > validWords.first().startMs ->
                validWords.last().endMs - validWords.first().startMs
            else -> 0L
        }
        if (explicitSpanMs in 500L..9000L) {
            val alpha = if (sampleCount < 2) 0.60f else 0.35f
            emaLineMediaMs = emaLineMediaMs * (1f - alpha) + explicitSpanMs.toFloat() * alpha
            emaLineUnits = emaLineUnits * (1f - alpha) + currentUnits * alpha
            sampleCount++
        }

        val remainingMediaMs: Float = when {
            // 1. Explicit line end timestamp from SuperLyric
            lineEndMs > anchorPosMs -> {
                val raw = (lineEndMs - anchorPosMs).toFloat() * EXPLICIT_END_DUTY - IPC_DELIVERY_LEAD_MS
                raw.coerceIn(550f, maxOf(maxSingMediaMs * 1.20f, 8500f))
            }
            // 2. Word-level end timestamp
            validWords.isNotEmpty() && validWords.last().endMs > anchorPosMs -> {
                val raw = (validWords.last().endMs - anchorPosMs).toFloat() * EXPLICIT_END_DUTY - IPC_DELIVERY_LEAD_MS
                raw.coerceIn(550f, maxOf(maxSingMediaMs * 1.20f, 8500f))
            }
            // 3. Inferred from next line's start timestamp (strip inter-line breath gap & cap instrumental breaks)
            nextLineStartMs > anchorPosMs -> {
                val raw = (nextLineStartMs - anchorPosMs).toFloat() * INTER_LINE_SINGING_DUTY - IPC_DELIVERY_LEAD_MS
                raw.coerceIn(minOf(raw, minSingMediaMs).coerceAtLeast(550f), maxSingMediaMs)
            }
            // 4. Final line bounded by total song duration
            songDurationMs > anchorPosMs && (songDurationMs - anchorPosMs) <= 15000L -> {
                val raw = (songDurationMs - anchorPosMs).toFloat() * INTER_LINE_SINGING_DUTY - IPC_DELIVERY_LEAD_MS
                raw.coerceIn(550f, maxSingMediaMs)
            }
            // 5. Legacy SuperLyric delay hint (ms)
            legacyDelayMs > 0 -> {
                val raw = legacyDelayMs.toFloat() * EXPLICIT_END_DUTY - IPC_DELIVERY_LEAD_MS
                raw.coerceIn(550f, maxOf(maxSingMediaMs * 1.20f, 8500f))
            }
            // 6. Single-sentence push without timestamps:
            // Music lines follow a musical bar cadence — packing more words into a bar speeds up
            // singing rather than multiplying bar length linearly, so scale sub-linearly by ratio^0.35.
            else -> {
                val unitRatio = (currentUnits / emaLineUnits.coerceAtLeast(4f)).coerceIn(0.45f, 2.5f)
                val barScale = Math.pow(unitRatio.toDouble(), 0.35).toFloat()
                val raw = emaLineMediaMs * barScale * INTER_LINE_SINGING_DUTY - IPC_DELIVERY_LEAD_MS
                val upperBound = if (sampleCount > 0) {
                    (emaLineMediaMs * 1.10f).coerceIn(1800f, 4500f)
                } else {
                    3200f
                }
                raw.coerceIn(750f, upperBound)
            }
        }

        val durationWallMs = (remainingMediaMs / playbackSpeed).toLong().coerceIn(450L, 10000L)

        // Update tracker state for the next sentence.
        if (lyricText != lastLyricText) {
            lastLineWallMs = nowWallMs
            lastLineUnits = currentUnits
            lastLyricText = lyricText
        }

        // Only enable word-by-word cursor mode when we have >= 2 timed words and a coherent anchor.
        val usableWords = if (validWords.size >= 2 && validWords.last().endMs > anchorPosMs) {
            validWords
        } else {
            emptyList()
        }

        return LyricScrollPlan.Dynamic(
            durationWallMs = durationWallMs,
            playbackSpeed = playbackSpeed,
            initialMediaPosMs = anchorPosMs,
            words = usableWords,
            animationDurationMs = animationDurationMs.coerceAtLeast(0L),
            targetWidthPx = targetWidthPx
        )
    }
}
