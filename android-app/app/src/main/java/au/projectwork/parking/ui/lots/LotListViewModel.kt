package au.projectwork.parking.ui.lots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotStore
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.data.ParkingRepository
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.pollingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LotListViewModel(
    private val repository: ParkingRepository,
    private val store: LotStore,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val favourites: StateFlow<Set<Int>> = store.favourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _here = MutableStateFlow<Here?>(null)
    val here: StateFlow<Here?> = _here.asStateFlow()

    private val _filter = MutableStateFlow(LotFilter.Nearby)
    val filter: StateFlow<LotFilter> = _filter.asStateFlow()

    private var lastSaved: List<LotSummary>? = null

    // the big ticket's spots; fetched with each poll because the list endpoint has no per-spot data
    private val _heroDetail = MutableStateFlow<LotDetail?>(null)
    val heroDetail: StateFlow<LotDetail?> = _heroDetail.asStateFlow()

    // WhileSubscribed: polling stops 5 s after the screen stops collecting (app in background)
    val state: StateFlow<UiState<List<LotSummary>>> =
        pollingState(
            refreshRequests,
            clock = clock,
            cached = { store.loadLots() },
            // only when the numbers changed: no disk write every 5 s. The cached time can then be older than the
            // last fetch, which only makes a cold-start stamp look older, never fresher
            onFetched = { lots, at -> if (lots != lastSaved) store.saveLots(lots, at).also { lastSaved = lots } },
        ) {
            repository.getLots().also { lots -> _heroDetail.value = fetchHeroDetail(lots) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private suspend fun fetchHeroDetail(lots: List<LotSummary>): LotDetail? {
        val hero = pickHero(lots, favourites.value, _here.value) ?: return null
        return try {
            repository.getLot(hero.row.lot.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the bay strip is a bonus; keep the last one for the same lot rather than failing the list
            _heroDetail.value?.takeIf { it.id == hero.row.lot.id }
        }
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    fun setFilter(filter: LotFilter) {
        _filter.value = filter
    }

    fun setHere(here: Here) {
        val first = _here.value == null
        _here.value = here
        // the nearest lot may have changed: fetch its spots now instead of on the next poll
        if (first) refresh()
    }

    fun toggleFavourite(id: Int) {
        viewModelScope.launch { store.toggleFavourite(id) }
    }
}
