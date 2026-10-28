package au.projectwork.parking.data

import retrofit2.HttpException

class LotNotFoundException(id: Int) : Exception("Parking lot $id not found")

/** Throws IOException when the server is unreachable, LotNotFoundException for an unknown lot. */
interface ParkingRepository {
    suspend fun getLots(): List<LotSummary>
    suspend fun getLot(id: Int): LotDetail
}

class NetworkParkingRepository(private val api: ParkingApi) : ParkingRepository {

    override suspend fun getLots(): List<LotSummary> = api.getLots()

    override suspend fun getLot(id: Int): LotDetail =
        try {
            api.getLot(id)
        } catch (e: HttpException) {
            if (e.code() == 404) throw LotNotFoundException(id) else throw e
        }
}
