package au.projectwork.parking.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SseUpdateStreamTest {

    @Test
    fun twoScreensShareOneConnection() = runBlocking {
        val connections = AtomicInteger()
        // answers every request itself, no network: an empty event stream that ends at once
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            connections.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stream = SseUpdateStream("http://test/", client, scope)

        val list = scope.launch { stream.changes.collect {} }
        val detail = scope.launch { stream.changes.collect {} }
        // well inside the 1 s reconnect backoff, so only first connections are counted
        delay(300)

        assertEquals(1, connections.get())
        list.cancel()
        detail.cancel()
        scope.cancel()
    }

    /** How many times [SseUpdateStream.changes] emits for one connection whose body is [body]. */
    private fun emissionsFor(body: String): Int = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stream = SseUpdateStream("http://test/", client, scope)
        val count = AtomicInteger()
        scope.launch { stream.changes.collect { count.incrementAndGet() } }
        delay(300) // inside the 1 s reconnect backoff: one connection only
        scope.cancel()
        count.get()
    }

    @Test
    fun emitsOnOpenSoScreensRefetchAfterAReconnect() {
        assertEquals(1, emissionsFor("event: hello\ndata: hi\n\n"))
    }

    @Test
    fun emitsOnAChangedEventToo() {
        // open and changed land together and the stream keeps only the newest (DROP_OLDEST), so 1 or 2 is right
        assertEquals(2, emissionsFor("event: changed\ndata: x\n\n"))
    }
}
