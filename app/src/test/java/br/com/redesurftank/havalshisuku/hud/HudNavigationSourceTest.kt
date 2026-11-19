package br.com.redesurftank.havalshisuku.hud

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry.Directions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudNavigationSourceTest {
    @Test
    fun nativeWazeTakesPrecedenceOverAndroidAuto() {
        val androidAuto = Directions(active = true, street = "AA", distance = "100 m", distanceM = 100, turn = "TURN_LEFT")
        val nativeWaze = Directions(active = true, street = "Waze", distance = "50 m", distanceM = 50, turn = "TURN_RIGHT")

        val selection = HudNavigationSource.select(androidAuto, nativeWaze)

        assertEquals(HudNavigationSource.Kind.NATIVE_WAZE, selection.kind)
        assertEquals("Waze", selection.directions!!.street)
    }

    @Test
    fun androidAutoIsRestoredWhenNativeWazeClears() {
        val androidAuto = Directions(active = true, street = "AA", distance = "100 m", distanceM = 100, turn = "TURN_LEFT")

        val selection = HudNavigationSource.select(androidAuto, null)

        assertEquals(HudNavigationSource.Kind.ANDROID_AUTO, selection.kind)
        assertEquals(androidAuto, selection.directions)
    }

    @Test
    fun inactiveNativeWazeDoesNotMaskActiveAndroidAuto() {
        val androidAuto = Directions(active = true, street = "AA", distance = "100 m", distanceM = 100, turn = "TURN_LEFT")
        val inactiveWaze = Directions(active = false, street = "", distance = "", turn = "")

        val selection = HudNavigationSource.select(androidAuto, inactiveWaze)

        assertEquals(HudNavigationSource.Kind.ANDROID_AUTO, selection.kind)
        assertEquals(androidAuto, selection.directions)
    }

    @Test
    fun noActiveSourceReturnsNone() {
        val inactiveWaze = Directions(active = false, street = "", distance = "", turn = "")

        val selection = HudNavigationSource.select(null, inactiveWaze)

        assertEquals(HudNavigationSource.Kind.NONE, selection.kind)
        assertNull(selection.directions)
    }
}
