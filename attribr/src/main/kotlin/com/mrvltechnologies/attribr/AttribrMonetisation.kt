package com.mrvltechnologies.attribr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ── Models ─────────────────────────────────────────────────────────────────

/**
 * Current entitlements for this Attribr customer.
 * Returned by [AttribrMonetisation.getEntitlements] and [AttribrMonetisation.validateReceipt].
 */
data class AttribrEntitlements(
    val tier: String,
    val features: List<String>,
    val installLimit: Int,
    val appLimit: Int,
    /** ISO 8601 string; null if no active subscription. */
    val validUntil: String?
) {
    /** True if a specific feature is available in the current tier. */
    fun hasFeature(feature: String): Boolean = features.contains(feature)

    /** True if the customer is on any paid tier. */
    val isPaid: Boolean get() = tier != "seed"

    companion object {
        /** Seed defaults — used as fallback when the server cannot be reached. */
        val seedDefaults = AttribrEntitlements(
            tier = "seed",
            features = emptyList(),
            installLimit = 2_000,
            appLimit = 1,
            validUntil = null
        )
    }
}

// ── Exceptions ─────────────────────────────────────────────────────────────

sealed class AttribrMonetisationException(message: String) : Exception(message) {
    class NotConfigured : AttribrMonetisationException(
        "Attribr.initialize() must be called before using AttribrMonetisation."
    )
    class InvalidReceipt : AttribrMonetisationException(
        "The receipt could not be validated by the Attribr server."
    )
    class TierNotSupported(val upgradeUrl: String = "https://attribr.dev/pricing") :
        AttribrMonetisationException("Receipt validation requires Founder tier or above. Upgrade at $upgradeUrl")
    class NetworkError(cause: Throwable) :
        AttribrMonetisationException("Network error: ${cause.message}")
    class ServerError(val statusCode: Int, val body: String) :
        AttribrMonetisationException("Server error $statusCode: $body")
}

// ── AttribrMonetisation ────────────────────────────────────────────────────

/**
 * Subscription and entitlement management for Attribr.
 *
 * Usage:
 * ```kotlin
 * // After a successful Google Play purchase:
 * val ent = AttribrMonetisation.validateReceipt(
 *     transactionId = purchase.purchaseToken,
 *     appId = "com.mrvltechnologies.yourapp",
 *     platform = "google"
 * )
 * if (ent.hasFeature("receipt_validation")) { ... }
 *
 * // At app launch (reads from Attribr cache — fast):
 * val ent = AttribrMonetisation.getEntitlements()
 *
 * // In "Restore Purchases" handler — see restoreEntitlements() docs below.
 * ```
 */
object AttribrMonetisation {

    private const val BASE_URL = "https://pblzmoxcwpqywuyubdim.supabase.co/functions/v1"
    private const val UPGRADE_URL = "https://attribr.dev/pricing"

    // ── Validate receipt ───────────────────────────────────────────────────

    /**
     * Validate a purchase token (Google Play) or transaction ID (Apple) against
     * Attribr's backend. Upserts the subscription record and rebuilds entitlements.
     *
     * @param transactionId Google Play purchaseToken, or Apple StoreKit 2 transaction ID.
     * @param appId Your app's package name, e.g. "com.mrvltechnologies.hawk".
     * @param platform "google" (default) or "apple".
     * @throws [AttribrMonetisationException.TierNotSupported] if on Seed tier.
     */
    @Throws(AttribrMonetisationException::class)
    suspend fun validateReceipt(
        transactionId: String,
        appId: String,
        platform: String = "google"
    ): AttribrEntitlements = withContext(Dispatchers.IO) {
        val apiKey = requireApiKey()
        val body = JSONObject().apply {
            put("platform", platform)
            put("app_id", appId)
            put("transaction_id", transactionId)
        }.toString()

        val (statusCode, responseBody) = post("$BASE_URL/validate-receipt", apiKey, body)

        when {
            statusCode == 403 -> throw AttribrMonetisationException.TierNotSupported(UPGRADE_URL)
            statusCode !in 200..299 -> throw AttribrMonetisationException.ServerError(statusCode, responseBody)
            else -> {
                val json = JSONObject(responseBody)
                if (!json.optBoolean("valid", false)) throw AttribrMonetisationException.InvalidReceipt()
                parseEntitlements(json)
            }
        }
    }

    // ── Get entitlements ───────────────────────────────────────────────────

    /**
     * Fetch current entitlements from the Attribr cache.
     * Fast — no Apple/Google API call. Call at app launch to gate features.
     *
     * Falls back to [AttribrEntitlements.seedDefaults] on any network error.
     */
    suspend fun getEntitlements(): AttribrEntitlements = withContext(Dispatchers.IO) {
        try {
            val apiKey = requireApiKey()
            val (statusCode, responseBody) = get("$BASE_URL/get-entitlements", apiKey)
            if (statusCode == 200) parseEntitlements(JSONObject(responseBody))
            else AttribrEntitlements.seedDefaults
        } catch (_: Exception) {
            AttribrEntitlements.seedDefaults
        }
    }

    // ── Restore entitlements ───────────────────────────────────────────────

    /**
     * Re-validate all active purchases and rebuild entitlements.
     * Call from your "Restore Purchases" button.
     *
     * Query purchases from the Play Billing Library yourself first, then call this:
     * ```kotlin
     * billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
     *     .setProductType(BillingClient.ProductType.SUBS).build()
     * ) { result, purchases ->
     *     if (result.responseCode == BillingResponseCode.OK) {
     *         purchases.forEach { purchase ->
     *             CoroutineScope(Dispatchers.IO).launch {
     *                 AttribrMonetisation.validateReceipt(
     *                     transactionId = purchase.purchaseToken,
     *                     appId = packageName
     *                 )
     *             }
     *         }
     *     }
     * }
     * // Then fetch the rebuilt cache:
     * val ent = AttribrMonetisation.restoreEntitlements(packageName)
     * ```
     */
    suspend fun restoreEntitlements(appId: String): AttribrEntitlements = getEntitlements()

    // ── Private helpers ────────────────────────────────────────────────────

    private fun requireApiKey(): String =
        Attribr.configuration?.apiKey ?: throw AttribrMonetisationException.NotConfigured()

    private fun parseEntitlements(json: JSONObject): AttribrEntitlements {
        val featuresArray = json.optJSONArray("features")
        val features = buildList {
            if (featuresArray != null) for (i in 0 until featuresArray.length()) add(featuresArray.getString(i))
        }
        return AttribrEntitlements(
            tier = json.optString("tier", "seed"),
            features = features,
            installLimit = json.optInt("install_limit", 2_000),
            appLimit = json.optInt("app_limit", 1),
            validUntil = json.optString("valid_until").takeIf { it.isNotEmpty() }
        )
    }

    private fun post(url: String, apiKey: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Attribr-Key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val rb = try { conn.inputStream.bufferedReader().readText() }
                     catch (_: Exception) { conn.errorStream?.bufferedReader()?.readText() ?: "" }
            Pair(code, rb)
        } catch (e: Exception) {
            throw AttribrMonetisationException.NetworkError(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, apiKey: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Attribr-Key", apiKey)
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val code = conn.responseCode
            val rb = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            Pair(code, rb)
        } catch (e: Exception) {
            throw AttribrMonetisationException.NetworkError(e)
        } finally {
            conn.disconnect()
        }
    }
}
