package au.projectwork.parking.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Tells the app when the server's parking data changed, so it can refetch then
 * rather than on a timer.
 *
 * [connected] lets the caller poll slowly while the stream is up and fall back to
 * a short interval when it is not, so a dropped stream degrades to the old
 * behaviour instead of leaving the screen stale.
 */
interface UpdateStream {
    /** Emits once per server-side change. Never completes; reconnects on its own. */
    val changes: Flow<Unit>
    val connected: Boolean
}

/** An [UpdateStream] that never connects: the app then just polls. */
object NoUpdateStream : UpdateStream {
    override val changes: Flow<Unit> = flow { }
    override val connected: Boolean = false
}

class SseUpdateStream(baseUrl: String, client: OkHttpClient = defaultClient()) : UpdateStream {

    private val request = Request.Builder().url(baseUrl.trimEnd('/') + STREAM_PATH).build()
    private val factory = EventSources.createFactory(client)

    @Volatile
    private var isConnected = false
    override val connected: Boolean get() = isConnected

    override val changes: Flow<Unit> = flow {
        var backoffMs = MIN_BACKOFF_MS
        while (true) {
            // one connection at a time; connect() returns when the stream drops
            connect().collect { change ->
                backoffMs = MIN_BACKOFF_MS
                if (change) emit(Unit)
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
        // a slow collector must not stall the reader thread; a missed tick costs nothing
        // because every event means the same thing: refetch
        .buffer(1, BufferOverflow.DROP_OLDEST)

    /** Emits true per change event, false on connect; completes when the stream drops. */
    private fun connect(): Flow<Boolean> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                isConnected = true
                trySend(false)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // "hello" and "keep-alive" only prove the stream is alive
                if (type == EVENT_CHANGED) trySend(true)
            }

            override fun onClosed(eventSource: EventSource) {
                isConnected = false
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // the server being down is the normal case here, not an error worth raising:
                // complete so the caller waits and reconnects
                isConnected = false
                close()
            }
        }
        val source = factory.newEventSource(request, listener)
        awaitClose {
            isConnected = false
            source.cancel()
        }
    }

    private companion object {
        const val STREAM_PATH = "/parking-lots/stream"
        const val EVENT_CHANGED = "changed"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        fun defaultClient() = OkHttpClient.Builder()
            // the stream is idle by design between changes: no read timeout, the server's
            // keep-alive and our own reconnect handle a dead connection
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
