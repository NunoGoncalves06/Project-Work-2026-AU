package au.projectwork.parking.ui.lot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotStore
import au.projectwork.parking.data.NoUpdateStream
import au.projectwork.parking.data.ParkingRepository
import au.projectwork.parking.data.UpdateStream
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.pollPeriodMs
import au.projectwork.parking.ui.pollingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LotDetailViewModel(
    repository: ParkingRepository,
    private val store: LotStore,
    private val lotId: Int,
    updates: UpdateStream = NoUpdateStream,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val state: StateFlow<UiState<LotDetail>> =
        pollingState(
            // the server's push and the user's pull mean the same thing: fetch now
            merge(refreshRequests, updates.changes),
            periodMs = { pollPeriodMs(updates.connected) },
            clock = clock,
        ) { repository.getLot(lotId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    val favourite: StateFlow<Boolean> =
        store.favourites.map { lotId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    fun toggleFavourite() {
        viewModelScope.launch { store.toggleFavourite(lotId) }
    }
}
