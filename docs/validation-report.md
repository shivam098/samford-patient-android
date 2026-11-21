# Validation report — Samford Patient v0.1.0

Evidence only; anything not run is listed under "Not verified". Environment for local runs:
Windows 11, JDK 21.0.13 (Microsoft build), Android SDK platform 37 (build-tools 37.0.0),
Gradle 9.9.2, AGP 9.5.2, Kotlin 2.4.21. Date: 2026-11-21 (IST).

## 1. Build and static checks (local)

| Command | Result |
|---|---|
| `gradlew :app:assembleDebug :app:assembleRelease :app:printApkSizes` | BUILD SUCCESSFUL; **release (R8 + resource shrinking, with the CI-generated Baseline Profile) 2.43 MB** (`assets/dexopt/baseline.prof` 13 KB) |
| `gradlew :app:lintDebug` | 0 errors, 1 warning (Compose BOM 2026.11.01 exists but needs compileSdk 37.1, which is not installed; 2026.09.00 is used) |
| `gradlew :app:compileDebugAndroidTestKotlin :baselineprofile:assemble` | BUILD SUCCESSFUL |

## 2. Unit, Robolectric and Compose UI tests (local)

`gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleRelease` (and `:app:recordRoborazziDebug`,
which runs the same tests and writes screenshots): **71 tests, 0 failures, 7 skipped** (the 7 backend
and cross-app tests skip without a backend; see §3 and §4).

| Suite | Tests | Covers |
|---|---|---|
| `SyncEngineTest` | 12 | quiet first-sync backfill; steady state = exactly 1 conditional request; amendment via notification → feed → record with v1 kept; feed ETag/304 skips the record; `429` + `Retry-After` honoured even by manual refresh (≤10 % spread); exponential backoff bounds; failed record fetch retried next run; expired vs revoked token; records of another patient never applied; pharmacy state → Home item with recipient name; baseline paging of notifications; idempotent viewed acks |
| `BackoffPolicyTest` | 6 | equal-jitter bounds per attempt, 6 h cap, Retry-After floor/spread, jitter diversity; **1 lakh simulated installs spread evenly across the 30-minute window (every minute 2,800–3,900)** |
| `BlobStoreTest` | 5 | SHA-256 verified download then zero-request reopen; corrupted download rejected and not cached; LRU eviction within the cap, pinned sample pages kept, recently opened page survives; 8 concurrent opens = 1 download; clearing downloads keeps pinned pages |
| `SessionAndAuthTest` | 7 | sign-in stores token and family profiles; **the first adult profile opens even when a child is listed first, and re-sign-in keeps the last chosen profile**; wrong code shows attempts left; **sign-out wipes records, versions, pages, Home items, acks, sync state, blob index, page files, share PDFs, app lock and token, keeping server address**; a different mobile on the same phone never sees the previous records; sample mode replaced on sign-in; revoked device wipes, expired token keeps records readable |
| `NotifierTest` | 2 | notification text is generic ("2 new updates": no names, doctors, tests or medicines), private visibility with a public lock-screen version, each item notified once; with notifications off, items are marked without posting |
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

Backend: `shivam098/samford-web-app` at `dffde17` (seed with the doctor app's real `samford-ink/1`
pages and a real prescription addendum for patient-b), started with
`npm run mobile:isolated -- --port 4321 --data <temp> --reset --relaxed-limits --test-support`.
(The same tests also passed earlier against `83be4ae`, M2.)

`gradlew :app:testDebugUnitTest --tests "com.samford.patient.integration.BackendIntegrationTest" --tests "com.samford.patient.integration.BackendUiJourneyTest" "-Psamford.itBase=http://127.0.0.1:4321"`
→ **5/5 passed**:

| Test | Result |
|---|---|
| Seeded family `9000000001`: sign in with the returned demo code, sync, download every current page and verify SHA-256; dispensed state present | passed; first sync **6 requests**, steady state **1 request (304)** |
| API-driven stand-in tablet completes a visit for a fresh patient (2-page prescription to pharmacy, investigations to `central-lab`) → patient signs in → **page hashes and bytes identical**; pharmacy dispenses → Home "Medicines dispensed" (3 requests); lab collects and releases Haemoglobin → result and Home item; doctor addendum → v2 with reason, **v1 pages unchanged**, Home "amended"; viewed ack accepted once | passed |
| Another family (`9000000003`) gets 404 for patient A's document, record and page blob | passed |
| Second family (`9000000002`): the seeded prescription has versions 1 and 2, v2 reason "Corrected dose and duration (synthetic sample)", v2 = the unchanged v1 page plus one new page (all hashes verified), latest recipients PATIENT `AVAILABLE` and PHARMACY `DISPENSED`; acknowledging an unread document succeeds once | passed |
| `BackendUiJourneyTest`: the real screens, HTTP client and sync stack (Robolectric) sign in with the server's demo code, reach Home with items, open Records and a handwritten page | passed |

**Defect found by these tests and fixed:** the server lists family profiles by name, so for
`9000000001` "Demo child A" came first and became the active profile, leaving Home empty after
sign-in. Sign-in now opens the first adult profile and keeps the user's last choice on re-sign-in.

**Live public demo (read-only check, once):** discovery gist `status: online`, `apiBaseUrl`
`https://preview-ellen-excitement-continuing.trycloudflare.com/api/mobile/v1` (backend `518864a`),
`GET /meta` 200. `BackendIntegrationTest.seededFamilySignsInAndEveryPageMatchesItsHash` with
`-Psamford.itBase=https://preview-ellen-excitement-continuing.trycloudflare.com` → **passed** over
HTTPS through Cloudflare: first sync 6 requests, steady state **1 request answered 304** (weak ETag
round-trips), 4 handwritten pages downloaded and SHA-256 verified. Other tests that create visits or
acknowledge documents were not run against the shared live server.

## 4. Cross-app end to end with the real doctor tablet app (local)

1. Doctor app `shivam098/samford-doctor-android` at `2cbc7a5`, its real freeze/outbox/upload code:
   `SAMFORD_IT_API_BASE=http://127.0.0.1:4321/api/mobile/v1 SAMFORD_E2E_OUT=<file> gradlew :app:testDebugUnitTest --tests "com.samford.doctor.integration.BackendIntegrationTest.crossAppE2EProducer"` → BUILD SUCCESSFUL, handoff written
   (visit `visit-b97311cb-…`, Visit summary + Prescription, 1500×2121 PNG pages, PDF and ink hashes).
2. Patient app: `gradlew :app:testDebugUnitTest --tests "com.samford.patient.integration.CrossAppPatientVerifyTest" "-Psamford.e2eIn=<file>"`
   → **passed**: signed in with the handoff mobile via OTP, synced, and for each document the page
   SHA-256 list and the downloaded bytes were identical to what the tablet signed; PDF hashes matched.
   Re-run against backend `dffde17`: producer BUILD SUCCESSFUL (visit `visit-74459b5b-…`, Visit
   summary + Prescription), verifier passed.
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
