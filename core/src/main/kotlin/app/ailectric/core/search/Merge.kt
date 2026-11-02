package app.ailectric.core.search

import app.ailectric.core.geo.Geo
import app.ailectric.core.model.Station
import kotlin.math.floor

/** Same place and no conflicting operator: sources often disagree by a few metres. */
private const val SAME_SPOT_M = 30.0

/** Sources place one site up to a car park apart, so a shared operator allows more distance. */
private const val SAME_OPERATOR_M = 100.0

/** About 110 m north-south, so a match can only be in the 3x3 cells around a station. */
private const val CELL_DEGREES = 0.001

private val OPERATOR_NOISE = setOf(
    "gmbh", "mbh", "ag", "se", "kg", "co", "ug", "ohg", "e", "v", "ev", "und", "the", "deutschland",
    "germany", "energie", "energy", "charging", "charge", "mobility", "mobilität", "stadtwerke", "werke",
    "smarter", "living", "services", "service", "solutions", "ladestation", "ladestationen",
)

private fun operatorTokens(operator: String?) =
    operator.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filterTo(hashSetOf()) { it.length > 1 && it !in OPERATOR_NOISE }

private class Entry(val station: Station) {
    val tokens = operatorTokens(station.operator)
}

private fun isDuplicate(a: Entry, b: Entry): Boolean {
    val meters = Geo.distanceMeters(a.station.location, b.station.location)
    return when {
        a.tokens.isEmpty() || b.tokens.isEmpty() -> meters <= SAME_SPOT_M
        a.tokens.any { it in b.tokens } -> meters <= SAME_OPERATOR_M
        else -> false
    }
}

/**
 * Joins the sources, richest first. A station stays unless an earlier source already has it.
 * Stations of one source are never merged with each other, and a duplicate is dropped whole, so
 * every field of a station keeps coming from the source that station names.
 */
fun mergeStations(sources: List<List<Station>>): List<Station> {
    val kept = ArrayList<Station>()
    val grid = HashMap<Pair<Int, Int>, MutableList<Entry>>()
    fun cell(s: Station) = floor(s.location.lat / CELL_DEGREES).toInt() to floor(s.location.lon / CELL_DEGREES).toInt()
    for (source in sources) {
        val added = source.map(::Entry).filter { entry ->
            val (lat, lon) = cell(entry.station)
            (lat - 1..lat + 1).none { y ->
                (lon - 2..lon + 2).any { x -> grid[y to x].orEmpty().any { isDuplicate(entry, it) } }
            }
        }
        for (entry in added) {
            kept += entry.station
            grid.getOrPut(cell(entry.station)) { mutableListOf() } += entry
        }
    }
    return kept
}
