package com.mrvltechnologies.attribr

import android.net.Uri
import org.json.JSONObject

/**
 * Handles incoming deep links (App Links and custom scheme URLs) for Attribr attribution.
 *
 * ## Usage — Activity
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     intent?.data?.let { Attribr.handleDeepLink(it) }
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     intent.data?.let { Attribr.handleDeepLink(it) }
 * }
 * ```
 */
internal class DeepLinkHandler(
    private val networkClient: NetworkClient,
    private val deviceIdentifier: DeviceIdentifier,
    private val logger: AttribrLogger,
    private val appId: String,
    private val eventQueue: EventQueue,
) {
    /**
     * Process an incoming URI. Extracts link_code from attribr.dev/l/{code} URIs
     * or attribr://{code} custom scheme URIs.
     *
     * @return [DeepLinkResult] indicating whether it was an Attribr link.
     */
    fun handle(uri: Uri): DeepLinkResult {
        logger.debug("Processing deep link: $uri")

        val linkCode = extractLinkCode(uri)
            ?: run {
                logger.debug("URI is not an Attribr deep link")
                return DeepLinkResult.NotAttribrLink
            }

        logger.info("Deep link code extracted: $linkCode")

        val deviceHash = deviceIdentifier.getHash()
        val body = JSONObject().apply {
            put("app_id", appId)
            put("device_hash", deviceHash)
            put("link_code", linkCode)
            // Idempotency key — generated once at build time, so a queued
            // retry carries the same id as the failed first attempt and the
            // backend can deduplicate. See Attribr.buildBasePayload.
            put("sdk_event_id", java.util.UUID.randomUUID().toString())
        }
        val bodyStr = body.toString()

        return when (val result = networkClient.post("attribr-deeplink-resolve", bodyStr)) {
            is NetworkResult.Success -> {
                try {
                    val json = JSONObject(result.body)
                    val destination = json.optString("destination", "")
                    if (destination.isNotEmpty()) {
                        logger.info("Deep link resolved -> $destination")
                        DeepLinkResult.Resolved(Uri.parse(destination), linkCode)
                    } else {
                        DeepLinkResult.Attributed(linkCode)
                    }
                } catch (e: Exception) {
                    DeepLinkResult.Attributed(linkCode)
                }
            }
            is NetworkResult.Failure -> {
                // Durable delivery: the click payload is queued and retried on
                // the next flush, so returning Attributed here is honest —
                // the attribution WILL reach the server.
                logger.error("Deep link resolution failed — click queued for retry")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = java.util.UUID.randomUUID().toString(),
                        kind       = EventKind.DEEP_LINK,
                        endpoint   = "attribr-deeplink-resolve",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
                DeepLinkResult.Attributed(linkCode)
            }
        }
    }

    private fun extractLinkCode(uri: Uri): String? {
        // Pattern 1: https://attribr.dev/l/{code}
        val host = uri.host
        if (host == "attribr.dev" || host == "www.attribr.dev") {
            val segments = uri.pathSegments
            if (segments.size >= 2 && segments[0] == "l") {
                return segments[1]
            }
        }

        // Pattern 2: attribr://{code}
        if (uri.scheme == "attribr") {
            return uri.host
        }

        // Pattern 3: ?attribr_link={code}
        return uri.getQueryParameter("attribr_link")
    }
}

/** Result of processing a deep link. */
public sealed class DeepLinkResult {
    /** URL was not an Attribr deep link. */
    public object NotAttribrLink : DeepLinkResult()
    /** Link was attributed but no destination was returned. */
    public data class Attributed(val linkCode: String) : DeepLinkResult()
    /** Link resolved to a destination URI. */
    public data class Resolved(val destination: Uri, val linkCode: String) : DeepLinkResult()
    /** An error occurred. */
    public data class Error(val message: String) : DeepLinkResult()
}
