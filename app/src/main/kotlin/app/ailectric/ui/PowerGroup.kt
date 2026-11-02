package app.ailectric.ui

import android.graphics.Color

private val DARK_TEXT = Color.rgb(20, 20, 20)

/**
 * Colour of a charger by speed, shared by the car's tiles and the map. Tiles run from [lightColor]
 * to [darkColor]; [onColor] has to stay readable on both.
 */
enum class PowerGroup(
    val fromKw: Double,
    val label: String,
    val color: Int,
    val onColor: Int,
    val lightColor: Int,
    val darkColor: Int,
) {
    UNKNOWN(0.0, "?", Color.rgb(74, 80, 86), Color.WHITE, Color.rgb(90, 97, 104), Color.rgb(58, 63, 69)),
    SLOW(0.01, "bis 22", 0xFFA4C96B.toInt(), DARK_TEXT, 0xFFA4C96B.toInt(), 0xFF83A756.toInt()),
    FAST(23.0, "23–149", 0xFF45B69C.toInt(), DARK_TEXT, 0xFF45B69C.toInt(), 0xFF348975.toInt()),
    RAPID(150.0, "150–299", 0xFF2989B9.toInt(), Color.WHITE, 0xFF2989B9.toInt(), 0xFF1F678B.toInt()),
    ULTRA(300.0, "ab 300 kW", 0xFF6746A0.toInt(), Color.WHITE, 0xFF6746A0.toInt(), 0xFF4D3578.toInt());

    companion object {
        /** Slowest first, the unit comes last, and "?" stands apart at the end. */
        val LEGEND = entries.drop(1) + UNKNOWN

        fun of(kw: Double?) = entries.last { (kw ?: 0.0) >= it.fromKw }
    }
}
