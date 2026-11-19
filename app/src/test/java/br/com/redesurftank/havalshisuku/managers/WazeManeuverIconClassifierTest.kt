package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeManeuverIconClassifierTest {
    @Test
    fun classifiesLeftAndRightFromArrowHeadAndStem() {
        assertEquals("TURN_RIGHT", WazeManeuverIconClassifier.classify(icon(tipX = 44, stemX = 14), SIZE, SIZE))
        assertEquals("TURN_LEFT", WazeManeuverIconClassifier.classify(icon(tipX = 15, stemX = 45), SIZE, SIZE))
    }

    @Test
    fun classifiesCenteredArrowAsStraight() {
        assertEquals("STRAIGHT", WazeManeuverIconClassifier.classify(icon(tipX = 30, stemX = 30), SIZE, SIZE))
    }

    @Test
    fun rejectsBlankAndAmbiguousIcons() {
        val blank = IntArray(SIZE * SIZE) { BLACK }
        assertNull(WazeManeuverIconClassifier.classify(blank, SIZE, SIZE))
        assertNull(WazeManeuverIconClassifier.classify(icon(tipX = 44, stemX = 14, secondStemX = 45), SIZE, SIZE))
    }

    private fun icon(tipX: Int, stemX: Int, secondStemX: Int? = null): IntArray {
        val pixels = IntArray(SIZE * SIZE) { BLACK }
        for (y in 25 until SIZE - 2) {
            for (x in (stemX - 1)..(stemX + 1)) pixels[y * SIZE + x] = WHITE
            secondStemX?.let { x0 ->
                for (x in (x0 - 1)..(x0 + 1)) pixels[y * SIZE + x] = WHITE
            }
        }
        // A filled upward-facing arrow head in the crop's top band.
        for (y in 3..15) {
            val halfWidth = (y - 3) / 2
            for (x in (tipX - halfWidth)..(tipX + halfWidth)) pixels[y * SIZE + x] = WHITE
        }
        return pixels
    }

    companion object {
        private const val SIZE = 60
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
