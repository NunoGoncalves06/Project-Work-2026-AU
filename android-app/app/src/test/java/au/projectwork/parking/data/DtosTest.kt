package au.projectwork.parking.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DtosTest {

    // exact examples from docs/api-status.md / the spec, so a renamed key on either side fails here
    @Test
    fun decodesLotList() {
        val json = """
            [{ "id": 1,
               "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
               "totalSpots": 16, "freeSpots": 12, "carsInside": 4 }]
        """
        val lots = ParkingJson.decodeFromString<List<LotSummary>>(json)
        assertEquals(
            listOf(LotSummary(1, Address("Finlandsgade", "22", "Aarhus"), 16, 12, 4)),
            lots,
        )
    }

    @Test
    fun decodesLotDetailAndIgnoresUnknownKeys() {
        val json = """
            { "id": 1,
              "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
              "totalSpots": 16, "freeSpots": 12, "carsInside": 4, "addedLater": true,
              "rows": [{ "id": 1, "freeSpots": 0, "spots": [{ "id": 1, "occupied": true }] }] }
        """
        val lot = ParkingJson.decodeFromString<LotDetail>(json)
        assertEquals(listOf(Row(1, 0, listOf(Spot(1, true)))), lot.rows)
        assertEquals("Aarhus", lot.address.city)
        // older backends send no forecast
        assertEquals(emptyList<Forecast>(), lot.forecast)
    }

    @Test
    fun decodesForecast() {
        val json = """
            { "id": 1,
              "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
              "totalSpots": 16, "freeSpots": 12, "carsInside": 4, "rows": [],
              "forecast": [{ "at": "2026-11-11T15:00:00Z", "freeSpots": 5 }] }
        """
        assertEquals(listOf(Forecast("2026-11-11T15:00:00Z", 5)), ParkingJson.decodeFromString<LotDetail>(json).forecast)
    }

    @Test
    fun decodesCoordinatesWhenPresent() {
        val json = """{ "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus", "latitude": 56.1716, "longitude": 10.1897 }"""
        assertEquals(Address("Finlandsgade", "22", "Aarhus", 56.1716, 10.1897), ParkingJson.decodeFromString<Address>(json))
    }
}
