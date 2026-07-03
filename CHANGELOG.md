# Attribr Kotlin SDK — Changelog

## 1.4.0 — 2026-07-03 (Sprint 13)

- **App-local install-instance ID.** The SDK now generates a random UUID on first launch, persists it in the app's own `SharedPreferences("attribr", MODE_PRIVATE)`, and sends its SHA-256 hex digest to Attribr on every `trackLaunch()` in the new field `sdk_install_instance_id_hash`.
- The **raw UUID never leaves the device** — only the hash is on the wire.
- Enables Attribr's `reinstall_candidate` classification on the second installation of the app on the same device (Android wipes app-private storage on uninstall, so a reinstall generates a fresh UUID → a differing hash arrives at the backend).
- **Not an advertising ID, not a platform device ID, not fingerprinting.** No GAID/AAID/Android ID/serial/MAC/IMEI/Build.FINGERPRINT anywhere in the SDK.
- `deleteAllData()` now also resets the local install-instance so the user's GDPR-style request is honoured on-device.
- Backwards-compatible: absent field means the backend falls back to the Sprint 12 `returning_launch` path via `(app_id, device_hash)` alone.
- No public API changes. `initialize`, `trackLaunch`, `setConsent`, `attributeInstall`, `trackEvent`, `trackRevenue`, `trackSubscriptionEvent`, `registerPushToken`, `deleteAllData`, `handleDeepLink` all unchanged in signature.

## 1.3.0 — 2026-07-02 (Sprint 3)

- **Google Play Install Referrer as a first-class deterministic attribution signal.**
- `checkInstallReferrer()` now builds a structured `play_referrer` payload with:
  - `status` (`ok` / `feature_not_supported` / `service_unavailable` / `unknown_response` / `error`)
  - `response_code`
  - `install_referrer` (raw string, forwarded only when status = ok)
  - `referrer_click_timestamp_seconds` + `install_begin_timestamp_seconds`
  - `referrer_click_timestamp_server_seconds` + `install_begin_timestamp_server_seconds` (tamper-resistant, Google-signed)
  - `install_version`
  - `google_play_instant`
- **Every response is now sent to the backend**, including failure statuses, so the Data Confidence Score can observe Play Store availability as a health signal.
- Flat-field payload keys (`referrer_url`, `click_timestamp`, `install_timestamp`, `attribution_method`) are still sent on the `ok` path for backwards compatibility with old backend builds.
- No new permissions required.
- No advertising ID / GAID collected.
- One-attempt-per-install semantics preserved via existing `SharedPreferences` flag.

**Troubleshooting notes**

- Play Referrer is only queried on the FIRST launch after install. Users on a device where Attribr was already integrated before this SDK version was released will not see a referrer captured retroactively.
- Play Referrer is only available for installs that came through the Google Play Store. Side-loaded installs and installs from alternative app stores will report `feature_not_supported`.
- Emulators without Google Play services will report `service_unavailable`.
- Play Referrer data is only available from Google Play for approximately 90 days after install — collect it early, on first launch.
- A malformed referrer string (no known attribution params) is recorded as `play_referrer_malformed` on the backend and does not degrade the install response.
