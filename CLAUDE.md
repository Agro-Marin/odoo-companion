# Odoo Companion — Android Device Agent

Android app (Kotlin, Gradle) that turns a company-owned phone into an Odoo
`remote.device`: it pushes **location**, **call logs**, and **call recordings**
to the `remote_mobile` module (an addon in the `agromarin` repo) over
Bearer-authenticated REST. The app is generic — no customer- or
deployment-specific logic; a device is configured with base URL + identifier +
token and talks to whatever Odoo those point at.

> **This is not an Odoo addon.** It never goes on an addons_path, and the
> AgroMarin Odoo coding guidelines (`doc/coding_guidelines.rst` in the `odoo`
> repo) do not apply here. Kotlin style is enforced by `.editorconfig` (ktlint
> `intellij_idea` style, 4-space indent, 100-col lines) and `config/detekt.yml`,
> which covers `app/src/test` as well as `app/src/main`. The trailing-comma
> rules are switched **off**, not set to "none": the code uses trailing commas
> throughout and ktlint neither adds nor removes them.
>
> **Read `README.md` first** — it is the deep reference: endpoints and payload
> shapes, the outbox architecture, MDM provisioning keys and their safety
> semantics, HTTP-vs-HTTPS policy, and how to diagnose a phone that stopped
> reporting. This file only carries what you need to not break things.

## Layout

Code lives under `app/src/main/java/com/odoocompanion/`, one package per
concern (`config`, `location`, `calllog`, `recording`, `data`, `sync`, `net`,
`system`, `ui`); JVM unit tests mirror the packages under `app/src/test/`.

**Policy is extracted from the Android component that triggers it**, in every
package that has one: `CallLogReader` from `CallLogSyncWorker`, `RecordingHarvest`
and `RecordingFilename` from `RecordingHarvestWorker`, `OutboxDrainer` from
`UploadWorker`, `LocationQueue` from `LocationForegroundService`, `StatusScreen`
from `MainActivity`. Each pair
exists because what a device uploads, keeps and discards has to be checkable
without WorkManager, a `LifecycleService` or a fused location provider — a rule
worth keeping the next time something is added to a callback because that is
where the data arrives.

## Build / test / lint

Requires JDK 21+ (CI builds on the 25 pinned in `tools/versions.env`) and Android
SDK 37; `local.properties` must point at the SDK
(`sdk.dir=…`, git-ignored).

```bash
./gradlew assembleDebug              # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest          # JVM unit tests
tools/lint.sh [--fix] [--static-only] # ktlint + detekt (+ Android Lint)
tools/check-release-wire.sh          # after assembleRelease: R8 kept the payload
tools/smoke-test.sh <url> <id> <tok> # replay real payloads against a live Odoo
```

**`tools/lint.sh` refuses a tool that is not the pinned version**, which is in
`tools/versions.env` and read by CI from the same file. It is not pedantry:
ktlint is exact in both directions, and 1.5.0 joins a multi-line parameter list
onto one line while leaving its trailing comma — which the pinned 1.3.1 rejects
and cannot autocorrect. Set `KTLINT=/path/to/ktlint` rather than reaching for
whatever is on `PATH`.

For end-to-end testing, run any Odoo 19 instance with `remote_mobile`
installed; a **debug** build on an emulator reaches a dev server on the host
machine at `http://10.0.2.2:<port>`.

## Invariants — do not regress these

- **A reconciliation lives in one place, and runs one at a time.** Startup, the
  `ACTION_APPLICATION_RESTRICTIONS_CHANGED` receiver and `BootReceiver` all launch
  `applyConfiguration`, two of them onto the same scope. Interleaved, a pass that
  read the configuration *before* a managed push could get the last word over one
  that read it after — and since `applyManaged` never clears an enrollment, the
  reachable direction is the bad one: the stale pass calls
  `LocationForegroundService.stop` on a handset the fresh pass just enrolled, and it
  reports nothing until the next trigger. It holds a `Mutex`. "Schedule the periodic work, then start
  or stop the location service" was written three times and had already drifted:
  `applyConfiguration` had the `else → stop` branch, `MainActivity`'s save handler
  did not, and `startIfEnrolled` never rescheduled work. Only `saveEnrollment`
  rejecting a blank base URL kept the missing branch unreachable, which is a
  coincidence of validation and not a design. `CompanionApp.applyConfiguration` is
  the single reconciler; both UI paths call it.
- **Everything goes through the outbox.** Producers write to the Room outbox and
  never upload inline; only the drain path uploads, and draining is serialised —
  two overlapping drains would send the same rows twice.
  **Serialised, but never queued.** `upload-periodic`, `upload-now` and the tail of
  the `collect-now` chain are three separate WorkManager items all running
  `UploadWorker`, against a default pool of `max(2, min(cpus - 1, 4))` — **three
  threads on a four-core handset, two on a budget one**. Measured with three
  concurrent drains, the two that had nothing to do each held a worker thread for
  the *whole* of the third (856 / 887 / 908 ms against 850 ms of work), and a drain
  carrying recordings runs for minutes, so `CallLogSyncWorker` and
  `RecordingHarvestWorker` could not start at all. `drainAll` therefore uses
  `tryLock` and returns `deferred` rather than waiting: 126 / 126 / 759 ms after.
  Nothing is lost because the *winner* owns the remainder — a `DONE` drain that
  still sees rows queued sets `moreWorkPending`, which covers exactly the rows that
  arrived after their kind's last `take`. Do not give the loser a chained follow-up
  instead: a five-minute recording drain would enqueue one per bail.
- **Positions are batched; call logs and recordings are not.** A fix waits up to
  `upload_window_seconds` (180 by default) or until twenty are queued, decided in
  `UploadCadence.dueNow` from the location callback itself — no timer, because
  fixes already arrive on a schedule and each one asks the question. A window of
  `0` restores the previous request-per-fix behaviour exactly. Do not reintroduce
  an unconditional `uploadNow` per fix: at the default interval that was 1,440
  requests, WorkManager passes, radio wakes and server-side `api.event.log` rows
  a day per handset, and it meant the 200-fix batch the drain is built around
  only ever assembled while the phone was out of coverage.
  **A backlog reintroduced it.** Both of `dueNow`'s conditions are permanently true
  once the queue is behind — the depth is over `BATCH_THRESHOLD` and the oldest row
  is older than the window — so measured over a simulated day behind a 5,000-row
  backlog, **240 of 240 fixes asked**, which is the same 1,440 a day the invariant
  was written against, arriving exactly when the handset is already struggling.
  `LocationQueue` keeps `askedAt` and will not ask twice inside one window, so the
  service holds **one** `LocationQueue` rather than building one per callback. A
  window of `0` still asks on every fix, unchanged. `askedAt` is `Long?` and not a
  sentinel: `now() - Long.MIN_VALUE` overflows negative, which silently suppressed
  every request and took five tests with it.
- **Only positions are discarded** — and *every* path that discards them is
  scoped to them. Positions are capped at the newest 20,000 (trimmed back to
  `TRIMMED_FIXES` so the anti-join is not paid on every fix), deleted when
  unstorable, and deleted when they exhaust the retry budget. A call log or a
  recording never leaves the queue by deletion: it is marked `deadAt`, stops
  being retried, and is counted on the status screen until
  `DEAD_RETENTION_MILLIS` (90 days) purges it, with its audio. `markDead` takes `keptKinds` and `deleteExhausted` takes `exceptKinds`, so the
  complement reads off the call site rather than out of this paragraph — both were
  called `kinds` and both were passed `OutboxKind.IRREPLACEABLE` on consecutive
  lines while meaning `IN` and `NOT IN`. An unscoped `markDead` put GPS fixes into
  the count an operator is meant to act on.
  **A recording whose audio is gone is the one loss that is not a deletion**, and it
  is counted as `lost`: the row went with nothing to show for it, because the file
  the phone was going to upload is no longer there. It reported nothing at all until
  `lost` existed — every counter on the report was zero.
- **Release builds are HTTPS-only.** Location, call metadata, and audio must not
  cross a network in the clear. Debug builds relax this via
  `app/src/debug/AndroidManifest.xml`; if a production host genuinely needs
  plain HTTP, pin that one host in a `network_security_config.xml` — never
  re-enable cleartext globally.
- **A managed-config push must never unenroll a working device.** Omitted or
  blank keys leave current values alone, and an unusable `base_url` or an
  `identifier` outside `[A-Za-z0-9._~-]` is rejected rather than applied.
- **A recording's row and its audio file are removed together.** Every terminal
  path deletes the file; while a row is dead-lettered the file is deliberately
  kept, because `RecordingScanner` filters on `lastModified() > cursor` and the
  harvest cursor has already moved past it — an orphaned file is both
  un-uploadable and un-rescannable.
- **The configured interval is the interval.** `app_restrictions.xml` calls
  `location_interval_seconds` "seconds between position reports" and the README
  repeats it, but `requestUpdates` set `setMinUpdateIntervalMillis` to *half* the
  interval, so the fused provider was free to report twice as often — doubling the
  fixes, the uploads, the `api.event.log` rows and the radio wakes an administrator
  thought they had configured, whenever anything else on the handset asked for a
  faster fix. It is `LocationForegroundService.requestFor(seconds)` now, a pure
  function on the companion like `fixOf`, because the app's single most important
  configured number was verified by no test at all.
- **Recording capture is harvest-only.** Android ≥10 cannot record calls from an
  app; `RecordingHarvestWorker` only picks up files an OEM dialer already wrote.
  `CANDIDATE_DIRECTORIES` and the filename parser are the per-OEM knobs — verify
  both against a real handset before trusting them.
- **A collector that cannot see its input says so on the status screen.** Harvest
  needs `MANAGE_EXTERNAL_STORAGE`; the manifest declares it and MDM grants it, but
  until it was checked a phone without it scanned an empty listing, queued nothing
  and reported success, with no blocker anywhere. `READ_MEDIA_AUDIO` does not
  cover `getExternalStorageDirectory()`. `blockers()` takes `recordingsWanted`
  alongside `callLogWanted` — both parameters are required, so a new collector
  cannot be added without deciding what makes it visibly broken.
  `Environment.isExternalStorageManager` is unshimmable (Robolectric 4.14.1 does
  not shadow it) and throws on a device with no external volume, so the probe is
  guarded: a health check must never be what crashes the screen.
- **A recording filename yields a number or nothing — never a timestamp, and the
  file's own mtime is what settles it.** A digit token is discarded when it *names
  the moment the file was written* (a `YYYYMMDD[HH[MM[SS]]]` local datetime, or
  epoch seconds/millis, landing within 6 h of `lastModified`) — not when it merely
  looks date-shaped. The pattern-only spelling failed twice: the original
  `DATE_LIKE` of eight digits could not `matches()` any token in the 10–15 band,
  so that branch's guard was dead code and `Call_20251028143501.m4a` uploaded
  `20251028143501` as the caller's number; widening it to a full compact datetime
  then swallowed **`2025102814`, which is the real NANP number 202-510-2814** — 9
  assigned area codes (201–209) sit inside `(19|20)\d{2}`. Only the mtime
  separates the two, and it is already a parameter. Verified against a 19-name
  corpus of OEM shapes.
- **A local fault never costs a record, and being stopped is a local fault.** A
  configuration the app cannot read is reported and retried; it does not touch the
  queue. The earlier spelling charged `markFailed` against the head of each kind, so
  25 drains of an unreadable DataStore silently deleted the 200 oldest positions —
  data destroyed by a fault on the phone, not a refusal from the server. The retry
  budget exists to stop retrying what the *server* will not take.
  **`runCatching` is how that came back.** It catches `Throwable`, so it catches
  `CancellationException`, so a drain WorkManager stopped — its ten-minute slot,
  constraints no longer met, or `ExistingWorkPolicy.REPLACE` on the `collect-now`
  chain when someone taps *Sync now* twice — was handed to `failBatch` and charged
  an attempt (measured: charged in 6 of 6 rounds). `OutboxDrainer.attempt` rethrows
  cancellation and catches the rest; `tools/lint.sh` fails on a bare `runCatching`
  anywhere in `sync/`, because detekt cannot see this one — `SwallowedException` and
  `TooGenericExceptionCaught` are both on and both inspect `catch` blocks, and
  detekt reported 0 smells over all 55 files with the defect in place.
- **A row this build cannot decode is not refused.** `deadReason` has a third
  value, `undecodable`: `refused` means no operator action helps, and a newer
  build that understands the payload is exactly such an action. `CompanionApp`
  notices the installed `versionCode` changing and calls `reviveUndecodable` once
  per upgrade. Without it a payload written by a newer queue format was dead for
  ever.
- **Every drain is bounded and says when it stopped early.** Recordings have a
  byte budget; JSON batches now have `BATCHES_PER_DRAIN`, and both set
  `moreWorkPending` so `UploadWorker` chains another pass instead of running 100
  requests inside one WorkManager slot. Same shape as `CallLogReader.moreWaiting`.
- **Harvest is for calls, not for everything with a waveform.**
  `CANDIDATE_DIRECTORIES` ends with a bare `Recordings`, which on a modern handset
  also holds voice memos; `NOT_CALL_DIRECTORIES` excludes the recorder subfolders
  by name. It is a per-OEM knob like the rest of the scanner — widen it from a
  real handset, not from guesswork, and never narrow it so far that
  `Recordings/Call` stops matching.
- **A damaged configuration file must leave a device that can be enrolled again.**
  The preferences store carries a `ReplaceFileCorruptionHandler`: without it a
  corrupt `companion_config.preferences_pb` threw `CorruptionException` from every
  read *and* every write, for ever — no uploads, no UI, no recovery short of
  clearing app data. Replacing it costs the enrolment, which is a state the status
  screen names and an MDM re-applies on the next start, so a managed fleet heals
  itself. The store is built by `DeviceConfig.storeFor`, not by a
  `preferencesDataStore` delegate: that delegate keeps a **process-global**
  instance, so the first test in the JVM to touch it bound the store to that
  test's `filesDir` and every later test silently reused a stale directory — a
  test that passed or failed on ordering alone.
- **An absent restrictions service is not a withdrawn policy.**
  `ManagedConfig.read` returns `null` when there is no `RestrictionsManager`, and
  `applyConfiguration` then leaves the managed configuration alone; only a service
  that answers with an empty bundle means the policy was withdrawn. Mapping both
  to empty unlocked the form on a managed handset whenever the service was
  unavailable, which is the invariant above it turned inside out.
- **A bounded read counts what it reads, not what it keeps.** `CallLogReader`
  stopped at 1 000 *stored* rows, so a log whose numbers are mostly blank walked
  the whole provider in one pass while still reporting `moreWaiting = false`.
- **Uploads are at-least-once, and the server deduplicates — at two layers.**
  A `remote.device` in the **Mobile Phone category** is created with
  `duplicate_detection_enabled` and a 900 s window, so a retry of a committed
  request answers **409** within that window. Past it the transport sees a new
  request and the *model* dedup catches it instead, which used to leave
  `accepted: 0` and so a **422** — read as a refusal, dead-lettering records the
  server was holding. `remote_mobile` reports `duplicates` in its own counter
  now and answers 200 when the batch is fully accounted for; the client also
  reads that counter out of a 422, because a handset in the field talks to
  whatever Odoo it points at. A device created **without** the category gets
  neither dedup layer (and a **1 MB** payload cap instead of 50 MB, which 413s
  every recording). Setting the category is part of provisioning.
- **A status code the client does not recognise is retried, never discarded.**
  `OdooClient.classify` lists what makes discarding safe — 400, 422, and 409 —
  and everything else keeps the payload. The reverse spelling (a short retry
  list and `else -> Rejected`) destroyed the queue on 404 (device archived in
  Odoo, or identifier mistyped) and on 413. Do not reintroduce a catch-all that
  deletes.
- **The status code alone never authorises a delete — the body has to prove Odoo
  answered.** `base_controller._json_response` is the only way this endpoint
  replies, on every path, so a response that does not parse as a JSON object is
  not Odoo and `classify` retries it whatever the code. Both directions were
  live bugs: a captive portal answering **200** with HTML read as
  `Success(0, 0, 0)` and the drain deleted the batch — call logs, which nothing
  else in this design ever deletes — while also setting `delivered`, which
  revives dead-lettered rows and resets their budget on a delivery that never
  happened; and a proxy or WAF answering **400/422** read as Odoo refusing the
  payload, which dead-letters a call as `refused`, the one state no operator
  action reverses. Retrying is safe *because* the server deduplicates: the
  re-sent batch comes back 409 once the real endpoint is reachable. A test that
  spells a refusal `setResponseCode(422)` with no body is testing a response
  this server cannot send — give it the `{"error": …, "message": …}` envelope.
  **"Parses as a JSON object" was too weak a proof, in the direction that
  deletes.** A gateway or WAF answering **200** with its own JSON error envelope
  read as `Success(0, 0, 0)`: measured, two queued call logs deleted, nothing
  dead-lettered, `accepted` empty — and `200 {}` additionally set `delivered`,
  which revived dead-lettered rows on a delivery that never happened. The server
  hands over a real discriminator on every path: `base_controller._json_response`
  is the only reply, every success body carries `"status": "success"`, and
  `_error_response` emits `{"error", "message"}` and never `status`. So a 2xx now
  needs `reportsSuccess()`, and any other row-removing code needs `isOdooReply()`.
  `"status": "success"` is in the endpoint's first commit, so no deployed server
  omits it.
  **Verified against a live Odoo, not reasoned about.** `ServerContractTest` pins
  the eight responses `tools/smoke-test.sh` and three targeted probes captured from
  a real `remote_mobile` on a provisioned mobile-phone device (50 MB cap, 900 s
  dedup window, both read off the record). The one that settles the design is the
  recording route: it answers `{"status": "success", "recording_id": …, "matched":
  …}` with **no `accepted` key at all**, so a count can never be the discriminator
  for a delivery and only `status` can. The 413 body said `"maximum size of 2KB"`
  against a `max_payload_size` of 2048, which is what `declaredLimit` parses. Those
  tests need no Android runtime and run in 0.3 s; regenerate them from a live
  server rather than editing the strings by hand.
- **A dead-lettered row can come back, and only one kind of it, and only
  `MAX_REVIVALS` times.** `deadReason` separates `budget` from `refused`: the first
  is put back by `reviveExhausted` the moment a drain the server answers proves the
  fault is gone, and by a one-row probe (`reviveOldestExhausted`) when the queue is
  entirely dead and would otherwise never send anything again. A refused row stays
  dead — no operator action makes it storable. Without this the retry budget was a
  one-way door: 25 drains at WorkManager's five-hour backoff ceiling is days, which
  is the timescale of the operator-fixable faults above, and the README's promise
  that the phone "holds its queue until they are corrected" ended there.
  **Unbounded, it was the opposite door.** `reviveExhausted` assumes budget
  exhaustion means a transient fault that the successful delivery has just
  disproved. That is false for a fault belonging to the *row*: a recording over the
  device's `max_payload_size`, or a record whose ingest raises — `mobile_push_calllog`
  wraps its ingest in `except Exception` and answers **500**, which is retryable, so
  a correctly provisioned fleet reaches this too. Measured over 120 drains, one such
  row was revived 4 times, never settled, cost 116 doomed POSTs, and `countDead()`
  was **0 at the end of every pass** — killed at the start of a drain and revived at
  the end of the same one, so the undeliverable counter and its hint could never
  fire, while the worker reported `RETRY` every pass and drove the periodic upload
  to `WorkRequest.MAX_BACKOFF_MILLIS` (5 h), delaying every position and call log on
  the handset. So `revivals` is a column, a revival grants `ATTEMPTS_AFTER_REVIVAL`
  (one probe, not a fresh 25 — delivery is known to work, one attempt settles it),
  and `markUnreachable` turns a row that has spent `MAX_REVIVALS` into `refused`,
  where it stays visible. `reviveUndecodable` resets `revivals` too: a new build is
  a genuinely new condition.
- **A payload cap is a server setting, so a 413 is kept — but it is not retried
  unchanged.** `_payload_too_large` puts the device's real limit in the body
  (`"Request exceeds maximum size of NKB"`), which the client parsed into a log line
  and threw away while re-sending an identical batch, because `LOCATION_BATCH`,
  `CALL_LOG_BATCH` and `MAX_RECORDING_BYTES` are compile-time constants. Now
  `UploadOutcome.TooLarge` carries the declared limit: a multi-row batch halves and
  retries within the same drain, a single row is charged and kept (raising the cap
  is an operator action, so `refused` would be wrong) and the revival bound above is
  what stops it cycling.
  **The limit is also stored, because not sending is the whole point.** A 2 MB
  recording against a 1 MB cap pushed **74 MB** before it settled — 28 attempts, each
  streaming the full base64 body, because nothing remembered what the server had
  already said. `DeviceConfig.learnPayloadLimit` keeps it, `drainRecordings` asks
  `client.recordingWireSize` before posting and declines to send what the server has
  declared it will not take, and `learnPayloadLimit(0)` on any revival drops it so a
  raised cap is discovered. Same scenario after: **10 MB**, which is one probe per
  revival and is the cost of not going permanently blind to an operator's fix. The
  size asked for is `RecordingBody.contentLength()` itself, not a second copy of the
  base64 arithmetic. The gap is reachable —
  `mixin_inbound_gate.max_payload_size` defaults to **1 MB** and `remote_mobile`
  raises it to 50 MB only inside `create`, only when `device_category_id` is already
  the mobile-phone category in the same `vals`.
- **The outbox column and the wire are one format, and now say which.** `WireJson`
  serialises what crosses the network *and* what sits in `OutboxEntry.payload`, for
  up to 90 days. Those are two compatibility contracts — the wire's peer is today's
  server, the column's peer is a future build of this app. `undecodable` and
  `reviveUndecodable` half-close it, but their promise of "a newer build that
  understands the payload" needs the newer build to know what it is reading, and
  there was no marker to branch on. `payloadVersion` is written on insert and read
  when a row will not decode, so the reason names the format rather than shrugging.
  It has to exist *before* it is needed: rows already queued cannot be marked
  retroactively.
- **`lastUploadAt` means a delivery, not a drain that did not error.** `recordUpload`
  took only the error, so an empty queue — `outcome=DONE, lastError=null` — wrote a
  fresh timestamp and the screen said `Last upload: just now` on a phone that had not
  reached the server in a week. `DrainReport.delivered` was already tracked for the
  revival logic and simply was not exposed.
- **`skipped`, `duplicates` and `undecodable` are three different numbers.** The
  server read it and could not store it; the server already had it; this build
  could not decode it. Reporting them as one told an operator that a device
  delivering perfectly was losing everything it sent. `discarded`, `lost` and `purged` were split off `expired` for the same reason:
  positions dropped now, a recording whose file the phone no longer has, and dead
  rows aging out after 90 days are unrelated events that shared a counter.
  **`skipped` on a 200 is the one hole left open deliberately.** The server reports
  *how many* it could not store and not *which*, so a partial skip deletes rows it
  refused along with rows it took. It is unreachable today — the server drops a call
  only for a blank number or an empty direction, and `CallLogReader` filters the
  first while `CallDirection.of` falls back to the raw type number, which
  `_DIRECTION_ALIASES` maps to `"unknown"`; GPS is the same story. Narrowing the
  batch to find them was tried and reverted: the counters are tallied before the
  narrowing, so a fixed mock double-counted 9 duplicates for 3 rows. **The fix is in
  `remote_mobile`** — return the refused items' indexes beside the counter — not
  here.
- **A collector whose feature is off is cancelled, not merely not scheduled.**
  `schedulePeriodicWork` takes the whole `Settings` and reconciles: registering
  all three periodic workers unconditionally woke the process every 30 min and
  every hour on a device with call sync and recordings off, for a worker whose
  body is an early return. Skipping the enqueue alone changes nothing on a phone
  that already has the registration, so the off branch cancels. The upload stays
  registered either way — it is what drains a queue a previous enrollment left.
- **A bounded read says so.** `CallLogReader` stops at 1 000 rows and reports
  `moreWaiting`; the worker chains another pass rather than reporting success
  and sleeping for its period. Same shape as `DrainReport.moreWorkPending`.
- **A managed value may arrive as text.** Several EMM consoles serialise every
  restriction as a string — integers *and* booleans. `Bundle.getBoolean` on a
  String returns `false`, so `wifi_only_uploads: "true"` sent call audio over
  metered cellular while `managed` locked the form. Both `booleanOrNull` and
  `secondsOrNull` accept the type the console chose; an unparseable value leaves
  the stored one alone rather than switching a working device off.
- **The app must start on `minSdk`, and only one test proves it.** `ContextCompat`
  implements `RECEIVER_NOT_EXPORTED` on **API 26–32** by registering the receiver
  behind a `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, and
  `obtainAndCheckReceiverPermission` **throws** when the app has not declared it.
  `CompanionApp.onCreate` registers exactly such a receiver, so without the
  `<permission>` + `<uses-permission>` pair in the manifest the process dies on
  Android 10, 11, 12 and 12L — every launch, boot and worker — while `minSdk = 29`
  claims to support them. Nothing caught it because **every other test runs at the
  default Robolectric SDK, which is `targetSdk` (36)**, and the crash is gone at
  API 33+ where `Api33Impl` takes over. `MinSdkStartupTest` is pinned `@Config(sdk
  = [29])`. Robolectric does not model the install-time grant of a self-declared
  signature permission (`PackageManager.checkPermission` says GRANTED while
  androidx's `PermissionChecker` says DENIED), so that test asserts the manifest
  the platform grants from, not a boot.
- **Two limits differ between the test JVM and a real handset; neither is visible
  here.** Robolectric links **SQLite 3.44.3**, whose host-parameter ceiling is
  **32,766** (measured: 32,767 is refused). Android 10–11 ship SQLite 3.28, where
  it is **999**. Room expands `IN (:ids)` to one placeholder per element and
  **does not chunk** (see the generated `OutboxDao_Impl`), so any unbounded id
  list is a crash on `minSdk` that no test here can reproduce. Keep `DELETE`s
  predicate-shaped rather than id-shaped.

## Privacy posture — what was checked, and what it costs

The app carries three kinds of personal data: positions, call metadata (number,
contact name, direction, duration) and call audio. A security pass over the whole
app found one live leak and confirmed the rest; both halves are recorded so nobody
re-derives them.

- **A recording's filename is personal data, so it never reaches a log.** An OEM
  dialer names the file after the counterparty — the project's own corpus is
  `call_5512345678.m4a` and `Call recording +525512345678_251028_143501.m4a` — so
  a log line carrying the path carries a phone number into logcat, into every bug
  report, and into whatever the OEM ships logs to. The **directory** is the useful
  diagnostic (it names the per-OEM folder that `CANDIDATE_DIRECTORIES` is a knob
  for) and holds nothing personal. `asLoggableDirectory` does that, the path is
  resolved into a local *before* the `Log` call so the rule needs no exemption, and
  `tools/lint.sh` fails on `absolutePath` or `filePath` appearing anywhere inside a
  `Log.*(…)` call in `app/src/main`. Both gates in that script have been shown to
  fire by reintroducing the regression they exist for; one that has never been seen
  to fail is worth nothing. `loggableDirectory` is a top-level `internal` function
  rather than a private helper for the same reason: it is the thing standing
  between a phone number and logcat, and the gate cannot see it returning the
  wrong answer — only the wrong identifier being named.
- **A guard that correctly matches nothing produces a clean run, a zero, and no
  signal that the mechanism was never exercised.** A third state beside "it will
  run" and "it has run", and it looks exactly like a pass. Test the shape by
  constructing the case where the guard MUST fire, never by observing it quiet.
  Applied here by breaking each guard and requiring its test to go red:
  `loggableDirectory` returning the whole path, `tally.unreachable` stuck at zero,
  `deferred` pinned false. Until that pass those three were asserted by **nothing**
  — `markUnreachable` was covered at the DAO level, so the SQL was tested and the
  number an operator reads was not — and `deferred` had only a timing assertion,
  which passes on a fast machine for the wrong reason. `anythingArrivedLate` looks
  like a fourth and is not: "a row queued mid-drain is not left until the next
  period" asserts `moreWorkPending` in a drain that never reaches the batch budget,
  so the flag can only have come from that check.
- **Backups and device transfer are excluded outright**, on both transports —
  `backup_rules.xml` for pre-Android-12 and `data_extraction_rules.xml` after it,
  each excluding `root`, `database`, `sharedpref` and `file`. Verified, not
  assumed: the token and the unsent queue stay on the handset they belong to.
- **Only `MainActivity` and `BootReceiver` are exported**, the first because it is
  the launcher and the second because `BOOT_COMPLETED` requires it; both of
  `BootReceiver`'s actions are system-only broadcasts and it returns immediately on
  anything else. The location service is `exported="false"`.
- **Accepted, not fixed**: the outbox and the preferences store are unencrypted
  app-private storage, so numbers, contact names, coordinates and the Bearer token
  rest behind the OS sandbox rather than behind a key. On a non-rooted managed
  handset with backups off that is the normal bar; SQLCipher and a Keystore-wrapped
  token are the higher one, and neither is free. `MainActivity` sets no
  `FLAG_SECURE`, so the enrollment screen can appear in a recents thumbnail — the
  token field is `textPassword`, so what shows is the base URL and identifier.
  Decide these deliberately rather than discovering them.

## Git

Single `main` branch. Use the AgroMarin commit format (`[TAG] area: summary` —
canonical §7 in the `odoo` repo's guidelines), with a package/area name in
place of an Odoo module name.
