package com.voicekb.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 截图里那种条状声波。喂入 0~1 的振幅，向左滚动。 */
class WaveView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val amps = ArrayDeque<Float>()
    private val maxBars = 64
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F8CFF")
    }

    fun push(amp: Float) {
        amps.addLast(amp.coerceIn(0f, 1f))
        while (amps.size > maxBars) amps.removeFirst()
        invalidate()
    }
    fun clear() { amps.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amps.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val bar = w / maxBars
        val barW = bar * 0.55f
        amps.forEachIndexed { i, a ->
            val bh = (a * h).coerceAtLeast(3f)
            val x = i * bar
            canvas.drawRoundRect(x, (h - bh) / 2, x + barW, (h + bh) / 2,
                3f, 3f, paint)
        }
    }
}
