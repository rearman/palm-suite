package com.rearman.palmlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.roundToInt

// The Palm OS 3.5 battery gauge of the Palm IIIxe Applications screen, drawn on the Palm px grid: a
// 1 px black outline with cut corners, the charge in solid black from the left with a 1 px paper
// highlight line, the empty part in a 50% dither, and an outlined nub at the right. While the phone
// charges, a lightning bolt shows across the body, in paper over the charge and black over the
// dither. `pp` converts Palm px to screen px.
class BatteryGauge(ctx: Context, private val pp: (Float) -> Float, private val paper: Int) : View(ctx) {
	companion object {
		// Sizes in Palm px, measured from a IIIxe photo (adjustable).
		const val BODY_W = 30
		const val BODY_H = 9
		const val NUB_W = 3
		const val NUB_H = 5
		const val HIGHLIGHT_ROW = 3 // the paper line in the charge, rows from the top of the body

		// Bolt corners as fractions of the body width and height.
		private val BOLT = floatArrayOf(
			0.58f, 0.05f, 0.30f, 0.58f, 0.48f, 0.58f, 0.40f, 0.95f, 0.72f, 0.40f, 0.54f, 0.40f, 0.64f, 0.05f,
		)
	}

	private var level = 100 // percent, 0 to 100
	private var charging = false
	private val paint = Paint().apply { style = Paint.Style.FILL }

	fun set(level: Int, charging: Boolean) {
		val l = level.coerceIn(0, 100)
		if (l == this.level && charging == this.charging) return
		this.level = l
		this.charging = charging
		invalidate()
	}

	override fun onMeasure(widthSpec: Int, heightSpec: Int) =
		setMeasuredDimension((pp(1f) * (BODY_W + NUB_W)).roundToInt(), (pp(1f) * BODY_H).roundToInt())

	override fun onDraw(c: Canvas) {
		val s = pp(1f)
		// Palm px cells at grid (x, y), w wide and h high.
		fun px(x: Int, y: Int, w: Int = 1, h: Int = 1) = c.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, paint)
		val inW = BODY_W - 2
		val inH = BODY_H - 2
		val fill = (inW * level / 100f).roundToInt()
		paint.color = Color.BLACK
		// The outline, without the four corner cells.
		px(1, 0, BODY_W - 2)
		px(1, BODY_H - 1, BODY_W - 2)
		px(0, 1, 1, inH)
		px(BODY_W - 1, 1, 1, inH)
		// The nub, centered on the body: outlined, with cut outer corners and a paper center.
		val nubTop = (BODY_H - NUB_H) / 2
		px(BODY_W, nubTop, NUB_W - 1, NUB_H)
		px(BODY_W + NUB_W - 1, nubTop + 1, 1, NUB_H - 2)
		// The charge, then the empty part in a 50% dither.
		if (fill > 0) px(1, 1, fill, inH)
		for (x in 1 + fill..inW) for (y in 1..inH) if ((x + y) % 2 == 0) px(x, y)
		paint.color = paper
		px(BODY_W + 1, nubTop + 1, NUB_W - 2, NUB_H - 2)
		if (fill > 1) px(1, HIGHLIGHT_ROW, fill - 1, 1)
		if (!charging) return
		val bw = BODY_W * s
		val bh = BODY_H * s
		val chargeRight = (1 + fill) * s
		val bolt = Path()
		for (i in BOLT.indices step 2) {
			val x = BOLT[i] * bw
			val y = BOLT[i + 1] * bh
			if (i == 0) bolt.moveTo(x, y) else bolt.lineTo(x, y)
		}
		bolt.close()
		c.save()
		c.clipRect(0f, 0f, chargeRight, bh)
		paint.color = paper
		c.drawPath(bolt, paint)
		c.restore()
		c.save()
		c.clipOutRect(0f, 0f, chargeRight, bh)
		paint.color = Color.BLACK
		c.drawPath(bolt, paint)
		c.restore()
	}
}
