package com.example.cdplayer

import android.content.Context
import android.graphics.*
import android.view.View

class DiscView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun c(s: String) = Color.parseColor(s)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.97f

        p.style = Paint.Style.FILL
        p.shader = SweepGradient(cx, cy, intArrayOf(
            c("#E9ECF1"), c("#8E949F"), c("#F7F8FA"), c("#7F8591"), c("#E9ECF1")), null)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = Color.argb(40, 0, 0, 0)
        var rr = r * 0.40f
        while (rr < r * 0.95f) { canvas.drawCircle(cx, cy, rr, p); rr += r * 0.045f }

        p.strokeWidth = r * 0.035f
        p.color = c("#E53935")
        canvas.drawCircle(cx, cy, r * 0.34f, p)

        p.style = Paint.Style.FILL
        p.color = c("#1A1D23")
        canvas.drawCircle(cx, cy, r * 0.30f, p)
        p.color = c("#C9CED6")
        canvas.drawCircle(cx, cy, r * 0.16f, p)
        p.color = c("#101216")
        canvas.drawCircle(cx, cy, r * 0.06f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = c("#3A3F48")
        canvas.drawCircle(cx, cy, r, p)
    }
}
