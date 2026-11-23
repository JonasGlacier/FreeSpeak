package com.freespeak.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.freespeak.ime.R
import kotlin.math.abs
import kotlin.math.sin

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class VisualMode {
        IDLE,
        LISTENING,
        TRANSCRIBING,
    }

    private val barCount = 17
    private val barValues = FloatArray(barCount) { 0.12f }
    private val rect = RectF()

    // Drawn on the mic circle: uses mic foreground color, brighter while speech is detected.
    private var baseColor = ContextCompat.getColor(context, R.color.ime_mic_fg)
    private var idleColor = ColorUtils.setAlphaComponent(baseColor, 0x80)
    private var activeColor = ColorUtils.setAlphaComponent(baseColor, 0xB3)
    private var speechColor = baseColor

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = idleColor
    }

    private var mode: VisualMode = VisualMode.IDLE
    private var speechActive: Boolean = false
    private var phase: Float = 0f
    private var targetAmplitude = 0.14f
    private var listeningFrameScheduled = false
    private val listeningFrame = object : Runnable {
        override fun run() {
            listeningFrameScheduled = false
            if (mode != VisualMode.LISTENING || !isShown || !isAttachedToWindow) return

            if (advanceListeningFrame()) {
                postInvalidateOnAnimation()
                scheduleListeningFrame()
            }
        }
    }

    fun setForegroundColor(fgColor: Int) {
        if (baseColor == fgColor) return
        baseColor = fgColor
        idleColor = ColorUtils.setAlphaComponent(fgColor, 0x80)
        activeColor = ColorUtils.setAlphaComponent(fgColor, 0xB3)
        speechColor = fgColor
        postInvalidateOnAnimation()
    }

    fun setVisualMode(newMode: VisualMode) {
        if (mode == newMode) return
        stopListeningFrames()
        mode = newMode
        targetAmplitude = 0.14f
        if (newMode == VisualMode.IDLE) {
            barValues.fill(0.12f)
            speechActive = false
        }
        scheduleListeningFrame()
        postInvalidateOnAnimation()
    }

    fun pushAudioFrame(normalizedRms: Float, isSpeech: Boolean) {
        val speechChanged = speechActive != isSpeech
        speechActive = isSpeech
        if (mode != VisualMode.LISTENING) return

        targetAmplitude = (0.14f + normalizedRms * 0.86f).coerceIn(0.12f, 1.0f)
        if (speechChanged) postInvalidateOnAnimation()
        scheduleListeningFrame()
    }

    private fun scheduleListeningFrame() {
        if (mode != VisualMode.LISTENING || listeningFrameScheduled ||
            !isAttachedToWindow || !isShown
        ) return
        listeningFrameScheduled = true
        postDelayed(listeningFrame, 32L)
    }

    private fun stopListeningFrames() {
        removeCallbacks(listeningFrame)
        listeningFrameScheduled = false
    }

    private fun advanceListeningFrame(): Boolean {
        // Advance independently of StateFlow emissions: repeated silence levels are conflated.
        // Shift from center outward until the waveform settles, then let the timer sleep.
        var changed = false
        fun updateBar(index: Int, value: Float) {
            if (barValues[index] != value) changed = true
            barValues[index] = value
        }
        val center = barCount / 2
        for (i in 0 until center) {
            updateBar(i, (barValues[i + 1] * 0.92f).coerceAtLeast(0.12f))
        }
        for (i in barCount - 1 downTo center + 1) {
            updateBar(i, (barValues[i - 1] * 0.92f).coerceAtLeast(0.12f))
        }
        val delta = targetAmplitude - barValues[center]
        updateBar(center, if (abs(delta) < 0.001f) targetAmplitude else barValues[center] + delta * 0.45f)
        return changed
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleListeningFrame()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) scheduleListeningFrame() else stopListeningFrames()
    }

    override fun onDetachedFromWindow() {
        stopListeningFrames()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val barWidth = (6f * density)
        val gap = (3.5f * density)
        val totalBarsWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = (w - totalBarsWidth) * 0.5f
        val centerY = h * 0.5f
        val maxHalfHeight = (h * 0.48f).coerceAtLeast(4f * density)
        val corner = barWidth * 0.5f

        paint.color = when (mode) {
            VisualMode.IDLE -> idleColor
            VisualMode.LISTENING -> if (speechActive) speechColor else activeColor
            VisualMode.TRANSCRIBING -> activeColor
        }

        if (mode == VisualMode.TRANSCRIBING) {
            phase += 0.18f
            val centerIdx = (barCount - 1) * 0.5f
            for (i in 0 until barCount) {
                val dist = abs(i - centerIdx) / centerIdx
                val wave = (sin((phase - i * 0.35f).toDouble()).toFloat() * 0.5f + 0.5f)
                val amp = (0.18f + wave * 0.65f * (1f - dist * 0.45f)).coerceIn(0.14f, 0.9f)
                val halfBar = maxHalfHeight * amp
                val left = startX + i * (barWidth + gap)
                rect.set(left, centerY - halfBar, left + barWidth, centerY + halfBar)
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
            postInvalidateDelayed(32L)
            return
        }

        for (i in 0 until barCount) {
            val amp = if (mode == VisualMode.IDLE) 0.14f else barValues[i]
            val halfBar = (maxHalfHeight * amp).coerceAtLeast(2.5f * density)
            val left = startX + i * (barWidth + gap)
            rect.set(left, centerY - halfBar, left + barWidth, centerY + halfBar)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
    }
}
