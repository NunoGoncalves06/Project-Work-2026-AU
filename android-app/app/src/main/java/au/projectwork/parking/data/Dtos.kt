package au.projectwork.parking.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ignoreUnknownKeys: the backend may add fields without breaking the app
val ParkingJson = Json { ignoreUnknownKeys = true }

@Serializable
data class Address(
    val street: String,
    val houseNr: String,
    val city: String,
    // null until the lot has coordinates in the DB
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class LotSummary(
    val id: Int,
    val address: Address,
    val totalSpots: Int,
    val freeSpots: Int,
    val carsInside: Int,
    // "spots" (a sensor on every spot) or "entrance" (only a gate counting cars in and out: free = spots - carsInside)
    val countedBy: String = "spots",
) {
    val isEntrance get() = countedBy == "entrance"
}

@Serializable
data class Spot(val id: Int, val occupied: Boolean)

@Serializable
data class Row(val id: Int, val freeSpots: Int, val spots: List<Spot>)

@Serializable
data class LotDetail(
    val id: Int,
    val address: Address,
    val totalSpots: Int,
    val freeSpots: Int,
    val carsInside: Int,
    val rows: List<Row>,
    // empty until the lot has a few weeks of sensor history
    val forecast: List<Forecast> = emptyList(),
    val countedBy: String = "spots",
) {
    val isEntrance get() = countedBy == "entrance"
}

/** Free spots usually seen at [at] (an ISO instant, a full hour) on this weekday. */
@Serializable
data class Forecast(val at: String, val freeSpots: Int)
