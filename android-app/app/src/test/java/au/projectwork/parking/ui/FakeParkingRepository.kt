package au.projectwork.parking.ui

import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.data.ParkingRepository

/** Each call returns the next queued result; the last one repeats. */
class FakeParkingRepository : ParkingRepository {
    val lotsResults = ArrayDeque<Result<List<LotSummary>>>()
    val lotResults = ArrayDeque<Result<LotDetail>>()
    var lotsCalls = 0
    var lotCalls = 0

    override suspend fun getLots(): List<LotSummary> {
        lotsCalls++
        return next(lotsResults).getOrThrow()
    }

    override suspend fun getLot(id: Int): LotDetail {
        lotCalls++
        return next(lotResults).getOrThrow()
    }

    private fun <T> next(queue: ArrayDeque<T>): T = if (queue.size > 1) queue.removeFirst() else queue.first()
}
