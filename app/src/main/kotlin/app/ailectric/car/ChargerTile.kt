package app.ailectric.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.car.app.model.CarIcon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import app.ailectric.core.model.Station
import app.ailectric.ui.Format
import app.ailectric.ui.PowerGroup

/** The rounded square in the car's grid: power, free points, gradient by speed group. */
internal object ChargerTile {
    /** Rounded corners need alpha; at this size 18 tiles take about 700 KB of one binder call. */
    private const val SIZE_PX = 100
    private const val CORNER_RATIO = 0.2f
    /** The same side margin keeps "400 kW" and a shrunk four-digit power off the edge. */
    private const val TEXT_WIDTH_RATIO = 0.86f

    fun icon(s: Station): CarIcon =
        CarIcon.Builder(IconCompat.createWithBitmap(bitmap(s))).build()

    fun bitmap(s: Station, sizePx: Int = SIZE_PX): Bitmap {
        val group = PowerGroup.of(s.maxPowerKw)
        val size = sizePx.toFloat()
        val corner = size * CORNER_RATIO
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size, size, group.lightColor, group.darkColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(0f, 0f, size, size, corner, corner, fill)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = group.onColor }
        drawPower(canvas, paint, Format.powerShort(s), size)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT
        paint.textSize = size * 0.2f
        canvas.drawText(Format.freeShort(s), size / 2, size * 0.68f, paint)
        paint.textSize = size * 0.125f
        canvas.drawText("frei", size / 2, size * 0.85f, paint)
        return bitmap
    }

    /** Figure and small unit share a baseline and are centred together. */
    private fun drawPower(canvas: Canvas, paint: Paint, figure: String, size: Float) {
        val gap = size * 0.025f
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * 0.31f
        val unitRatio = 0.44f
        val full = paint.measureText(figure) + gap + paint.measureText(UNIT) * unitRatio
        paint.textSize *= (size * TEXT_WIDTH_RATIO / full).coerceAtMost(1f)
        val figureWidth = paint.measureText(figure)
        val figureSize = paint.textSize
        val width = figureWidth + gap + paint.measureText(UNIT) * unitRatio
        val left = (size - width) / 2
        val baseline = size * 0.44f
        canvas.drawText(figure, left, baseline, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = figureSize * unitRatio
        canvas.drawText(UNIT, left + figureWidth + gap, baseline, paint)
    }

    private const val UNIT = "kW"
}
