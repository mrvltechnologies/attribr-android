# Attribr Kotlin SDK — Changelog

## Build tooling — 2026-09-22 (Volta Android Phase 3.1A shared-platform repair)

- **Reverted the Sprint 14 AGP/Kotlin pin bump.** `settings.gradle.kts`'s `pluginManagement.plugins`
  block goes back to **AGP 8.12.0 / Kotlin 2.0.21** (from Sprint 14's 8.7.3/1.9.24). Gradle wrapper
  bumped 8.9 → 8.13 to satisfy AGP 8.12.0's minimum. **No public API, attribution semantics, consent
  behaviour, network contracts, install-identity, or event-schema changes** — dependency/build-tooling
  only, verified by this SDK's own full `clean test lint` (42 unit tests, 0 failures; lint 5
  warnings, 0 errors).
- **Why:** Sprint 14's own justification — "bumped... to match modern MRVL app AGP versions (Hawk
  Android is on 8.7.3)" — was checked directly against Hawk by MRVL's actual committed
  `android/gradle/libs.versions.toml` and is factually incorrect: Hawk is on AGP 8.12.0, not 8.7.3.
  Because this SDK is consumed by multiple product apps as a **mutable sibling checkout** via Gradle
  `includeBuild` (Volta directly; Hawk via a `packages/attribr` symlink to this same checkout), the
  Sprint 14 change silently broke composite-build resolution for every consumer pinned to 8.12.0 —
  discovered when it broke Volta Android's build mid-session, with an opaque
  `AgpVersionCompatibilityRule` failure that gave no indication of which sibling repo had changed.
  Full investigation, evidence, and the cross-repo consumer table:
  `volta_ev_platform/apps/volta-android/MRVL_ANDROID_TOOLCHAIN_COMPATIBILITY.md` (in the Volta
  monorepo, not duplicated here).
- **Known open risk, not resolved by this change:** FlashSeat's native Android host module declares
  AGP 8.7.3 / Kotlin 2.1.0 — different again from this reverted pin. Not independently build-verified
  (no Gradle wrapper in that repo, no system-wide `gradle` binary available in the environment this
  repair was made from). **If FlashSeat consumes this SDK via the same mutable-checkout
  `includeBuild` pattern, that team should verify their build against this reverted pin.**
- **Longer-term recommendation, not actioned here:** this SDK already has a working `maven-publish`
  block (group `com.mrvltechnologies`, artifact `attribr`) that has never actually been published
  anywhere (`~/.m2` has zero `com.mrvltechnologies` artifacts on the machine this was checked from).
  Moving product consumers to a real published, versioned artifact — rather than a mutable sibling
  checkout every app's build depends on simultaneously — would prevent this entire class of incident
  regardless of which app's session happens to touch this repo next.

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
