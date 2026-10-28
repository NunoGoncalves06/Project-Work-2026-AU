package au.projectwork.parking.data

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class NetworkParkingRepositoryTest {

    private fun apiThrowing(code: Int) = object : ParkingApi {
        override suspend fun getLots(): List<LotSummary> = throw HttpException(Response.error<Any>(code, "".toResponseBody()))
        override suspend fun getLot(id: Int): LotDetail = throw HttpException(Response.error<Any>(code, "".toResponseBody()))
    }

    @Test(expected = LotNotFoundException::class)
    fun http404OnDetailBecomesLotNotFound() = runTest {
        NetworkParkingRepository(apiThrowing(404)).getLot(99)
    }

    @Test
    fun otherHttpErrorsPassThrough() = runTest {
        val e = runCatching { NetworkParkingRepository(apiThrowing(500)).getLot(1) }.exceptionOrNull()
        assertEquals(500, (e as HttpException).code())
    }
}
