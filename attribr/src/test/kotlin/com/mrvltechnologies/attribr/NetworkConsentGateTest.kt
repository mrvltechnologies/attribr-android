package com.mrvltechnologies.attribr

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** A denied/revoked SDK dispatch must return before opening a connection. */
class NetworkConsentGateTest {
    @Test fun deniedConsentBlocksTrackAndNonErasureDelete() {
        var allowed = false
        val network = NetworkClient("test-public-key", AttribrConfiguration(), RecordingLogger()) { allowed }
        val p = network.post("attribr-track", "{}")
        val d = network.delete("not-erasure", "{}")
        assertTrue(p is NetworkResult.Failure)
        assertTrue(d is NetworkResult.Failure)
        assertEquals(0, (p as NetworkResult.Failure).statusCode)
        allowed = false
    }
}

private class LocalHttpResponse(private val status: Int, private val received: () -> Unit = {}) : java.io.Closeable {
    private val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    val url: String = "http://127.0.0.1:${listener.localPort}"
    init {
        worker.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { input.read() }
                received()
                socket.getOutputStream().write("HTTP/1.1 $status Result\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            }
        }
    }
    override fun close() { listener.close(); worker.shutdownNow() }
}

class NetworkConsentErasureTest {
    @Test fun explicitErasureRemainsAvailableAfterWithdrawal() {
        var calls = 0
        LocalHttpResponse(200) { calls++ }.use { server ->
            val client = NetworkClient("test-public-key", AttribrConfiguration(baseURL = server.url), RecordingLogger()) { false }
            assertTrue(client.delete("attribr-delete-user-data", "{}") is NetworkResult.Success)
            assertEquals(1, calls)
            assertTrue(client.post("attribr-track", "{}") is NetworkResult.Failure)
            assertEquals(1, calls)
        }
    }

    @Test fun withdrawnAndRegrantedInFlightRequestCannotBecomeRetryable() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val generation = java.util.concurrent.atomic.AtomicLong(0)
        LocalHttpResponse(500) {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }.use { server ->
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val client = NetworkClient("test-public-key", AttribrConfiguration(baseURL = server.url), RecordingLogger(), { generation.get() }) { true }
                val future = executor.submit<NetworkResult> { client.post("attribr-track", "{}") }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                generation.incrementAndGet() // withdrawal followed by a new grant
                release.countDown()
                val result = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(result is NetworkResult.Failure && result.error is ConsentUnavailableException)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
}
