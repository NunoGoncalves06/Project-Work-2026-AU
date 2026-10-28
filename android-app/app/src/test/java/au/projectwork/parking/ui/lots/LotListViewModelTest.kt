package au.projectwork.parking.ui.lots

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.ui.FakeLotStore
import au.projectwork.parking.ui.FakeParkingRepository
import au.projectwork.parking.ui.UiState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LotListViewModelTest {

    private val lot = LotSummary(1, Address("Finlandsgade", "22", "Aarhus"), 16, 12, 4)
    private val repo = FakeParkingRepository()
    private val store = FakeLotStore()

    @After
    fun tearDown() = Dispatchers.resetMain()

    // never advanceUntilIdle(): the polling loop never goes idle
    private fun TestScope.subscribedViewModel(): LotListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LotListViewModel(repo, store, clock = { 0L })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun firstLoadShowsContent() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        assertEquals(UiState.Loading, vm.state.value)
        runCurrent()
        assertEquals(UiState.Content(listOf(lot)), vm.state.value)
    }

    @Test
    fun firstLoadFailureShowsError() = runTest {
        repo.lotsResults += Result.failure(IOException("down"))
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Error(), vm.state.value)
    }

    @Test
    fun refreshFailureKeepsLastContentMarkedOffline() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        repo.lotsResults += Result.failure(IOException("blip"))
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = true), vm.state.value)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = false), vm.state.value)
    }

    @Test
    fun pollsAgainAfterFiveSeconds() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        subscribedViewModel()
        runCurrent()
        assertEquals(1, repo.lotsCalls)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, repo.lotsCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, repo.lotsCalls)
    }

    @Test
    fun refreshFetchesImmediately() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        runCurrent()
        vm.refresh(); runCurrent()
        assertEquals(2, repo.lotsCalls)
    }

    @Test
    fun keepsLastContentOfflineAfterResubscribe() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        repo.lotsResults += Result.failure(IOException("down"))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LotListViewModel(repo, store, clock = { 0L })
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        assertEquals(UiState.Content(listOf(lot)), vm.state.value)
        // stop the upstream: WhileSubscribed(5_000) cancels it after 5 s without collectors
        job.cancel()
        advanceTimeBy(6_000); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = true), vm.state.value)
    }

    @Test
    fun showsCachedLotsBeforeTheFirstFetchAndKeepsThemWhenOffline() = runTest {
        store.saved = listOf(lot) to 42L
        repo.lotsResults += Result.failure(IOException("down"))
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = true, fetchedAt = 42L), vm.state.value)
    }

    @Test
    fun savesEveryFetchToTheCache() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        subscribedViewModel()
        runCurrent()
        assertEquals(listOf(lot) to 0L, store.saved)
    }
}
