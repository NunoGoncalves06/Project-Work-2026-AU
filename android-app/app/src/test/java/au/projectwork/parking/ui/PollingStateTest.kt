package au.projectwork.parking.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollingStateTest {

    @Test
    fun slowFirstLoadDoesNotOverwriteTheNewerRefresh() = runTest {
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var calls = 0
        val seen = mutableListOf<UiState<String>>()
        val flow = pollingState(refresh, periodMs = { 60_000 }) {
            if (++calls == 1) { delay(1_000); "old" } else "new"
        }
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        refresh.tryEmit(Unit)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(2, calls)
        assertEquals("new", (seen.last() as UiState.Content).data)
    }
}
