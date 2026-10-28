package au.projectwork.parking.data

import retrofit2.http.GET
import retrofit2.http.Path

interface ParkingApi {

    @GET("parking-lots")
    suspend fun getLots(): List<LotSummary>

    @GET("parking-lots/{id}")
    suspend fun getLot(@Path("id") id: Int): LotDetail
}
