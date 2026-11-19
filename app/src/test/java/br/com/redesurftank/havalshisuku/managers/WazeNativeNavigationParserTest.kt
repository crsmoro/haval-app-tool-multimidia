package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeNativeNavigationParserTest {
    @Test
    fun parsesNextStreetAndMetricDistanceWithRecognizedManeuver() {
        val directions = WazeNativeNavigationParser.parse(
            distanceText = "50 m",
            streetText = "R. Almirante Jaceguay",
            turn = "TURN_RIGHT"
        )

        assertTrue(directions!!.active)
        assertEquals("R. Almirante Jaceguay", directions.street)
        assertEquals("50 m", directions.distance)
        assertEquals(50, directions.distanceM)
        assertEquals("TURN_RIGHT", directions.turn)
    }

    @Test
    fun parsesLocalizedKilometers() {
        val directions = WazeNativeNavigationParser.parse(
            distanceText = "1,3 km",
            streetText = "Av. Brasil",
            turn = "TURN_LEFT"
        )

        assertEquals(1_300, directions!!.distanceM)
        assertEquals("1.3 km", directions.distance)
    }

    @Test
    fun keepsDistanceAndStreetWhenIconDirectionIsUnknown() {
        val directions = WazeNativeNavigationParser.parse(
            distanceText = "50 m",
            streetText = "R. Almirante Jaceguay",
            turn = ""
        )

        assertTrue(directions!!.active)
        assertEquals(WazeNativeNavigationParser.UNKNOWN_TURN, directions.turn)
        assertEquals(50, directions.distanceM)
    }

    @Test
    fun rejectsNonNavigationWazeScreens() {
        assertNull(WazeNativeNavigationParser.parse("", "Buscar", ""))
        assertNull(WazeNativeNavigationParser.parse("Em seguida", "", ""))
    }

    @Test
    fun normalizesWhitespaceInStreetName() {
        val directions = WazeNativeNavigationParser.parse(" 200 m ", "  Av.   Brasil ", "STRAIGHT")
        assertEquals("Av. Brasil", directions!!.street)
        assertEquals(200, directions.distanceM)
    }
}
