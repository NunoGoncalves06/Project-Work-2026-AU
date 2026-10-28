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
)

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
)
