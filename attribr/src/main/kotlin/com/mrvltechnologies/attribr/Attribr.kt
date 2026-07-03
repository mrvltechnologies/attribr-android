package com.mrvltechnologies.attribr

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * **Attribr by MRVL** — Install attribution + 30-day retention tracking.
 *
 * ## Quick Start
 * ```kotlin
 * // In Application.onCreate():
 * Attribr.initialize(this, apiKey = "attr_live_…")
 * Attribr.setConsent(ConsentState.GRANTED) // after your GDPR banner
 *
 * // In every Activity/Fragment onResume(), or via ProcessLifecycleOwner:
 * Attribr.trackLaunch()
 * ```
 *
 * ## Thread Safety
 * All public methods are safe to call from any thread.
 * Network I/O is dispatched to a dedicated background thread.
 * Initialisation is double-checked locked for safe concurrent first-call.
 */
public object Attribr {

    @Volatile private var isInitialized = false
    private val initLock = Any()

    // Internal components — lateinit, set atomically inside initLock
    private lateinit var apiKey: String
    private lateinit var config: AttribrConfiguration
    private lateinit var logger: AttribrLogger
    private lateinit var consentManager: ConsentManager
    private lateinit var deviceIdentifier: DeviceIdentifier
    private lateinit var eventQueue: EventQueue
    private lateinit var networkClient: NetworkClient
    private lateinit var appContext: Context
    private lateinit var deepLinkHandler: DeepLinkHandler

    // Install referrer check is best-effort, fires once on first install
    private val referrerCheckedKey = "attribr_referrer_checked"

    /**
     * Single-thread executor for all network I/O.
     * Daemon thread so it doesn't prevent JVM exit.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AttribrSDK-io").apply { isDaemon = true }
    }

    /**
     * Immutable snapshot of the values passed to [initialize]. Exposed so that
     * companion modules (e.g. [AttribrMonetisation]) can read the API key
     * without re-plumbing it. Null until [initialize] has been called.
     */
    public data class ConfigurationSnapshot(
        val apiKey: String,
        val config: AttribrConfiguration,
    )

    /** Snapshot of the current configuration, or `null` before initialisation. */
    @JvmStatic
    public val configuration: ConfigurationSnapshot?
        get() = if (isInitialized) ConfigurationSnapshot(apiKey, config) else null

    // -------------------------------------------------------------------------
    // Public API — mirrors Swift SDK exactly
    // -------------------------------------------------------------------------

    /**
     * Initialise the SDK. Call once from [android.app.Application.onCreate].
     * Subsequent calls are silently ignored (singleton pattern).
     *
     * @param context  Application context. Activity context is also accepted and
     *                 unwrapped to application context internally.
     * @param apiKey   Your Attribr live key, e.g. `attr_live_myapp00x…` (50 chars).
     * @param config   Optional [AttribrConfiguration] to customise behaviour.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        apiKey: String,
        config: AttribrConfiguration = AttribrConfiguration(),
    ) {
        if (isInitialized) return
        synchronized(initLock) {
            if (isInitialized) return

            this.apiKey          = apiKey
            this.config          = config
            this.appContext      = context.applicationContext
            this.logger          = AttribrLogger(config.debugLogging)
            this.consentManager  = ConsentManager(this.appContext)
            this.deviceIdentifier = DeviceIdentifier(this.appContext)
            this.eventQueue      = EventQueue(this.appContext, config.maxQueueSize, logger)
            this.networkClient   = NetworkClient(apiKey, config, logger)
            this.deepLinkHandler = DeepLinkHandler(networkClient, deviceIdentifier, logger, appContext.packageName)
            this.isInitialized   = true

            logger.info("Attribr initialized — key prefix: ${apiKey.take(18)}…")
            executor.execute { checkInstallReferrer() }
        }
    }

    /**
     * Inform the SDK of the user's GDPR consent decision.
     *
     * The SDK sends **no data** until this is set to [ConsentState.GRANTED].
     * When [ConsentState.GRANTED] is set, any events queued due to network
     * failures are flushed immediately.
     *
     * Call this after your consent banner resolves, or on app start if the
     * user has previously granted consent.
     */
    @JvmStatic
    public fun setConsent(state: ConsentState) {
        requireInitialized()
        consentManager.state = state
        logger.info("Consent set to: ${state.rawValue}")
        if (state == ConsentState.GRANTED) {
            executor.execute { flushQueue() }
        }
    }

    /**
     * Record an app launch (foreground event).
     *
     * Call this every time the app comes to the foreground. The recommended
     * pattern is a [androidx.lifecycle.ProcessLifecycleOwner] observer or
     * [android.app.Activity.onResume] in your base Activity.
     *
     * Silently ignored until consent is [ConsentState.GRANTED].
     */
    @JvmStatic
    public fun trackLaunch() {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("trackLaunch skipped — consent not granted")
            return
        }
        executor.execute {
            // Flush any events that failed on a previous launch before sending the
            // current one. Without this, queued events are only retried when
            // setConsent(GRANTED) is called again — which never happens after init.
            flushQueue()
            sendTrack()
        }
    }

    /**
     * Record a referral attribution.
     *
     * Call this when the app is opened from a referral link, promo code, or
     * UTM-tagged marketing URL.
     *
     * @param code        Attribution code (e.g. "JOHN2026" or UTM campaign value).
     * @param source      Channel type — [AttributionSource.RIPPL], [AttributionSource.UTM],
     *                    or [AttributionSource.CUSTOM].
     * @param utmSource   Optional UTM source string (used when source is [AttributionSource.UTM]).
     * @param utmCampaign Optional UTM campaign string.
     */
    @JvmStatic
    @JvmOverloads
    public fun attributeInstall(
        code: String,
        source: AttributionSource = AttributionSource.CUSTOM,
        utmSource: String? = null,
        utmCampaign: String? = null,
    ) {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("attributeInstall skipped — consent not granted")
            return
        }
        executor.execute { sendAttribution(code, source, utmSource, utmCampaign) }
    }

    /**
     * Track a custom event (e.g. "purchase", "level_complete", "subscription_started").
     *
     * @param name     Event name. Max 100 characters. Use snake_case by convention.
     * @param value    Optional numeric value (e.g. purchase amount in pence).
     * @param currency ISO 4217 currency code (e.g. "GBP").
     * @param metadata Optional free-form key–value pairs. Max 10 entries.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackEvent(
        name: String,
        value: Double? = null,
        currency: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        requireInitialized()
        if (!consentManager.isGranted) return
        executor.execute { sendEvent(name, value, currency, metadata) }
    }

    /**
     * Delete all data for this device from Attribr's servers.
     *
     * Implements the GDPR right to erasure. Wire this to a "Delete my data"
     * button in your app's Privacy or Account settings screen.
     *
     * @param callback Invoked with `true` on success, `false` on failure.
     *                 Called on the executor thread — post to main if needed.
     */
    @JvmStatic
    @JvmOverloads
    public fun deleteAllData(callback: ((Boolean) -> Unit)? = null) {
        requireInitialized()
        executor.execute {
            val success = sendDelete()
            callback?.invoke(success)
        }
    }

    /**
     * Track an ad impression revenue event.
     *
     * Call from your ad network's impression-level revenue callback
     * (AdMob, MAX, IronSource, Unity Ads, AppLovin, etc.)
     *
     * @param adNetwork  Ad network name (e.g. "admob", "max", "ironsource", "applovin", "unity").
     * @param adUnitId   Optional ad unit identifier.
     * @param adFormat   Optional format: "banner", "interstitial", "rewarded", "native".
     * @param revenue    Revenue amount (typically in USD micro-cents from ad networks).
     * @param currency   ISO 4217 currency code. Defaults to "USD".
     */
    @JvmStatic
    @JvmOverloads
    public fun trackAdRevenue(
        adNetwork: String,
        adUnitId: String? = null,
        adFormat: String? = null,
        revenue: Double,
        currency: String = "USD",
    ) {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("trackAdRevenue skipped — consent not granted")
            return
        }
        executor.execute { sendAdRevenue(adNetwork, adUnitId, adFormat, revenue, currency) }
    }

    /**
     * Register the device's FCM push token for uninstall detection.
     *
     * Call from [com.google.firebase.messaging.FirebaseMessagingService.onNewToken]
     * or after retrieving the token with [com.google.firebase.messaging.FirebaseMessaging.getToken].
     *
     * @param fcmToken The FCM registration token string.
     */
    @JvmStatic
    public fun registerPushToken(fcmToken: String) {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("registerPushToken skipped — consent not granted")
            return
        }
        executor.execute { sendPushToken(fcmToken) }
    }

    /**
     * Track a subscription or in-app purchase event.
     *
     * Call after successful Google Play Billing transactions to attribute revenue
     * to acquisition sources.
     *
     * @param productId              Google Play product ID (e.g. "pro_monthly").
     * @param eventType              "purchase", "renewal", "cancellation", "refund", "trial_start".
     * @param revenue                Revenue amount in [currency]. Null for non-revenue events.
     * @param currency               ISO 4217 currency code. Defaults to "USD".
     * @param originalTransactionId  Google Play purchase token for deduplication.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackSubscriptionEvent(
        productId: String,
        eventType: String,
        revenue: Double? = null,
        currency: String = "USD",
        originalTransactionId: String? = null,
    ) {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("trackSubscriptionEvent skipped — consent not granted")
            return
        }
        executor.execute {
            sendSubscriptionEvent(productId, eventType, revenue, currency, originalTransactionId)
        }
    }

    /**
     * Track an in-app purchase or subscription revenue event.
     *
     * Call after every successful Google Play Billing or Stripe transaction to power
     * ROAS, LTV, and revenue-by-source reports in the Attribr dashboard.
     * Idempotent — duplicate [transactionId] values are silently ignored server-side.
     *
     * ```kotlin
     * // After a successful purchase:
     * Attribr.trackRevenue(
     *     amount        = 9.99,
     *     currency      = "GBP",
     *     productId     = "pro_monthly",
     *     transactionId = purchase.purchaseToken,
     *     store         = "google",
     *     eventType     = "initial_purchase",
     * )
     * ```
     *
     * @param amount        Revenue amount in [currency] (e.g. `9.99` for a £9.99 charge).
     * @param currency      ISO 4217 currency code (default `"USD"`).
     * @param productId     Product SKU (e.g. `"pro_monthly"`). Optional.
     * @param transactionId Unique transaction identifier. Use `purchase.purchaseToken`
     *                      for Google Play Billing or the Stripe PaymentIntent ID.
     * @param store         `"google"`, `"apple"`, `"stripe"`, or `"other"` (default `"google"`).
     * @param eventType     `"initial_purchase"` (default), `"renewal"`, `"refund"`,
     *                      `"cancellation"`, `"upgrade"`, or `"downgrade"`.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackRevenue(
        amount: Double,
        currency: String = "USD",
        productId: String? = null,
        transactionId: String,
        store: String = "google",
        eventType: String = "initial_purchase",
    ) {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("trackRevenue skipped — consent not granted")
            return
        }
        executor.execute {
            sendRevenue(amount, currency, productId, transactionId, store, eventType)
        }
    }

    /**
     * Handle an incoming deep link URI.
     *
     * Call from your Activity's `onCreate` or `onNewIntent`:
     * ```kotlin
     * intent?.data?.let { Attribr.handleDeepLink(it) }
     * ```
     *
     * @param uri The URI from the incoming intent.
     * @return [DeepLinkResult] indicating whether this was an Attribr link.
     */
    @JvmStatic
    public fun handleDeepLink(uri: android.net.Uri): DeepLinkResult {
        requireInitialized()
        if (!consentManager.isGranted) {
            logger.debug("handleDeepLink skipped — consent not granted")
            return DeepLinkResult.NotAttribrLink
        }
        return deepLinkHandler.handle(uri)
    }

    // -------------------------------------------------------------------------
    // Play Install Referrer (Android deterministic deferred attribution)
    // -------------------------------------------------------------------------

    /**
     * Query the Play Install Referrer API for deterministic deferred attribution.
     *
     * Sprint 3 — every response is now surfaced to the backend as a structured
     * `play_referrer` object, including failure statuses. This lets the
     * Data Confidence Score observe Play Store availability as a health signal
     * instead of silently swallowing service outages.
     *
     * Only fires once per install (gated by SharedPreferences flag).
     */
    private fun checkInstallReferrer() {
        val prefs = appContext.getSharedPreferences("attribr", Context.MODE_PRIVATE)
        if (prefs.getBoolean(referrerCheckedKey, false)) return

        val client = com.android.installreferrer.api.InstallReferrerClient
            .newBuilder(appContext).build()

        client.startConnection(object : com.android.installreferrer.api.InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                // Always mark checked BEFORE dispatch so any thrown exception in
                // the branches below still consumes the one-shot budget.
                val payload = try {
                    when (responseCode) {
                        com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val details = client.installReferrer
                            JSONObject().apply {
                                put("status",                                    "ok")
                                put("response_code",                             "OK")
                                put("install_referrer",                          details.installReferrer ?: "")
                                put("referrer_click_timestamp_seconds",          details.referrerClickTimestampSeconds)
                                put("install_begin_timestamp_seconds",           details.installBeginTimestampSeconds)
                                put("referrer_click_timestamp_server_seconds",   details.referrerClickTimestampServerSeconds)
                                put("install_begin_timestamp_server_seconds",    details.installBeginTimestampServerSeconds)
                                put("install_version",                           details.installVersion ?: "")
                                put("google_play_instant",                       details.googlePlayInstantParam)
                            }
                        }
                        com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            JSONObject().apply {
                                put("status", "feature_not_supported")
                                put("response_code", "FEATURE_NOT_SUPPORTED")
                            }
                        }
                        com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            JSONObject().apply {
                                put("status", "service_unavailable")
                                put("response_code", "SERVICE_UNAVAILABLE")
                            }
                        }
                        else -> {
                            JSONObject().apply {
                                put("status", "unknown_response")
                                put("response_code", responseCode.toString())
                            }
                        }
                    }
                } catch (e: Exception) {
                    JSONObject().apply {
                        put("status", "error")
                        put("response_code", "EXCEPTION")
                        put("error_message", e.message ?: "unknown")
                    }
                }

                prefs.edit().putBoolean(referrerCheckedKey, true).apply()
                runCatching { client.endConnection() }
                logger.debug("Install referrer status: ${payload.optString("status")}")
                executor.execute { sendInstallReferrer(payload) }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Best-effort — no retry
                logger.debug("Install referrer service disconnected")
            }
        })
    }

    /**
     * Send the structured Play Install Referrer payload to the Attribr backend.
     *
     * The payload is always sent — success AND failure — so the backend can
     * write a raw event describing what the Play Store told us. On success,
     * the backend parses the referrer for a deterministic attribution decision.
     * On failure, the backend records the status for the Data Confidence Score.
     */
    private fun sendInstallReferrer(playReferrer: JSONObject) {
        val body = buildBasePayload().apply {
            put("play_referrer", playReferrer)
            // Backwards-compatible flat fields — older backend builds still read these.
            // The new backend prefers the structured `play_referrer` object.
            if (playReferrer.optString("status") == "ok") {
                put("referrer_url",       playReferrer.optString("install_referrer"))
                put("click_timestamp",    playReferrer.optLong("referrer_click_timestamp_seconds"))
                put("install_timestamp",  playReferrer.optLong("install_begin_timestamp_seconds"))
                put("attribution_method", "play_install_referrer")
            }
        }

        when (val result = networkClient.post("attribr-track", body.toString())) {
            is NetworkResult.Success -> logger.debug("Play referrer payload sent successfully")
            is NetworkResult.Failure -> logger.error("Play referrer send failed: ${result.statusCode}")
        }
    }

    // -------------------------------------------------------------------------
    // Private send methods (always called on executor thread)
    // -------------------------------------------------------------------------

    private fun sendTrack() {
        val body = buildBasePayload()

        // Re-engagement detection: compare against last seen timestamp
        val prefs = appContext.getSharedPreferences("attribr", Context.MODE_PRIVATE)
        val lastSeenAt = prefs.getLong("attribr_last_seen_at", 0L)
        val now = System.currentTimeMillis()
        if (lastSeenAt > 0L) {
            val daysSince = (now - lastSeenAt) / 86_400_000.0
            when {
                daysSince > 30 -> {
                    body.put("re_engagement", true)
                    body.put("days_since_last_seen", daysSince.toInt())
                    body.put("install_type", "re_engagement")
                    logger.info("trackLaunch: re-engagement detected (${daysSince.toInt()} days since last seen)")
                }
                daysSince > 7 -> body.put("install_type", "returning")
            }
        }
        // NOTE: lastSeenAt is updated ONLY on confirmed server success below.
        // Advancing the timestamp before a successful response would corrupt
        // re-engagement detection for users with intermittent connectivity.

        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-track", bodyStr)) {
            is NetworkResult.Success -> {
                // Stamp the confirmed timestamp now that the server acknowledged the event
                prefs.edit().putLong("attribr_last_seen_at", now).apply()
                logger.debug("trackLaunch: success")
            }
            is NetworkResult.Failure -> {
                logger.error("trackLaunch failed: ${result.statusCode} ${result.error?.message}")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.TRACK,
                        endpoint   = "attribr-track",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    private fun sendAttribution(
        code: String,
        source: AttributionSource,
        utmSource: String?,
        utmCampaign: String?,
    ) {
        val body = buildBasePayload().apply {
            put("attribution_code", code)
            put("source", source.value)
            utmSource?.let    { put("utm_source", it) }
            utmCampaign?.let  { put("utm_campaign", it) }
        }
        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-attribute", bodyStr)) {
            is NetworkResult.Success -> logger.debug("attributeInstall: success")
            is NetworkResult.Failure -> {
                logger.error("attributeInstall failed: ${result.statusCode}")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.ATTRIBUTE,
                        endpoint   = "attribr-attribute",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    private fun sendEvent(
        name: String,
        value: Double?,
        currency: String?,
        metadata: Map<String, String>,
    ) {
        val body = buildBasePayload().apply {
            put("event_name", name)
            value?.let    { put("value", it) }
            currency?.let { put("currency", it) }
            if (metadata.isNotEmpty()) {
                val meta = JSONObject()
                metadata.forEach { (k, v) -> meta.put(k, v) }
                put("metadata", meta)
            }
        }
        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-track", bodyStr)) {
            is NetworkResult.Success -> logger.debug("trackEvent '$name': success")
            is NetworkResult.Failure -> {
                logger.error("trackEvent '$name' failed: ${result.statusCode}")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.EVENT,
                        endpoint   = "attribr-track",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    private fun sendDelete(): Boolean {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName

        val body = JSONObject().apply {
            put("app_id", appId)
            put("device_hash", deviceHash)
        }

        return when (val result = networkClient.delete("attribr-delete-user-data", body.toString())) {
            is NetworkResult.Success -> {
                // Sprint 13 — also clear the app-local install-instance so a
                // subsequent trackLaunch() re-generates a fresh hash and
                // classifies as reinstall_candidate on the server side.
                InstallInstance.reset(appContext)
                logger.info("deleteAllData: confirmed by server")
                true
            }
            is NetworkResult.Failure -> {
                logger.error("deleteAllData failed: ${result.statusCode}")
                false
            }
        }
    }

    private fun sendAdRevenue(
        adNetwork: String,
        adUnitId: String?,
        adFormat: String?,
        revenue: Double,
        currency: String,
    ) {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName

        val body = JSONObject().apply {
            put("app_id", appId)
            put("device_hash", deviceHash)
            put("ad_network", adNetwork)
            put("revenue_usd", revenue)
            put("currency", currency)
            adUnitId?.let { put("ad_unit_id", it) }
            adFormat?.let { put("ad_format", it) }
        }
        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-ad-revenue", bodyStr)) {
            is NetworkResult.Success -> logger.debug("trackAdRevenue ($adNetwork): success")
            is NetworkResult.Failure -> {
                logger.error("trackAdRevenue failed: ${result.statusCode} ${result.error?.message}")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.AD_REVENUE,
                        endpoint   = "attribr-ad-revenue",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    private fun sendPushToken(fcmToken: String) {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName

        val body = JSONObject().apply {
            put("app_id", appId)
            put("device_hash", deviceHash)
            put("platform", "android")
            put("push_token", fcmToken)
        }

        when (val result = networkClient.post("attribr-push-token", body.toString())) {
            is NetworkResult.Success -> logger.debug("registerPushToken: success")
            is NetworkResult.Failure -> {
                // Push token registration is best-effort — no queuing
                logger.error("registerPushToken failed: ${result.statusCode} ${result.error?.message}")
            }
        }
    }

    private fun sendSubscriptionEvent(
        productId: String,
        eventType: String,
        revenue: Double?,
        currency: String,
        originalTransactionId: String?,
    ) {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName

        val body = JSONObject().apply {
            put("app_id", appId)
            put("device_hash", deviceHash)
            put("product_id", productId)
            put("event_type", eventType)
            put("currency", currency)
            put("store", "android")
            revenue?.let { put("revenue_usd", it) }
            originalTransactionId?.let { put("original_transaction_id", it) }
        }
        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-subscription-event", bodyStr)) {
            is NetworkResult.Success -> logger.debug("trackSubscriptionEvent '$eventType' ($productId): success")
            is NetworkResult.Failure -> {
                logger.error("trackSubscriptionEvent failed: ${result.statusCode}")
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.SUBSCRIPTION_EVENT,
                        endpoint   = "attribr-subscription-event",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    private fun sendRevenue(
        amount: Double,
        currency: String,
        productId: String?,
        transactionId: String,
        store: String,
        eventType: String,
    ) {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName

        val body = JSONObject().apply {
            put("app_id",         appId)
            put("device_hash",    deviceHash)
            put("amount",         amount)
            put("currency",       currency)
            put("transaction_id", transactionId)
            put("store",          store)
            put("event_type",     eventType)
            productId?.let { put("product_id", it) }
        }
        val bodyStr = body.toString()

        when (val result = networkClient.post("attribr-revenue", bodyStr)) {
            is NetworkResult.Success -> logger.debug("trackRevenue '$eventType' ($transactionId): success")
            is NetworkResult.Failure -> {
                logger.error("trackRevenue failed: ${result.statusCode}")
                // NOTE: EventKind.REVENUE must be added to the EventKind enum in EventQueue.kt
                // when the supporting files are restored from the pending rebase.
                eventQueue.enqueue(
                    QueuedEvent(
                        id         = UUID.randomUUID().toString(),
                        kind       = EventKind.REVENUE,
                        endpoint   = "attribr-revenue",
                        method     = "POST",
                        payload    = bodyStr,
                        enqueuedAt = System.currentTimeMillis(),
                        attempts   = 1,
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Queue flush — drains all queued events in FIFO order
    // -------------------------------------------------------------------------

    private fun flushQueue() {
        val pending = eventQueue.peek()
        if (pending.isEmpty()) return
        logger.info("Flushing ${pending.size} queued event(s)")

        for (event in pending) {
            val result = if (event.method == "DELETE") {
                networkClient.delete(event.endpoint, event.payload)
            } else {
                networkClient.post(event.endpoint, event.payload)
            }

            when (result) {
                is NetworkResult.Success -> {
                    logger.debug("Flushed event ${event.id} (${event.kind.value})")
                    eventQueue.remove(event.id)
                }
                is NetworkResult.Failure -> {
                    logger.debug("Flush failed for event ${event.id} — keeping in queue")
                    eventQueue.markFailed(event.id)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds the common payload fields sent with every request. */
    private fun buildBasePayload(): JSONObject {
        val deviceHash = deviceIdentifier.getHash()
        val appId      = appContext.packageName
        val osVersion  = Build.VERSION.RELEASE
        val appVersion = try {
            @Suppress("DEPRECATION")
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        return JSONObject().apply {
            put("app_id",      appId)
            put("device_hash", deviceHash)
            put("os_version",  osVersion)
            put("app_version", appVersion)
            put("platform",    "android")
            // Sprint 13 — app-local install-instance ID hash. Enables the
            // backend's `reinstall_candidate` classification on the second
            // installation of the app on the same device (SharedPreferences
            // is wiped on uninstall, so the hash changes across reinstalls).
            put("sdk_install_instance_id_hash", InstallInstance.hashHex(appContext))
        }
    }

    private fun requireInitialized() {
        check(isInitialized) {
            "Attribr.initialize(context, apiKey) must be called before using the SDK"
        }
    }
}
