package au.projectwork.parking

import android.content.Context
import au.projectwork.parking.data.DataStoreLotStore
import au.projectwork.parking.data.LotStore
import au.projectwork.parking.data.NetworkParkingRepository
import au.projectwork.parking.data.ParkingApi
import au.projectwork.parking.data.ParkingJson
import au.projectwork.parking.data.ParkingRepository
import au.projectwork.parking.data.SseUpdateStream
import au.projectwork.parking.data.UpdateStream
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer(context: Context) {

    val store: LotStore = DataStoreLotStore(context.applicationContext)

    /** Shared by both screens: one stream per app, not one per screen. */
    val updates: UpdateStream = SseUpdateStream(BuildConfig.API_BASE_URL)

    val repository: ParkingRepository = NetworkParkingRepository(
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(ParkingJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ParkingApi::class.java)
    )
}
