# Odoo Companion

An Android companion app that turns a company-owned phone into an Odoo
`remote.device`, pushing **location**, **call logs** and **call recordings** to the
`remote_mobile` module over Bearer-authenticated REST.

The app is generic: it holds no customer- or deployment-specific logic. A device is
configured with three values — base URL, device identifier, Bearer token — and talks
to whatever Odoo instance those point at.

## Requirements

| Piece | Version |
|-------|---------|
| Odoo | 19.0 with the `remote_mobile` module installed |
| Android | 10 (API 29) minimum, targets API 36 |
| Build | JDK 21+ (CI builds on 25), Android SDK 37, Gradle 9.7.1 |

## Endpoints used

All three are `POST`, authenticated with `Authorization: Bearer <token>`, where
`<identifier>` is the device's identifier in Odoo:

| Purpose | Route | Body |
|---------|-------|------|
| Location | `/remote/mobile/<identifier>/location` | `{"points": [ … ]}` |
| Call logs | `/remote/mobile/<identifier>/calllog` | `{"calls": [ … ]}` |
| Recordings | `/remote/mobile/<identifier>/recording` | one object with `audio_b64` |

The batch routes answer with `accepted`, `duplicates` and `skipped`: rows newly
stored, rows this device had already sent, and rows the endpoint read and could
not use. The three are separate because the app reports them separately — a
duplicate is a delivery that happened twice, and counting it as `skipped` told an
operator that a phone uploading perfectly was losing everything it sent.

A fix carries `latitude`, `longitude`, `timestamp` (epoch ms), and optionally
`accuracy`, `altitude`, `speed`, `heading` and `battery_level`. Every number is in
the unit `android.location.Location` reports it in — nothing is converted on the way
out — so: accuracy and altitude in **metres**, speed in **metres per second**,
heading in degrees east of true north, battery in whole percent. Two of those are
worth stating twice. `remote.data.log.gps` stores **speed in km/h** and does the
conversion itself, picking the factor from a `speed_unit` key that defaults to `mps`
when absent, which it always is here; sending km/h from the phone without that key
would be stored 3.6× too high and nothing would say so. And `altitude` is height
above the WGS84 ellipsoid, not above mean sea level, though the field it lands in
is labelled for the latter — the two differ by tens of metres. A call carries `number`,
`direction`, `timestamp`, `duration` and optional `contact_name`. `direction` is one of
`incoming`, `outgoing`, `missed`, `voicemail`, `rejected`, `blocked` — the app translates
Android's `CallLog.Calls.TYPE` itself, so the endpoint does not have to track an Android
enumeration. It used to send the raw integer, and when Android added
`ANSWERED_EXTERNALLY_TYPE = 7` (an incoming call taken on a paired watch) the server had
no mapping for it and dropped those calls silently. The endpoint still accepts the
numbers, for queues written by older builds — and a number it does not recognise is now
stored as `unknown` rather than dropped. Dropping it was the same fault one layer down:
an unmappable call left `accepted: 0`, the endpoint answered 422, and the phone
dead-lettered a call that had happened, waiting for whatever type Android defines
next.

A recording carries `number`, `recorded_at`, `file_name`, `mimetype` and base64
`audio_b64`.

## Architecture

```
LocationForegroundService ─┐
CallLogSyncWorker ─────────┼─→ Room "outbox" table ─→ UploadWorker ─→ Odoo
RecordingHarvestWorker ────┘      (survives reboot)     (WorkManager)
```

Every producer writes to a local outbox rather than uploading inline, so a phone with
no signal keeps collecting and flushes the backlog when it reconnects.

Positions travel in groups. A fix waits up to `upload_window_seconds` (180 by default)
for company, or goes immediately once twenty are queued — so at the default one-minute
interval four fixes share a request instead of each taking one of its own. That is
**1,440 requests a day per handset before, 360 after**, and a request is a WorkManager
pass, a radio wake and an `api.event.log` row on the Odoo side, all of which a fleet
multiplies. The cost is that the newest position on the map can be up to three minutes
older than it used to be; if a deployment needs a position the moment it is taken,
set `upload_window_seconds` to `0` and every fix is sent on its own, exactly as before.
Call logs and recordings are unaffected — they are collected on their own schedule and
upload as soon as they are found. `OutboxDrainer`
batches up to 200 positions or 100 calls per request; recordings go one at a time,
under a per-pass size budget, and the local file is removed with its queue row.
Draining is serialised — periodic, manual and post-collection uploads are separate
WorkManager requests, and two that overlap would otherwise send the same rows twice.

The location queue is capped at the newest 20,000 fixes (about two weeks at the default
interval): a phone out of coverage would otherwise queue without limit, and the periodic
upload cannot trim it because it is network-constrained and never runs while offline.
The trim cuts back to 19,000 rather than to the cap — it is a delete with a
20,000-row anti-join, and stopping exactly at the cap meant the next fix put the
queue over it again, so it ran on every fix for as long as the phone was out of
coverage (16.7 ms a fix against 1.8 ms with a short queue).

**Only positions are discarded** — including when they run out of retries, which
used to dead-letter them alongside the call logs and put GPS fixes into the count
an operator is meant to act on. A call log or a recording that nothing can deliver is
marked dead rather than deleted: it stops being retried, the status screen counts it, and
it is purged — with its audio — only after 90 days. An empty queue therefore means
delivered, which is not something it used to mean.

**And dead is not permanent.** The retry budget is twenty-five drains, not
twenty-five minutes, and WorkManager backs a failing worker off to a five-hour
ceiling — so it expires over days, which is exactly how long an archived device
or an identifier mistyped at enrollment goes unnoticed. A row stopped by the
budget is put back the moment a drain the server answers proves the fault is
gone; if the whole queue is dead and the phone would otherwise never send
anything again, one row is released per budget as a probe. A row the server
*refused* stays dead, because no operator action makes it storable.

### What each answer from the server does

| Answer | Meaning | The queue |
|--------|---------|-----------|
| 2xx | stored — `duplicates` counts what it already held, `skipped` what it read but could not use | removed |
| 409 | the transport layer recognised this exact request inside the dedup window | removed, counted as a duplicate |
| 422 **reporting duplicates** | an older server's spelling of "I already hold this batch" | removed, counted as a duplicate |
| 400, 422 | unreadable, or read and nothing storable | positions deleted; calls and recordings marked dead |
| 413 | over the device's `max_payload_size` | a multi-row batch **halves and retries** in the same drain; one row is **kept and charged** |
| 401, 403, 404, 429, 5xx, anything else | transient, or fixable from Odoo | **kept and retried** |
| 2xx without `"status": "success"` | *nothing on this network is Odoo* | **kept and retried** |
| any other row-removing status whose body carries neither `status` nor `error` | same | **kept and retried** |

The last two rows are the ones the status code alone cannot answer. Every response this
endpoint sends — success and error alike — comes from `base_controller._json_response`, so
a success always carries `"status": "success"` and an error always carries `{"error",
"message"}` and never `status`. That is the discriminator, and it has to be, because
"parses as a JSON object" is not enough: a captive portal answering **200** with its
sign-in page was caught by the weaker test, but a gateway or WAF answering **200** with its
own JSON error envelope sailed through it as `accepted: 0, duplicates: 0, skipped: 0` — a
delivery — and the drain deleted the batch: call logs, which nothing else in this design
ever deletes. It also set `delivered`, which puts dead-lettered rows back and resets their
retry budget, so one middlebox both destroyed live records and falsified the state of the
dead ones. The same hole runs the other way on **400** and **422**: a proxy refusing the
request looks like Odoo refusing the payload, and a refusal dead-letters a call as
`refused` — the one state no operator action brings back. Retrying is safe precisely
because the server deduplicates: whatever a misread network made the phone re-send comes
back 409 once the real endpoint is reachable.

The 422 row above is the one that had to be split. The endpoint chose its status
from `accepted > 0`, and it drops rows it already holds *before* taking that
count — so "nothing here was storable" and "the server already had every row"
were the same answer. Past `duplicate_window_seconds` the 409 no longer fires,
and a phone re-sending a batch Odoo had committed was told 422, read it as a
refusal, and dead-lettered call records the server was holding. The endpoint
reports `duplicates` separately now and answers 200 for a batch it accounts for
in full; the client reads that counter out of a 422 as well, because a handset
in the field talks to whatever Odoo it points at.

404 and 413 are the two that matter in practice and neither is about the payload: a device
**archived** in Odoo (or an identifier mistyped at enrollment) answers 404, and a
`remote.device` created **without the Mobile Phone category** carries a 1 MB payload cap
instead of 50 MB and answers 413 to every recording. Both are corrected in Odoo with the
handset untouched, and the phone picks its queue back up when they are — including the
rows that had already exhausted the retry budget while nobody had noticed.

The 413 also carries the number: `"Request exceeds maximum size of NKB"`. The client reads
it, so a batch that is too large halves and retries within the same drain rather than
re-sending something identical, and the figure an operator needs ends up on the status
screen instead of only in a log. It is remembered too: a recording whose wire size
exceeds the stored cap is not sent at all. That is not a nicety — a 2 MB recording
against a 1 MB cap used to push 74 MB of metered data before it settled, and a 30 MB one
would push over a gigabyte. Any revival drops the stored cap, so raising it in Odoo is
still discovered on the next pass.

**Nothing is retried for ever.** A row put back by `reviveExhausted` gets one probe
attempt, not a fresh budget of 25, and `revivals` is counted: after `MAX_REVIVALS` it is
marked `refused` and stays on the undeliverable line. Without that bound a row the server
chokes on — an oversized recording, or a record whose ingest raises, which the endpoint
answers **500** and the client is right to treat as retryable — was revived on every drain
for ever, and was *never* visible, because it was killed at the start of a drain and put
back at the end of the same one. It also held the upload worker at `RETRY`, which walks
WorkManager's backoff out to five hours and delays every position and call log on the
handset.

## Build

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests
```

`local.properties` must point at an SDK (`sdk.dir=…`); it is git-ignored.

## Configuration

### Provisioning by MDM (how a fleet should do it)

The app declares Android app restrictions (`res/xml/app_restrictions.xml`), so any EMM
that supports managed configuration — Android Management API, Headwind, Intune — can
push enrollment per device:

| Key | Type | Meaning |
|-----|------|---------|
| `base_url` | string | Odoo base URL |
| `identifier` | string | the `remote.device` identifier for this phone — letters, digits, `-`, `_`, `.` or `~` only |
| `token` | string | that device's inbound Bearer token |
| `call_log_enabled` | bool | upload call metadata |
| `recordings_enabled` | bool | harvest OEM dialer recordings |
| `wifi_only_uploads` | bool | hold uploads for an unmetered network |
| `location_interval_seconds` | integer | seconds between position reports, clamped to 15 s … 24 h; it is also the *fastest* rate accepted, so the figure is the rate |
| `upload_window_seconds` | integer | how long a position may wait to travel with others; 0 sends each one immediately. Clamped to 0 … 1 h |

The app applies them at launch and whenever the EMM changes them, starts reporting
without anyone opening it, and disables the on-device form so a provisioned phone
cannot be re-pointed. An omitted or blank key leaves the current value alone, so a
partial push never unenrolls a working device — as does a `base_url` that cannot
build a request, or an `identifier` outside the character set above, since either
would otherwise replace a working enrollment with one that can never upload.

`location_interval_seconds` is read whether the EMM sends it as a number or as text —
several consoles serialise every managed value as a string, and those pushes used to be
accepted, reported as applied, and silently ignored. It is clamped rather than trusted:
one second of `PRIORITY_HIGH_ACCURACY` flattens a handset in an afternoon.

### Manual entry (a handful of phones, or testing)

Open the app and fill in base URL, identifier and token. Three switches control
behavior: call-log sync, recording upload (off by default), and Wi-Fi-only uploads.
The position interval and the upload window are on the form too, so a deployment
with no EMM — or someone testing, who wants to see an upload the moment a fix is
taken — can set them without one; the interval was previously reachable only
through a managed configuration. Both are clamped to the same bounds an MDM push
is, and the form shows the stored value back rather than what was typed. Leaving
either blank keeps whatever is stored. "Sync now" collects from every producer
and then uploads.

## HTTP vs HTTPS

Release builds talk **only to HTTPS**. That is Android's default for `targetSdk` 28+ and it is
kept deliberately: location, call metadata and call audio should not cross a network in the
clear. A release build pointed at an `http://` URL fails every upload with
`CLEARTEXT communication to <host> not permitted by network security policy`, which the app
records in the outbox row's `lastError` and logs under the `OdooClient` tag.

Debug builds relax this (`app/src/debug/AndroidManifest.xml`) so they can reach a development
server — including `http://10.0.2.2:8069`, the host loopback as seen from an emulator. If an
on-premise instance genuinely must serve plain HTTP in production, add a
`network_security_config.xml` pinning that one host rather than re-enabling cleartext globally.

## Diagnosing a phone that stopped reporting

The status screen is the first stop, because the usual causes are local to the handset
and invisible from Odoo. It shows enrollment, whether an MDM configured the device, the
depth of each queue, how many records could not be delivered at all, when the last upload
succeeded, when it was last attempted if that is more recent (a device failing since
Tuesday and one whose worker stopped running on Tuesday need different fixes), and the
three conditions that stop reporting outright:

- **Location permission missing** — nothing is collected at all.
- **Call sync on without `READ_CALL_LOG`** — the worker reads nothing and reports
  success, so every other line on the screen describes a healthy phone. It is
  only shown when call sync is actually switched on.
- **Recordings on without all-files access** — the same shape, and it was the harder one
  to see: `MANAGE_EXTERNAL_STORAGE` is declared in the manifest and granted by MDM policy,
  but nothing checked it. Without it `RecordingScanner` reads an empty directory listing,
  the harvest queues zero rows and returns success, and the screen reported no blocker at
  all. `READ_MEDIA_AUDIO`, which the app *does* request at runtime, does not cover
  `getExternalStorageDirectory()`. Shown only when recordings are switched on; "Grant
  permissions" then also opens the all-files-access screen.
- **Location set to "while using the app"** — collection works until the phone reboots
  or the app updates, then the service cannot restart from the background and the phone
  goes quiet with no error anywhere.
- **Battery optimization is on** — the OS eventually stops the service while the phone
  idles. "Grant permissions" asks for the permissions and then opens the system prompt
  for the exemption; on a managed fleet the EMM should set it by policy instead.

A recording whose filename carries only a timestamp uploads with no number rather than
with the timestamp as one: a digit run is treated as a stamp when it names the moment the
file was written (a `YYYYMMDD[HH[MM[SS]]]` local datetime or an epoch value within six
hours of `lastModified`). Nothing shorter works — `2025102814` is both a plausible
`YYYYMMDDHH` and the real number 202-510-2814, and only the file's own mtime separates
them.

`adb logcat -s OdooClient LocationService CompanionApp` shows upload failures, permission
refusals and applied managed configuration.

## Known constraints

- **Recording capture is not something this app can do.** Android removed the
  voice-call audio source in 10 and the accessibility workaround in 11; a Device Owner
  privilege does not restore it. `RecordingHarvestWorker` only picks up files an OEM
  dialer already wrote to disk, so it works exactly on handsets whose dialer records
  and only where the app can read that folder. `CANDIDATE_DIRECTORIES` and the
  filename parser are the per-OEM knobs, and both need checking against a real
  handset before trusting them.
- **Uploads are at-least-once; the server deduplicates at two layers.** A device in
  the **Mobile Phone category** is created with `duplicate_detection_enabled` and a
  900-second window, so a retry of a request the server had already committed
  answers 409 inside it and the app removes the row without counting a loss. Past
  that window the request looks new and the *model* dedup catches it instead —
  `remote.call.log` and `remote.data.log.gps` drop rows already stored, on
  (device, timestamp, number/coordinates) — which is reported as `duplicates` and
  is equally a delivery. A device created without that category gets neither layer,
  nor the 50 MB payload cap — set the category.
- **The Bearer token is stored unencrypted**, in the app's private DataStore file,
  with `allowBackup="false"`. That is deliberate rather than overlooked: the threat
  it would guard against is extraction from an unlocked or rooted handset, and on
  such a device the queued call recordings — which sit in the OEM dialer's folder on
  external storage, where this app cannot move them — are already readable. Encrypting
  the token alone would protect the least sensitive thing on the phone. What actually
  bounds this is Device Owner policy: disk encryption, a screen lock, and a token that
  can be rotated in Odoo the moment a handset goes missing.
- **Restricted permissions need MDM.** `READ_CALL_LOG` and broad storage access are
  granted silently by a Device Owner policy; without one, call-log sync is a no-op and
  recording harvest sees nothing.
- **Background location** must be granted "Allow all the time", and battery
  optimization must be disabled for the app, or the OS eventually stops the service.
