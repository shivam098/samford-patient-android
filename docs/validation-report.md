# Validation report — Samford Patient v0.1.0

Evidence only; anything not run is listed under "Not verified". Environment for local runs:
Windows 11, JDK 21.0.13 (Microsoft build), Android SDK platform 37 (build-tools 37.0.0),
Gradle 9.9.2, AGP 9.5.2, Kotlin 2.4.21. Date: 2026-11-21 (IST).

## 1. Build and static checks (local)

| Command | Result |
|---|---|
| `gradlew :app:assembleDebug :app:assembleRelease :app:printApkSizes` | BUILD SUCCESSFUL; debug 15.66 MB, **release (R8 + resource shrinking) 2.30 MB** |
| `gradlew :app:lintDebug` | 0 errors, 1 warning (Compose BOM 2026.11.01 exists but needs compileSdk 37.1, which is not installed; 2026.09.00 is used) |
| `gradlew :app:compileDebugAndroidTestKotlin :baselineprofile:assemble` | BUILD SUCCESSFUL |

## 2. Unit, Robolectric and Compose UI tests (local)

`gradlew :app:testDebugUnitTest` (and `:app:recordRoborazziDebug`, which runs the same tests and
writes screenshots): **61 tests, 0 failures** (6 backend tests skipped without a backend; see §3).

| Suite | Tests | Covers |
|---|---|---|
| `SyncEngineTest` | 12 | quiet first-sync backfill; steady state = exactly 1 conditional request; amendment via notification → feed → record with v1 kept; feed ETag/304 skips the record; `429` + `Retry-After` honoured even by manual refresh (≤10 % spread); exponential backoff bounds; failed record fetch retried next run; expired vs revoked token; records of another patient never applied; pharmacy state → Home item with recipient name; baseline paging of notifications; idempotent viewed acks |
| `BackoffPolicyTest` | 6 | equal-jitter bounds per attempt, 6 h cap, Retry-After floor/spread, jitter diversity; **1 lakh simulated installs spread evenly across the 30-minute window (every minute 2,800–3,900)** |
| `BlobStoreTest` | 5 | SHA-256 verified download then zero-request reopen; corrupted download rejected and not cached; LRU eviction within the cap, pinned sample pages kept, recently opened page survives; 8 concurrent opens = 1 download; clearing downloads keeps pinned pages |
| `SessionAndAuthTest` | 6 | sign-in stores token and family profiles; wrong code shows attempts left; **sign-out wipes records, versions, pages, Home items, acks, sync state, blob index, page files, share PDFs, app lock and token, keeping server address**; a different mobile on the same phone never sees the previous records; sample mode replaced on sign-in; revoked device wipes, expired token keeps records readable |
| `HttpMobileApiTest` | 9 | Bearer + `X-Device-Id` + `X-Samford-App(-Version)` + If-None-Match, no cookies; 304; `Idempotency-Key` on POSTs and exact contract bodies; error envelope, `Retry-After`, `attemptsRemaining`, `DEVICE_SIGNED_OUT`; non-JSON 503; network failure; signed-out calls not sent; blob path only for valid hashes; API root normalisation; HTTP-date Retry-After |
| `ServerDirectoryTest` | 7 | discovery validated with `/meta` and remembered; last-good tried first; offline discovery message; update-required from discovery and `/meta`; manual override never silently replaced; release refuses plain HTTP to LAN hosts; QR payload formats |
| `AppLockTest` | 3 | PIN stored only as salted PBKDF2 hash; lock on launch and after 30 s; five wrong PINs pause entry |
| `PatientJourneyTest` (UI) | 6 | sign-in with demo code → Home → Records with "Sent to Samford OPD Pharmacy"; unregistered mobile and wrong code explained in plain language; sample mode: amended banner with reason, version 1 via history, back to latest; family profile switch shows only that person's visits; sign-out returns to Welcome with an empty database and cache; app lock hides records until the PIN |
| `ScreenshotTest` (UI) | 7 | 27 screenshots: phone (411×891 dp) and small phone (320×640 dp), English and Hindi, 1.5× large text, sign-in with demo code, lab result, settings |

**Screenshots reviewed by eye** (in [docs/screenshots](screenshots)). Issues found and fixed during
review: zoom hint was white on white; the amended banner took half of a small screen; the viewer
title wrapped on 320 dp; "Records" wrapped at 1.5× text; one Home item per lab value was noisy (now
grouped per order). Hindi strings render correctly; handwriting and doctor-typed text stay as written.

## 3. Real mock backend integration (local)

Backend: `shivam098/samford-web-app` at `83be4ae` (M2), started with
`npm run mobile:isolated -- --port 4321 --data <temp> --reset --relaxed-limits --test-support`.

`gradlew :app:testDebugUnitTest --tests "com.samford.patient.integration.*" "-Psamford.itBase=http://127.0.0.1:4321"`
→ **4/4 passed** (`BackendIntegrationTest`):

| Test | Result |
|---|---|
| Seeded family `9000000001`: sign in with the returned demo code, sync, download every current page and verify SHA-256; dispensed state present | passed; first sync **6 requests**, steady state **1 request (304)**, 4 pages verified |
| API-driven stand-in tablet completes a visit for a fresh patient (2-page prescription to pharmacy, investigations to `central-lab`) → patient signs in → **page hashes and bytes identical**; pharmacy dispenses → Home "Medicines dispensed" (3 requests); lab collects and releases Haemoglobin → result and Home item; doctor addendum → v2 with reason, **v1 pages unchanged**, Home "amended"; viewed ack accepted once | passed |
| Another family (`9000000003`) gets 404 for patient A's document, record and page blob | passed |
| Second family (`9000000002`) syncs, every existing version is readable, and acknowledging an unread document succeeds once | passed |

## 4. Cross-app end to end with the real doctor tablet app (local)

1. Doctor app `shivam098/samford-doctor-android` at `2cbc7a5`, its real freeze/outbox/upload code:
   `SAMFORD_IT_API_BASE=http://127.0.0.1:4321/api/mobile/v1 SAMFORD_E2E_OUT=<file> gradlew :app:testDebugUnitTest --tests "com.samford.doctor.integration.BackendIntegrationTest.crossAppE2EProducer"` → BUILD SUCCESSFUL, handoff written
   (visit `visit-b97311cb-…`, Visit summary + Prescription, 1500×2121 PNG pages, PDF and ink hashes).
2. Patient app: `gradlew :app:testDebugUnitTest --tests "com.samford.patient.integration.CrossAppPatientVerifyTest" "-Psamford.e2eIn=<file>"`
   → **passed**: signed in with the handoff mobile via OTP, synced, and for each document the page
   SHA-256 list and the downloaded bytes were identical to what the tablet signed; PDF hashes matched.
3. The doctor's prescription page fetched as the patient was viewed by eye: it carries the
   "SYNTHETIC SAMPLE - not for clinical use" banner and is 232,623 bytes with the expected SHA-256.

## 5. Load evidence

Measured request and response sizes against the same backend are in
[client-load-model.md](client-load-model.md) (`curl -w` on `/patient/notifications` 304, record 200/304,
feed, profiles, blob). The fleet figures there are a client model, not a production load test.

## 6. CI

Workflow [`.github/workflows/android.yml`](../.github/workflows/android.yml): build/lint/unit/UI and
screenshots; backend integration + stand-in and real doctor-app cross-app E2E; API 34 emulator
instrumented tests (`SampleModeDeviceTest`, `BackendDeviceTest` signing in through `10.0.2.2`),
device screenshots and Baseline Profile generation. Results of the first runs are recorded in §8.

## 7. Not verified

- Physical low-end phones (cold start, memory and jank were not measured on a real device; this PC
  cannot run an accelerated emulator, so device evidence comes only from the CI emulator).
- Real push (FCM is out of scope); notifications are local and polled.
- Production capacity at 1 lakh users (only a client model and single-node mock measurements).
- QR scanning with Google code scanner on a real phone (needs Play services and a camera).
- Accessibility with TalkBack beyond semantics in tests; human review of the Hindi translation.

## 8. CI results

_Pending first push._
