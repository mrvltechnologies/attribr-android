# Attribr SDK for Android

Install attribution + 30-day retention tracking, with first-class Google Play Install Referrer support.

**Version:** `1.4.0` — reinstall-candidate detection via app-local install-instance hashing.

## Consumption paths

Consume Attribr through **one** of the three paths below.

### 1. Local `includeBuild` — the MRVL default

In the consuming app's `settings.gradle.kts`:
```kotlin
includeBuild("/absolute/path/to/Attribr-by-MRVL/sdk/kotlin")
```
Then in `app/build.gradle.kts`:
```kotlin
implementation("com.mrvltechnologies:attribr:1.4.0")
```
This is what every MRVL dogfood app uses. Fastest iteration, no artifact publishing needed.

### 2. Maven Local — recommended for CI/CD dogfood

From `sdk/kotlin/`:
```bash
./gradlew :attribr:publishToMavenLocal
```
In the consuming app's `settings.gradle.kts` add `mavenLocal()` to `dependencyResolutionManagement { repositories { … } }`, then:
```kotlin
implementation("com.mrvltechnologies:attribr:1.4.0")
```

### 3. JitPack — the path for external (non-MRVL) consumers

This repo (`github.com/mrvltechnologies/attribr-android`) is public and split out from the MRVL
monorepo specifically so it can be published standalone via JitPack — no MRVL repo access, no
local filesystem path, required.

```kotlin
// settings.gradle.kts
repositories { maven { url = uri("https://jitpack.io") } }

// app/build.gradle.kts
implementation("com.github.mrvltechnologies:attribr-android:v1.4.0") // NOTE: JitPack resolves by the literal git tag name, including the "v"
```

The JitPack coordinate's group/artifact come from this repo's GitHub org/name
(`mrvltechnologies` / `attribr-android`, both lower-case — JitPack coordinates are
case-sensitive) — NOT the Maven groupId used inside `build.gradle.kts`
(`com.mrvltechnologies`) and NOT the monorepo's name. Verify a build exists for the tag you
depend on at `https://jitpack.io/#mrvltechnologies/attribr-android` before shipping.

## Release checklist (bumping the version)

1. Bump `version` in `attribr/build.gradle.kts` (both the module `version = "…"` and inside the `afterEvaluate { publishing { … } }` block).
2. Add an entry to `CHANGELOG.md` describing the change.
3. Bump `SDK_VERSION` in `defaultConfig.buildConfigField` if you rely on it at runtime.
4. Commit and tag: `git tag vX.Y.Z && git push origin vX.Y.Z` (matching the version bumped in step 1).
5. Verify JitPack build at `https://jitpack.io/#mrvltechnologies/attribr-android`.
6. Update the release-log rows in each MRVL Android app's `marketing.md`.

## Integration snippet

```kotlin
// Application.onCreate() — the key comes from BuildConfig (see "API keys" below), never a source literal
Attribr.initialize(this, apiKey = BuildConfig.ATTRIBR_API_KEY)
Attribr.setConsent(ConsentState.GRANTED)

// From ProcessLifecycleOwner or Activity.onResume()
Attribr.trackLaunch()
```

Play Install Referrer is queried automatically on first launch. See `ATTRIBUTION_METHODOLOGY.md` for what fields are collected and what is NOT stored (raw IP, raw UA, GAID/Android ID — none of these ever leave the device).

## API keys — which credential may ship in an app

| Key scope | May be compiled into a mobile app? | Notes |
|---|---|---|
| `sdk_ingest` ("Client key") | **Yes — the only class allowed** | Write-only ingest (launch / event / revenue / attribution). Cannot read installs, revenue, cohorts, retention or sources. Treat as extractable-by-design; the security boundary is its scope. |
| `full` | **NO — SERVER_ONLY** | Read + write on the whole Attribr API. Anyone who extracts it from an APK can read the customer's dashboard data. Never in an app, never in `BuildConfig`, never in `local.properties` of a mobile module. |
| `api_read`, `evidence_ingest` | **NO — server only** | Read API / server-to-server evidence plane. |

Generate the Client key in Attribr → Settings → API Keys. Even an `sdk_ingest` key is **never committed**:
inject it at build time (`local.properties`/`secrets.properties` → `buildConfigField`, or the
`ATTRIBR_API_KEY` environment variable) and fail Release builds when it is empty or a placeholder.
Pattern and verification steps: `ATTRIBR_SDK_KEY_INJECTION_STANDARD.md` in the Attribr monorepo.
Machine-readable policy for build gates: `sdk/KEY_POLICY.md` in the monorepo (`attribr-by-mrvl`).

