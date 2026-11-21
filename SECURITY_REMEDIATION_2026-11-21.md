# Android consent and retry remediation

No public SDK API or endpoint changed. This reuses the existing unmerged consent-egress remediation and completes its erasure and race handling.

Consent withdrawal (and startup without a grant) removes tracking retry payloads while retaining explicit erasure requests. Queued dispatch is tied to the grant's generation, the transport rechecks before egress, and a failed in-flight request from an older generation cannot be queued after withdrawal/re-consent. A queue lock prevents late failures from repopulating a purged backlog. Deep-link retries honour the same suppression. `deleteAllData` remains available without analytics consent.

Verification: the full `:attribr:testDebugUnitTest` suite passes, 48 tests in 8 classes, zero failures/errors/skips. Tests include denied HTTP dispatch, actual localhost DELETE after withdrawal, in-flight withdrawal/re-consent, persisted purge, erasure retention and late queue rejection. Main Kotlin sources compile in that run. Use installed JDK 17 and the checked-in Gradle wrapper; no build/dependency version changes were made.

An HTTP request whose bytes were already sent cannot be undone by withdrawing consent. Existing server DSR remains the deletion path for previously delivered data. No extra network call or consent popup is introduced for server security monitoring. Test the change on a device (offline queue → revoke → relaunch → re-consent; delete while revoked) before publishing a targeted SDK update. No tag, binary, app release or publication was created here.
