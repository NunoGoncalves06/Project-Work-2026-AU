package au.projectwork.parking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.projectwork.parking.data.LotNotFoundException
import au.projectwork.parking.ui.theme.TicketColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * How often to refetch on our own. While the server's update stream is up it only has to catch
 * what the fingerprint check cannot see; with the stream down it is the only thing keeping the
 * screen fresh, and matches the 5 s the error card promises.
 */
fun pollPeriodMs(streamConnected: Boolean): Long = if (streamConnected) 60_000 else 5_000

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    /** [fetchedAt] is when [data] came from the server (epoch ms); old cached data keeps its old time. */
    data class Content<T>(val data: T, val offline: Boolean = false, val fetchedAt: Long = 0) : UiState<T>
    data class Error(val notFound: Boolean = false) : UiState<Nothing>
}

/**
 * Fetches now, then every [periodMs], and again whenever [refreshRequests] emits.
 * [cached] is shown before the first fetch; [onFetched] gets every successful result (to cache it).
 *
 * [periodMs] is read before each wait, so a caller can poll slowly while the server pushes
 * updates and drop back to a short interval when that stream is down.
 */
fun <T> pollingState(
    refreshRequests: Flow<Unit>,
    periodMs: () -> Long = { 5_000 },
    clock: () -> Long = System::currentTimeMillis,
    cached: suspend () -> Pair<T, Long>? = { null },
    onFetched: suspend (T, Long) -> Unit = { _, _ -> },
    fetch: suspend () -> T,
): Flow<UiState<T>> {
    // outside channelFlow so it survives WhileSubscribed restarts (the ViewModel keeps one Flow instance)
    var last: Pair<T, Long>? = null
    return channelFlow {
        if (last == null) last = cached()?.also { (data, at) -> send(UiState.Content(data, fetchedAt = at)) }
        suspend fun load() {
            val next = try {
                val data = fetch()
                val at = clock()
                last = data to at
                // a failed cache write must not turn a good fetch into "offline"
                try { onFetched(data, at) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                UiState.Content(data, fetchedAt = at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LotNotFoundException) {
                UiState.Error(notFound = true)
            } catch (e: Exception) {
                // keep showing the last data if we had any; only a failed first load is an error
                last?.let { (data, at) -> UiState.Content(data, offline = true, fetchedAt = at) } ?: UiState.Error()
            }
            send(next)
        }
        launch { refreshRequests.collect { load() } }
        while (true) {
            load()
            delay(periodMs())
        }
    }
}

@Composable
fun <T> StateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable (data: T, offline: Boolean, fetchedAt: Long) -> Unit,
) {
    when (state) {
        UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TicketColors.current.ink)
        }
        is UiState.Error -> Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Ticket(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.notFound) {
                        Text("Parking lot not found", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "It may have been removed. Go back to see the lots that exist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TicketColors.current.faded,
                        )
                        if (onBack != null) Button(onClick = onBack) { Text("Back to lots") }
                    } else {
                        Text("Can't reach the server", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Check your connection. AU Parking keeps trying every 5 seconds.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TicketColors.current.faded,
                        )
                        Button(onClick = onRetry) { Text("Try now") }
                    }
                }
            }
        }
        is UiState.Content -> Box(modifier.fillMaxSize()) { content(state.data, state.offline, state.fetchedAt) }
    }
}

/**
 * Pull-to-refresh that shows the spinner until the next result lands ([resultKey] changes), or for at most
 * 3 s when the result is identical (StateFlow drops equal values, so the key may never change).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(resultKey: Any?, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(resultKey) { refreshing = false }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(3_000)
            refreshing = false
        }
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; onRefresh() }) { content() }
}

