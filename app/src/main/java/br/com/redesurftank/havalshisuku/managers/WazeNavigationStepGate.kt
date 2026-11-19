package br.com.redesurftank.havalshisuku.managers

/** Gates native Waze image captures to route steps rather than the changing distance countdown. */
internal class WazeNavigationStepGate {
    private var previousStreet: String? = null
    private var previousDistanceM: Int? = null

    fun shouldCapture(street: String, distanceM: Int): Boolean {
        val normalizedStreet = street.trim()
        val previousStreet = previousStreet
        val previousDistance = previousDistanceM
        val firstStep = previousStreet == null
        val streetChanged = previousStreet != null && normalizedStreet != previousStreet
        val distanceReset = previousDistance != null &&
            distanceM.toLong() - previousDistance.toLong() >= DISTANCE_RESET_THRESHOLD_M

        this.previousStreet = normalizedStreet
        previousDistanceM = distanceM
        return firstStep || streetChanged || distanceReset
    }

    fun reset() {
        previousStreet = null
        previousDistanceM = null
    }

    private companion object {
        const val DISTANCE_RESET_THRESHOLD_M = 40L
    }
}
