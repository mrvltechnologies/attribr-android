# Security

## What this SDK does with your keys

- `apiKey` (`attr_live_…` / `attr_test_…`) is sent as the `X-Attribr-Key` header on every request. It is never
  logged or included in crash reports.
- **Always use a Client key (`sdk_ingest` scope) — never a Full key.** A Client key can only submit ingest events;
  it cannot read install, revenue, cohort, retention or source data even if extracted from a shipped APK. A `full`
  key (or `api_read` / `evidence_ingest`) embedded in an app would let anyone who extracts it read the customer's
  Attribr data. Those scopes are SERVER_ONLY.
- Even a Client key must not be committed: inject it at build time (see README → "API keys").

## What this SDK sends about the device

- A SHA-256 hash derived from app-local state — never the raw Android ID, never GAID.
- No IP address, email, name, location, contacts or photos are collected by this SDK.
- Nothing is sent until `Attribr.setConsent(ConsentState.GRANTED)`.

## Reporting a vulnerability

Report privately via GitHub Security Advisories (repository → Security → Report a vulnerability). Do not open a
public issue for a security report.
