# Client load model (patient app, 1 lakh users)

This models what **the patient app** asks of the server per user per day, so the backend can load
test against it (the backend session owns the server design and its M4 load test). It is a client
model, not a production capacity claim: the shared mock backend is a single Node/SQLite process.

## Measured request sizes (real mock backend, M2 commit `83be4ae`, HTTP/1.1, no TLS)

| Request | Status | Request bytes | Response headers | Response body |
|---|---|---|---|---|
| `GET /patient/notifications?cursor` + If-None-Match | 304 | 391 | 673 | 0 |
| `GET /patient/profiles/{id}/record` + If-None-Match | 304 | 380 | 673 | 0 |
| `GET /patient/profiles/{id}/record` (2 visits, 3 documents, 1 result) | 200 | 329 | 732 | 6,712 |
| `GET /patient/profiles/{id}/feed` (whole history) | 200 | 337 | 732 | 3,887 |
| `GET /patient/notifications` (whole history) | 200 | 327 | 732 | 1,494 |
| `GET /patient/profiles` | 200 | 312 | 731 | 211 |
| `GET /blobs/{sha256}` (seeded placeholder page PNG) | 200 | 366 | 771 | 40,891 |

Measured with `curl -w` (see [validation-report.md](validation-report.md)). The mock does not gzip
JSON; real handwriting pages are larger than the seeded placeholder (a 1240×1754 handwriting PNG is
typically 100–400 KB). TLS and HTTP/2 change the per-request overhead: HTTP/2 header compression
makes a repeated 304 poll much smaller than the 1.06 KB measured here.

On the public demo URL (Cloudflare tunnel in front of the same mock, backend commit `518864a`),
Cloudflare gzips JSON for clients that send `Accept-Encoding: gzip` (the backend measured a profile
list shrinking from 620 to 198 bytes). OkHttp sends that header and decompresses transparently, so
the JSON figures above are upper bounds; 304 replies have no body either way. The backend's
[scale design](https://github.com/shivam098/samford-web-app/blob/main/docs/scale-design.md) uses this
model as a cross-check (§1) and covers compression (§2.1).

Measured request counts with the real client against the same backend (`BackendIntegrationTest`):

| Situation | Requests |
|---|---|
| First sync after sign-in (family of 2: profiles, notifications, 2 feeds, 2 records) | 6 |
| Steady state, nothing new | **1** (notifications, 304) |
| Pharmacy dispensed one prescription | 3 (notifications 200, feed 200, record 200) |
| Reopening an already viewed page | 0 (immutable verified cache) |

## Client behaviour that drives the load

| Source | Frequency | Requests | Notes |
|---|---|---|---|
| Background probe (WorkManager) | every 30 min, 10-min flex, stable random phase per install | 1 × ≤48/day | connected network and battery-not-low only; Doze reduces it |
| Foreground probe | every 60 s while the app is visible | 1 per minute open | stops in the background |
| Profiles refresh | daily | 1 (usually 304) | |
| A change for a profile | per hospital event | +2 (feed, record) | only the changed profile |
| Pages | once per page, ever | 1 per page | when opened, or Wi-Fi prefetch of the last 90 days (≤40 MB/run) |
| Viewed acknowledgement | once per document version opened | 1 POST | idempotent |
| Pull-to-refresh | manual, ≤1/min/profile | 1 + 1 per profile (usually 304) | never overrides Retry-After |

## Per user per day

| Day type | Requests | Bytes (HTTP/1.1, measured sizes) |
|---|---|---|
| Quiet day, upper bound (48 background + 4 min open + profiles) | ~53 | ~56 KB (all 304s) |
| Quiet day, typical (Doze: ~30 background, 2 min open) | ~33 | ~35 KB |
| OPD visit day (+5 change bursts: visit completed with 3 documents, dispensed, sample collected, result, token called) | +15 | +60–120 KB JSON (record grows with history) |
| Opening the visit's handwriting once | +3–6 | +0.2–1.5 MB pages, downloaded once and cached |

A quiet month is about 1.7 MB of mobile data, mostly response headers.

## Fleet at 1 lakh installed users

| | Requests/day | Average rate | Peak assumption | Peak rate |
|---|---|---|---|---|
| Background probes | ≤4.8 M | ≤56/s | evenly spread by random phase | ~56/s |
| Foreground probes | ~0.4 M (4 min/user/day) | ~5/s | 10 % of users open the app in the same hour, 4 min each | ~11/s; extreme 10 % simultaneously: ~167/s |
| OPD changes (50 doctors × ~40 visits = 2,000 visits/day × ~15) | ~30 k | 0.35/s | morning OPD | <5/s |
| Pages (2,000 visits × ~4 pages, if all opened) | ~8 k | — | — | ~2 GB/day bytes |
| **Total** | **~5.2 M/day** | **~60/s** | | **~70/s typical, ~230/s extreme** |

More than 95 % of requests are conditional GETs answered with 304 and no body. Server-side, each
must be answerable from an index or in-memory watermark compare (cursor + ETag) without building
the response body. The backend confirmed the ETag excludes volatile signed URLs, so 304s stay
valid between signed-URL rotations.

## Spreading and back-pressure

- **Stable random phase:** `SyncPhase.initialDelayMillis` hashes the install ID into the 30-minute
  period. `BackoffPolicyTest.periodicPhaseIsStablePerInstallAndSpreadAcrossThePeriod` simulates
  1 lakh installs: every minute of the window receives 2,800–3,900 phones (3,333 expected), so
  there is no top-of-the-hour spike.
- **Failure storms:** exponential backoff with equal jitter (30 s base, 6 h cap), per scope and
  persisted, so a recovering server sees retries spread out rather than synchronised. The foreground
  loop honours the same backoff state, so an outage does not turn into 1 request/min from every open
  app.
- **Retry-After** from `429 RATE_LIMITED`/`OTP_RATE_LIMITED`/`OTP_LOCKED` is always honoured (also by
  pull-to-refresh), plus up to 10 % (max 60 s) spread.
- **Per-token limit:** the contract allows 600 requests/min/token; the app's peak is about 1–4/min.

## Recommendations for production (for the backend owner)

1. Serve `/blobs/{sha256}` from a CDN with the existing immutable cache headers (signed URLs or token
   validation at the edge); pages are the only large payload.
2. Enable gzip/brotli for JSON; record snapshots compress well and grow with visit history.
3. HTTP/2 or HTTP/3 to shrink repeated headers on 304 polls.
4. Keep a per-account notification watermark in memory so the probe is O(1).
5. As histories grow (years of visits), consider a per-visit or delta record endpoint; the client's
   event-hint design can switch to it without changing the UI.

## Battery and data on low-end phones

- No wake locks, no foreground service, no polling while the app is closed except WorkManager.
- Page downloads only on demand, or on unmetered networks with battery not low and storage not low.
- Release APK ≈ 2.3 MB (R8 + resource shrinking); bounded 200 MB page cache with LRU eviction.
