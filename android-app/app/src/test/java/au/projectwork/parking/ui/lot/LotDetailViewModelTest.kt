package au.projectwork.parking.ui.lot

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotNotFoundException
import au.projectwork.parking.data.NoUpdateStream
import au.projectwork.parking.data.Row
import au.projectwork.parking.data.Spot
import au.projectwork.parking.data.UpdateStream
import au.projectwork.parking.ui.FakeLotStore
import au.projectwork.parking.ui.FakeParkingRepository
import au.projectwork.parking.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LotDetailViewModelTest {

    private val lot = LotDetail(
        1, Address("Finlandsgade", "22", "Aarhus"), 3, 1, 2,
        listOf(Row(1, 1, listOf(Spot(1, true), Spot(2, true), Spot(3, false)))),
    )
    private val repo = FakeParkingRepository()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.subscribedViewModel(updates: UpdateStream = NoUpdateStream): LotDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LotDetailViewModel(repo, FakeLotStore(), lotId = 1, updates = updates, clock = { 0L })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun loadsLot() = runTest {
        repo.lotResults += Result.success(lot)
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Content(lot), vm.state.value)
        assertEquals(1, repo.lotCalls)
    }

    @Test
    fun unknownLotShowsNotFound() = runTest {
        repo.lotResults += Result.failure(LotNotFoundException(1))
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Error(notFound = true), vm.state.value)
    }

    private class FakeStream(override val connected: Boolean) : UpdateStream {
        override val changes: Flow<Unit> = emptyFlow()
    }

    @Test
    fun pollsEvery60sWhileTheStreamIsConnected() = runTest {
        repo.lotResults += Result.success(lot)
        subscribedViewModel(FakeStream(connected = true))
        runCurrent()
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, repo.lotCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, repo.lotCalls)
    }

    @Test
    fun pollsEvery5sWhileTheStreamIsDown() = runTest {
        repo.lotResults += Result.success(lot)
        subscribedViewModel(FakeStream(connected = false))
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, repo.lotCalls)
    }
}
