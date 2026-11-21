package com.mrvltechnologies.attribr

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking HTTP client used by the SDK. Callers dispatch to
 * [Attribr]'s single-thread executor so the main thread is never blocked.
 *
 * Authentication uses the `X-Attribr-Key` header — the same convention used
 * throughout the Attribr backend Edge Functions (see also `AttribrMonetisation`).
 */
internal class NetworkClient(
    private val apiKey: String,
    private val config: AttribrConfiguration,
    private val logger: AttribrLogger,
    private val generation: () -> Long = { 0 },
    private val isTrackingAllowed: () -> Boolean,
) {

    fun post(path: String, body: String): NetworkResult = execute("POST", path, body)

    fun delete(path: String, body: String): NetworkResult = execute("DELETE", path, body)

    private fun execute(method: String, path: String, body: String): NetworkResult {
        // Single transport gate: a task queued while consent was granted may
        // run after withdrawal. Erasure is explicitly exempt.
        val epoch = generation()
        val isErasure = method == "DELETE" && path == "attribr-delete-user-data"
        if (!isErasure && (!isTrackingAllowed() || generation() != epoch)) return NetworkResult.Failure(statusCode = 0, error = ConsentUnavailableException())
        val url = URL("${config.baseURL.trimEnd('/')}/$path")
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Attribr-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = config.requestTimeout.toInt()
                readTimeout = config.requestTimeout.toInt()
                doInput = true
                doOutput = true
            }
            // Recheck after opening the connection but before writing any data.
            if (!isErasure && (!isTrackingAllowed() || generation() != epoch)) {
                return NetworkResult.Failure(statusCode = 0, error = ConsentUnavailableException())
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val responseBody = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: IOException) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            if (code in 200..299) {
                NetworkResult.Success(statusCode = code, body = responseBody)
            } else {
                logger.debug("$method $path -> HTTP $code: $responseBody")
                NetworkResult.Failure(statusCode = code, error = if (!isErasure && generation() != epoch) ConsentUnavailableException() else null)
            }
        } catch (t: Throwable) {
            logger.debug("$method $path -> exception: ${t.message}")
            NetworkResult.Failure(statusCode = 0, error = if (!isErasure && generation() != epoch) ConsentUnavailableException() else t)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}

/** Signals a suppressed tracking request; callers must never retry its payload after re-consent. */
internal class ConsentUnavailableException : IllegalStateException("Tracking consent no longer valid")
