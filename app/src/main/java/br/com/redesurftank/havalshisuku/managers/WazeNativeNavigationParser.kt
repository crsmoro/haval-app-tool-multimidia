package br.com.redesurftank.havalshisuku.managers

import kotlin.math.roundToInt

/** Normalizes the accessible next-street and next-distance fields from native Waze. */
object WazeNativeNavigationParser {
    const val UNKNOWN_TURN = "WAZE_UNKNOWN"

    private val distancePattern = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\s*$", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")

    fun parse(distanceText: String?, streetText: String?, turn: String?): AndroidAutoNavigationTelemetry.Directions? {
        val parsedDistance = parseDistance(distanceText) ?: return null
        val street = streetText.orEmpty().trim().replace(whitespace, " ")
        val normalizedTurn = when (turn?.trim()?.uppercase()) {
            "TURN_LEFT", "TURN_RIGHT", "STRAIGHT" -> turn.trim().uppercase()
            else -> UNKNOWN_TURN
        }
        if (street.isEmpty() && normalizedTurn == UNKNOWN_TURN) return null

        return AndroidAutoNavigationTelemetry.Directions(
            active = true,
            street = street,
            distance = parsedDistance.display,
            distanceM = parsedDistance.meters,
            turn = normalizedTurn
        )
    }

    private data class ParsedDistance(val display: String, val meters: Int)

    private fun parseDistance(text: String?): ParsedDistance? {
        val match = distancePattern.matchEntire(text.orEmpty()) ?: return null
        val numberText = match.groupValues[1].replace(',', '.')
        val value = numberText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val unit = match.groupValues[2].lowercase()
        val meters = value * if (unit == "km") 1_000.0 else 1.0
        if (!meters.isFinite() || meters > Int.MAX_VALUE) return null
        val canonicalValue = if (numberText.contains('.')) {
            numberText.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        } else {
            numberText
        }
        return ParsedDistance("$canonicalValue $unit", meters.roundToInt())
    }
}
