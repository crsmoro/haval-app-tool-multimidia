package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeNavigationStepGateTest {
    @Test
    fun capturesInitialStepAndDistanceResetsButNotOrdinaryCountdowns() {
        val gate = WazeNavigationStepGate()

        assertTrue(gate.shouldCapture("Rua A", 500))
        assertFalse(gate.shouldCapture("Rua A", 450))
        assertFalse(gate.shouldCapture("Rua A", 100))
        assertTrue(gate.shouldCapture("Rua A", 600))
    }

    @Test
    fun capturesStreetChangesAndRouteRestartAfterReset() {
        val gate = WazeNavigationStepGate()

        assertTrue(gate.shouldCapture("Rua A", 250))
        assertTrue(gate.shouldCapture("Rua B", 200))
        gate.reset()
        assertTrue(gate.shouldCapture("Rua B", 200))
    }
}
