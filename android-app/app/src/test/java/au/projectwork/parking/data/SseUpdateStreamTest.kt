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
}
