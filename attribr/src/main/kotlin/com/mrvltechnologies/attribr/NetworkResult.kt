package com.mrvltechnologies.attribr

/**
 * Result of an HTTP call performed by [NetworkClient].
 *
 * [Success] carries the response body (may be empty) alongside the status code —
 * `DeepLinkHandler` reads `body` to parse the JSON destination.
 * [Failure] carries the status code (0 when the request never reached the
 * server) and the underlying exception when known.
 */
internal sealed class NetworkResult {
    data class Success(val statusCode: Int, val body: String) : NetworkResult()
    data class Failure(val statusCode: Int, val error: Throwable?) : NetworkResult()
}
