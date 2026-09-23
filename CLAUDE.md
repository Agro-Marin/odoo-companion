# Odoo Companion — Android Device Agent

Android app (Kotlin, Gradle) that turns a company-owned phone into an Odoo
`remote.device`: it pushes **location**, **call logs**, and **call recordings**
to the `device_mobile` module (an addon in the `agromarin` repo) over
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

Requires JDK 25, the version pinned in `tools/versions.env`, and Android
SDK 37; `local.properties` must point at the SDK
(`sdk.dir=…`, git-ignored).

```bash
./gradlew assembleDebug              # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest          # JVM unit tests
tools/lint.sh [--fix] [--static-only] # ktlint + detekt (+ Android Lint)
tools/check-release-wire.sh          # after assembleRelease: R8 kept the payload
tools/smoke-test.sh <url> <id> <tok> # replay real payloads, assert every answer
```

**`tools/lint.sh` refuses a tool that is not the pinned version**, which is in
`tools/versions.env` and read by CI from the same file. It is not pedantry:
ktlint is exact in both directions, and 1.5.0 joins a multi-line parameter list
onto one line while leaving its trailing comma — which the pinned 1.3.1 rejects
and cannot autocorrect. Set `KTLINT=/path/to/ktlint` rather than reaching for
whatever is on `PATH`.

For end-to-end testing, run any Odoo 19 instance with `device_mobile`
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
  kept. The scanner has no cursor, so the outbox is the harvest's only memory of
  a file: a file whose row is gone is queued and uploaded again. That is why a
  delivered file that will not delete stays as a `kept_on_disk` row, and why the
  90-day purge re-stamps a dead row whose audio it still cannot delete instead
  of forgetting it. A `kept_on_disk` row was delivered, so the status screen's
  undeliverable count (`countUndeliverable`) leaves it out; `countDead` still
  counts every dead row. On Android 10 without `WRITE_EXTERNAL_STORAGE` that is
  every uploaded recording, each of which used to read as a failure with a hint
  to go and fix the device in Odoo.
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
  cannot be added without deciding what makes it visibly broken — and
  `DeviceHealth.wantedPermissions` takes the same two, so a collector that is
  off costs no prompt. The legacy storage pair is asked for, and declared, only
  up to API 29; from 11 the folder takes all-files access, which no runtime
  prompt grants. An approximate location grant leads on to the all-the-time
  prompt as a precise one does.
  On Android 10 the check also wants `WRITE_EXTERNAL_STORAGE` (declared to API 29):
  measured on an API 29 emulator, the harvest read and uploaded a recording owned by
  `sdcard_rw` and then could not delete it, so every uploaded recording stayed on the
  phone for ever — the one level where `requestLegacyExternalStorage` is in force is
  the one where reading is not deleting.
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
- **A policy whose every value is unusable is still a policy.** `ManagedValues.isEmpty`
  used to be computed from the parsed values, so a bundle carrying only a malformed
  `base_url` read as "policy withdrawn" and unlocked the form under an MDM that was
  speaking. `fromBundle` sets `policyPresent` from the bundle itself.
- **A collector chained ahead of an upload does not ask for a third drain.** The
  `collect-now` chain tags its collectors `UPLOAD_FOLLOWS`; a tagged worker queues
  and leaves the chained `UploadWorker` to drain, instead of enqueueing `upload-now`
  beside it — the third concurrent drain the outbox section counts.
- **An http:// base URL is refused at enrolment on a build that cannot send it.**
  `NetworkSecurityPolicy.isCleartextTrafficPermitted` is the platform's own answer;
  before, a release build stored the URL and every upload failed in the socket with
  a message that named the network, not the mistake.
- **An absent restrictions service is not a withdrawn policy.**
  `ManagedConfig.read` returns `null` when there is no `RestrictionsManager`, and
  `applyConfiguration` then leaves the managed configuration alone; only a service
  that answers with an empty bundle means the policy was withdrawn. Mapping both
  to empty unlocked the form on a managed handset whenever the service was
  unavailable, which is the invariant above it turned inside out.
- **The call-log cursor is the row `_ID`, not a timestamp.** Android writes a call's
  row when the call ends, stamped with its start, so `DATE` lost a call that ended
  after a shorter, later one had been synced; `LAST_MODIFIED` fixed that and still
  lost rows stamped after a backwards clock correction. An id is assigned by the
  insert. The timestamp cursor is read once, to seed the id cursor on upgrade, and
  an id cursor above the provider's newest id means the log was cleared or
  restored, so the worker reads it again from zero. `CallLogCursorTest` (in
  `CallLogReaderTest.kt`) constructs the losses.
- **A bounded read counts what it reads, not what it keeps, and says so.**
  `CallLogReader` stops at 1 000 *scanned* rows and reports `moreWaiting`; the
  worker chains another pass rather than reporting success and sleeping for its
  period. Counting stored rows walked a mostly-blank log in one pass while
  reporting `moreWaiting = false`. Same shape as `DrainReport.moreWorkPending`.
- **Uploads are at-least-once, and the server deduplicates — at two layers.**
  A `remote.device` in the **Mobile Phone category** is created with
  `duplicate_detection_enabled` and a 900 s window, so a retry of a committed
  request answers **409** within that window. Past it the transport sees a new
  request and the *model* dedup catches it instead, which used to leave
  `accepted: 0` and so a **422** — read as a refusal, dead-lettering records the
  server was holding. `device_mobile` reports `duplicates` in its own counter
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
  **And a server that has once named itself must keep doing so.** `device_mobile`
  now puts `"service": "remote_mobile"` in every reply; the first time a device
  sees it, `DeviceConfig.learnServerNamesItself` remembers, and from then on a
  row-removing reply without it is retried as not-Odoo — which closes the
  `{"error": ...}` gateway envelope that `isOdooReply` alone lets through. Learned
  per base URL: a re-enrolment onto a different server forgets it, so an older
  Odoo is still believed. Never require the key unconditionally; a handset talks
  to whatever Odoo it points at.
  **Verified against a live Odoo, not reasoned about.** `ServerContractTest` pins
  the responses `tools/smoke-test.sh` and targeted probes captured from a real
  `device_mobile` on a provisioned mobile-phone device (50 MB cap, 900 s dedup
  window, both read off the record), last on 2026-09-23, and classifies each twice —
  before and after the client has learned that the server names itself. Refusals
  from the integration layer are RFC 9457 problem documents, so their `"status"` is
  an integer, never `"success"`. `400 invalid_json` is retried, not refused: this
  client only sends what it serialised, so a server that cannot parse the body
  received a damaged one, and refusing it dead-lettered a call as `refused`.
  The smoke test asserts; it used to print, and two of its printed expectations
  were wrong (a resent batch answers 409 inside the dedup window, and its recording
  was stamped at the call's start, where no real recording is — which is how it
  passed against a matcher that linked no real recording of a call over a minute). The one that settles the design is the
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
  where it stays visible — **only if its last failure was a server verdict.**
  `markFailed` records `serverFault` (a 5xx other than 502/503/504, or a single
  row over a cap) and `markUnreachable` requires it. **A 500 on a batch is
  narrowed like a 413**: the ingest wraps one row's exception, so the batch halves
  until the row that raises stands alone, and only that row is charged — before,
  one poison row retired ninety-nine good ones after three revivals. Before that, a 401 with no positions flowing
  reached `refused` in about thirty drains: budget-dead, one-row probe, 401, dead
  again, three times — and a rotated token fixed afterwards revived nothing.
  `LinkFaultTest` constructs it. `reviveUndecodable` resets `revivals` too: a new build is
  a genuinely new condition.
- **A payload cap is a server setting, so a 413 is kept — but it is not retried
  unchanged.** `_payload_too_large` puts the device's real limit in the body
  (`"Request exceeds maximum size of NKB"`), which the client parsed into a log line
  and threw away while re-sending an identical batch, because `LOCATION_BATCH`,
  `CALL_LOG_BATCH` and `MAX_RECORDING_BYTES` are compile-time constants. Now
  `UploadOutcome.TooLarge` carries the declared limit: a multi-row batch halves and
  retries within the same drain, a single row is charged, kept and deferred (raising the cap
  is an operator action, so `refused` would be wrong) and the revival bound above is
  what stops it cycling. **`MAX_RECORDING_BYTES` is the same policy, not a second
  one**: it is the 50 MB mobile-category cap divided by base64's 4/3, and a file over
  it takes the kept-and-charged path too, where it used to be `refused` outright.
  A 413 body now also carries `limit_bytes`, which the client prefers to the
  kilobyte-rounded message when it is there.
  **The limit is also stored, because not sending is the whole point.** A 2 MB
  recording against a 1 MB cap pushed **74 MB** before it settled — 28 attempts, each
  streaming the full base64 body, because nothing remembered what the server had
  already said. `DeviceConfig.learnPayloadLimit` keeps it, `drainRecordings` asks
  `client.recordingWireSize` before posting and declines to send what the server has
  declared it will not take, and `learnPayloadLimit(0)` on any revival drops it so a
  raised cap is discovered — and **it expires after a day** (`PAYLOAD_LIMIT_TTL_MILLIS`),
  because on an emulator with no positions flowing a cap raised in Odoo was never
  re-learned: the only request the phone made was the declined recording's, and
  the revival probe that would have dropped the cap was days away. Every success
  re-stamps it, so a working phone keeps the cap fresh for free. Same scenario after: **10 MB**, which is one probe per
  revival and is the cost of not going permanently blind to an operator's fix. The
  size asked for is `RecordingBody.contentLength()` itself, not a second copy of the
  base64 arithmetic. The gap is reachable —
  `mixin_inbound_gate.max_payload_size` defaults to **1 MB** and `device_mobile`
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
- **A verdict on one row never parks the queue.** A row the server ruled on — a
  500 from an ingest that raises on it, a single row over the cap, a recording over
  the declared cap or over `MAX_RECORDING_BYTES` — goes through `deferRuledOn`:
  charged as a server fault, given its own `retryAfter`, excluded from the rest of
  the pass, and the drain goes on. Only the 500 took that path at first; a
  recording over the cap returned `RETRY`, and as the oldest row it headed every
  `take`, so no recording behind it was ever sent and the worker walked into
  WorkManager's five-hour backoff, with the `upload-now` requests the location
  service makes (`KEEP`) waiting behind it. `RETRY` is for the link.
  A recording whose metadata will not decode is `undecodable`, exactly like a
  batch row, so `reviveUndecodable` finds it; it used to be charged as a link
  failure and went through the same head-of-line block.
  The cap a reply states applies to the rest of the same pass (`DrainTally.payloadLimit`)
  and is persisted once per drain, not once per batch.
- **`lastUploadAt` means a delivery, not a drain that did not error.** `recordUpload`
  took only the error, so an empty queue — `outcome=DONE, lastError=null` — wrote a
  fresh timestamp and the screen said `Last upload: just now` on a phone that had not
  reached the server in a week. `DrainReport.delivered` was already tracked for the
  revival logic and simply was not exposed. The converse holds too: a drain that
  delivered stamps it even when another row failed in the same pass. The status
  screen shows the last delivery and the last error as two lines, because a phone
  delivering every position while one recording waits out a 500 is neither
  "failed" nor "fine".
- **The form is filled once, then only where a policy governs it.** The store
  re-emits on every write — every upload records its attempt — and a managed phone
  used to repaint the whole form on each emission. Under a partial policy (a
  console that publishes only the switches) that wiped whatever enrolment the
  person was typing the moment any worker wrote to the store — and Save itself
  was such a writer: it stored each switch as its own edit before reading the
  enrolment fields, so changing a switch alongside typing the enrolment stored
  the switch and an empty enrolment (measured: `wifiOnlyUploads=true`,
  `baseUrl=""`). `DeviceConfig.saveForm` is one validated edit, taken from a
  snapshot of the form made before anything suspends, and it skips the keys the
  policy governs; a refused base URL now stores nothing, where it used to leave
  the switches written without a reconcile. It is the form's only write path:
  `saveEnrollment`, `setUploadWindow` and `setLocationInterval` repeated its
  validation and its clamping for tests alone and are gone, and the two setters
  left (`setFeature`, `setWifiOnlyUploads`) are `@VisibleForTesting` seams for
  states the form cannot produce.
- **The status text is a function of what it shows, and redraws when any of it
  changes**: the settings, `OutboxDao.counts()` (a Room `Flow`, so the queue lines
  follow every fix and every upload), a refused save (which used to be overwritten
  by the next redraw), and a re-read of the permissions on every `onResume`,
  because the battery and permission prompts only pause the screen. It used to
  redraw only when a setting changed, so the queue it showed was whatever it was
  when the screen last happened to redraw.
- **A learned server fact belongs to what it was learned from.** Whether the
  server names itself is forgotten on a new base URL; the payload cap, a setting
  on the device record, is forgotten on a new base URL *or* identifier.
- **`skipped`, `duplicates` and `undecodable` are three different numbers.** The
  server read it and could not store it; the server already had it; this build
  could not decode it. Reporting them as one told an operator that a device
  delivering perfectly was losing everything it sent. `discarded`, `lost` and `purged` were split off `expired` for the same reason:
  positions dropped now, a recording whose file the phone no longer has, and dead
  rows aging out after 90 days are unrelated events that shared a counter.
  **`skipped` on a 200 names its rows now.** The server used to report *how many* it
  could not store and not *which*, so a partial skip deleted rows it refused along
  with rows it took; narrowing the batch to find them was tried and reverted, because
  the counters are tallied before the narrowing. `device_mobile` now sends
  `skipped_indexes` beside the counter, indexes into the batch as posted, and the
  drain sets aside exactly those rows (`refused` for a call, discarded for a fix)
  and deletes the rest. An older server sending only the count is believed as
  before. Still unreachable in practice — `CallLogReader` filters blank numbers and
  `CallDirection.of` never yields a word the server does not know — but the hole is
  closed rather than documented.
- **A collector whose feature is off is cancelled, not merely not scheduled.**
  `schedulePeriodicWork` takes the whole `Settings` and reconciles: registering
  all three periodic workers unconditionally woke the process every 30 min and
  every hour on a device with call sync and recordings off, for a worker whose
  body is an early return. Skipping the enqueue alone changes nothing on a phone
  that already has the registration, so the off branch cancels. The upload stays
  registered either way — it is what drains a queue a previous enrollment left.
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

## Debug logging

`system/DebugLog.kt`'s `debug(TAG) { … }` writes only when the handset asks for
that tag — `adb shell setprop log.tag.OutboxDrainer DEBUG`, likewise
`LocationQueue`, `RecordingHarvest`, `CallLogSyncWorker` — so the drain's
per-batch decisions, how it classified each reply, the batching
cadence and the harvest counts are available on a release build without being
in every bug report. Counts, ids and codes only; `tools/lint.sh`'s path gate
covers `debug {}` bodies as well as `Log.*` calls. Not in `net/`: `OdooClient`'s
tests are plain JVM, where `android.util.Log` is an unmocked stub.

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
