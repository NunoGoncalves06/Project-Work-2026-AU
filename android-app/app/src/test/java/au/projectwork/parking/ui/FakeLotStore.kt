package au.projectwork.parking.ui

import au.projectwork.parking.data.LotStore
import au.projectwork.parking.data.LotSummary
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLotStore(var saved: Pair<List<LotSummary>, Long>? = null) : LotStore {
    override val favourites = MutableStateFlow<Set<Int>>(emptySet())

    override suspend fun toggleFavourite(id: Int) {
        favourites.value = favourites.value.let { if (id in it) it - id else it + id }
    }

    override suspend fun saveLots(lots: List<LotSummary>, fetchedAt: Long) {
        saved = lots to fetchedAt
    }

    override suspend fun loadLots() = saved
}
