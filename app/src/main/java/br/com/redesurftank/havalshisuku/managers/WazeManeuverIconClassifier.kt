package br.com.redesurftank.havalshisuku.managers

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Conservative pixel-shape classifier for the native Waze maneuver ImageView. */
object WazeManeuverIconClassifier {
    /** Returns a maneuver token, or null when the crop is blank or ambiguous. */
    fun classify(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong()) return null

        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        var brightCount = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!isGlyphPixel(pixels[row + x])) continue
                brightCount++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (brightCount < max(12, width * height / 250)) return null

        val glyphHeight = maxY - minY + 1
        if (glyphHeight < 8 || maxX - minX + 1 < 5) return null

        // The top of the icon is its arrow head. Use only its first fifth, not the whole bent path.
        val headBottom = minY + (glyphHeight * 0.18f).roundToInt().coerceAtLeast(3)
        var headXSum = 0L
        var headPixels = 0
        for (y in minY..minOf(maxY, headBottom)) {
            val row = y * width
            for (x in minX..maxX) {
                if (isGlyphPixel(pixels[row + x])) {
                    headXSum += x
                    headPixels++
                }
            }
        }
        if (headPixels < 5) return null
        val headX = headXSum.toFloat() / headPixels

        // A clean turn has one long stem below the head. Multiple stems are common on U-turn and
        // roundabout artwork, so deliberately leave those unknown instead of guessing a side.
        val stemTop = minY + (glyphHeight * 0.55f).roundToInt()
        if (stemTop >= maxY) return null
        val stemRows = IntArray(width)
        for (x in minX..maxX) {
            var occupiedRows = 0
            for (y in stemTop..maxY) {
                val row = y * width
                if ((x > 0 && isGlyphPixel(pixels[row + x - 1])) ||
                    isGlyphPixel(pixels[row + x]) ||
                    (x + 1 < width && isGlyphPixel(pixels[row + x + 1]))) {
                    occupiedRows++
                }
            }
            stemRows[x] = occupiedRows
        }
        var stemX = -1
        var strongestStem = 0
        for (x in minX..maxX) {
            if (stemRows[x] > strongestStem) {
                stemX = x
                strongestStem = stemRows[x]
            }
        }
        val lowerRows = maxY - stemTop + 1
        if (stemX < 0 || strongestStem < max(5, (lowerRows * 0.45f).roundToInt())) return null

        val separatedPeakGap = max(3, (width * 0.12f).roundToInt())
        var secondStem = 0
        for (x in minX..maxX) {
            if (abs(x - stemX) > separatedPeakGap && stemRows[x] > secondStem) secondStem = stemRows[x]
        }
        if (secondStem >= (strongestStem * 0.65f).roundToInt()) return null

        val scale = (width - 1).coerceAtLeast(1).toFloat()
        val headRatio = headX / scale
        val stemRatio = stemX / scale
        return when {
            headRatio >= 0.60f && stemRatio <= 0.40f && headRatio - stemRatio >= 0.20f -> "TURN_RIGHT"
            headRatio <= 0.40f && stemRatio >= 0.60f && stemRatio - headRatio >= 0.20f -> "TURN_LEFT"
            headRatio in 0.42f..0.58f && stemRatio in 0.42f..0.58f && abs(headRatio - stemRatio) <= 0.16f -> "STRAIGHT"
            else -> null
        }
    }

    private fun isGlyphPixel(color: Int): Boolean {
        val alpha = color ushr 24
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        return alpha >= 128 && red >= 180 && green >= 180 && blue >= 180
    }
}
