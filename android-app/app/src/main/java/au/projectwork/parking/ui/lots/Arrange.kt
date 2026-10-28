package au.projectwork.parking.ui.lots

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotSummary
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The phone's position (WGS84). */
data class Here(val latitude: Double, val longitude: Double)

enum class LotFilter { Nearby, Favourites, All }

data class LotRow(val lot: LotSummary, val distanceMeters: Double?, val favourite: Boolean)

/** Why the big ticket shows this lot. */
enum class HeroReason { Nearest, Favourite, MostSpace }

data class Hero(val row: LotRow, val reason: HeroReason)

/** Great-circle distance; null when the lot has no coordinates or we don't know where the phone is. */
fun distanceMeters(here: Here?, address: Address): Double? {
    val lat = address.latitude ?: return null
    val lon = address.longitude ?: return null
    if (here == null) return null
    val p1 = Math.toRadians(here.latitude)
    val p2 = Math.toRadians(lat)
    val dp = p2 - p1
    val dl = Math.toRadians(lon - here.longitude)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(a))
}

fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${(meters / 10).roundToInt() * 10} m"
    // Locale.ROOT: the UI is English, and a Danish default locale would print "1,2 km"
    meters < 10_000 -> String.format(Locale.ROOT, "%.1f km", meters / 1_000)
    else -> "${(meters / 1_000).roundToInt()} km"
}

private fun rows(lots: List<LotSummary>, favourites: Set<Int>, here: Here?) =
    lots.map { LotRow(it, distanceMeters(here, it.address), it.id in favourites) }

/**
 * The one lot to drive to: the nearest with a free spot, else a favourite with the most space, else the lot
 * with the most space. Null when every lot is full.
 */
fun pickHero(lots: List<LotSummary>, favourites: Set<Int>, here: Here?): Hero? {
    val open = rows(lots, favourites, here).filter { it.lot.freeSpots > 0 }
    open.filter { it.distanceMeters != null }.minByOrNull { it.distanceMeters!! }?.let { return Hero(it, HeroReason.Nearest) }
    open.filter { it.favourite }.maxByOrNull { it.lot.freeSpots }?.let { return Hero(it, HeroReason.Favourite) }
    return open.maxByOrNull { it.lot.freeSpots }?.let { Hero(it, HeroReason.MostSpace) }
}

/** The list under the big ticket: [filter] applied, closest first when we know distances, then by street. */
fun arrange(lots: List<LotSummary>, favourites: Set<Int>, here: Here?, filter: LotFilter, excludeId: Int?): List<LotRow> =
    rows(lots, favourites, here)
        .filter { it.lot.id != excludeId && (filter != LotFilter.Favourites || it.favourite) }
        .sortedWith(
            if (filter == LotFilter.All) compareBy { it.lot.address.street }
            else compareBy<LotRow, Double?>(nullsLast()) { it.distanceMeters }.thenBy { it.lot.address.street },
        )
