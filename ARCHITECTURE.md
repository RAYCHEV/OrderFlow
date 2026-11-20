# OrderFlow architecture

## Layers and ownership

OrderFlow is a single Android application module in `android/app`, with package `com.thexcoder.orderflow`. It targets Android 10+ with Compose Material 3. The screen, application state, domain policy, database, and file protocol are kept separate so storage and session rules can be tested without constructing UI screens.

| Area | Responsibility |
| --- | --- |
| `MainActivity`, `OrderFlowApplication` | Android entry points, repository lifetime, foreground/lifecycle integration |
| `ui/UiContract.kt` | Immutable `AppUiState`, screen identifiers, and `AppActions` command surface |
| `ui/OrderFlowApp.kt` | Compose screens and forms, lazy timelines, SAF document/folder picker launchers |
| `ui/CameraScreen.kt` | Camera permission, CameraX preview/capture, offline ML Kit barcode analyzer, camera cleanup |
| `ui/AppViewModel.kt` | MVVM presentation state, navigation/access checks, asynchronous command handling, paging and user messages |
| `data/AppRepository.kt` | Serialized application use cases, employee/session validation, durable operation recovery, shared import and local audit correlation |
| `data/OrderDatabase.kt` | Room entities, DAO, and versioned local schema |
| `data/SettingsStore.kt` | DataStore installation UUID, salted PIN verifier, failed-PIN delay, selected SAF tree, developer flag |
| `data/OrderStorage.kt` | Production SAF and private developer file protocol, collision protection, verified immutable events/photos, derived order reports |
| `data/DeviceActivityRepository.kt` | Private Room device events, guarded queries/export, safe private Markdown generation, in-memory admin capability |
| `core/Models.kt` | Activity event, daily session, event type, filter and formatting contracts |
| `core/SessionPolicy.kt` | Pure `Clock`-driven day validity/deadline calculation |
| `core/PinHasher.kt` | Salted PBKDF2 PIN verifier creation and constant-time verification |
| `core/EventCodec.kt` | Versioned JSON encoding/validation and safe order-number validation |
| `core/Markdown.kt` | Deterministic device/order Markdown projections from persisted events |

The UI invokes `AppActions`; it does not insert audit records or write order files. `AppRepository` is the central mutation boundary. Its coroutine mutex serializes actions and recovery within the installation. Room and file work use suspending APIs and IO dispatching; PIN derivation runs off the main thread. CameraX owns capture/analysis executors, and the analyzer closes each image after ML Kit completes.

## Local database

Database `orderflow.db`, Room schema version **1**, is new. There is no prior application schema to migrate. Schema JSON is exported under `android/app/schemas`; future changes must increment the version and provide explicit non-destructive migrations. No destructive migration fallback is configured.

| Table | Purpose | Important constraints/indexes |
| --- | --- | --- |
| `employees` | Locally registered employee identity and barcode | UUID primary key; unique barcode |
| `employee_sessions` | Restorable daily employee logins, deadline, termination reason, active order working session | Session UUID; employee and termination indexes |
| `orders` | Local production/developer order index | Key includes `prod:` or `dev:`; developer/updated-time index |
| `order_events` | Imported and locally completed shared order event cache | Event UUID primary key; order/timestamp index |
| `device_activity_events` | Authoritative history of this installation's successful activities | Event UUID primary key; production/test, time/date, employee, action, order indexes |
| `pending_operations` | Durable serialized operation intent and retained-photo reference | Original operation UUID primary key |

The database has several kinds of data ownership. `order_events` and `orders` are reconstructable indexes of external shared history. Employee sessions, local employee registrations, pending intents, and device activity are private authoritative data. They must not be treated as a disposable order cache. Imported order events do not become device audit events.

`ActivityEvent` carries event UUID, type, UTC milliseconds, recorded timezone, employee username/ID, installation device UUID/model, optional order and working-session identifiers, details, optional photo path, test flag, and optional actual detection time. Local date is derived from the recorded timezone for report grouping and date filtering. UUID primary keys deduplicate successful retries and expiration detection.

## Authentication boundaries

Employee barcodes are looked up against active local employees. An invalid, unknown, or inactive barcode cannot create a successful login event. `TEST-USER-001` is reserved for the administrator-created Test User and is accepted only while Developer Mode is enabled. The simulation uses the same validation path, with an additional developer-mode check.

Admin authentication is independent of employee authentication. DataStore retains only a salted PIN verifier and failed-attempt state. The successful authentication opens an in-memory `AdminAccess` capability, which is never restored from a saved screen, Intent, or process restart. Repository operations require this capability before employee management, storage/developer settings, device-history queries, or exports. The Compose screen also renders a PIN gate if given an unauthenticated admin route. The manifest exports only the launcher activity and declares no administrator deep links or file provider for private history.

Admin state is locked when leaving administration/backgrounding; administrator-initiated SAF picker flows have a handoff of at most two minutes, measured with monotonic elapsed time, so Android can return a chosen document. An expired handoff revokes administration and requires the PIN again. Export is checked at the repository boundary as well as in navigation and the button callback. The selected export is an explicit external copy, not access to the private source path.

Initial PIN setup is necessarily a trusted first-use operation: an installation with no PIN has no prior administrator credential to validate. There is no reset bypass. An administrator identity is represented in audit entries as `Administrator`; employee-management events include the affected local employee ID and details.

## Daily employee session policy

A successful login persists `DailySession` with a unique session UUID, employee identity, login UTC instant, login local date and timezone, next local midnight, current deadline timezone, last observed UTC instant, and test flag. Room also retains the active order key and order-session UUID so a normal process restart can restore the working context.

`SessionPolicy` accepts `java.time.Clock`. Production uses a system clock with the current zone; tests use a mutable/fixed clock. Policy evaluation returns a valid/expired decision, effective deadline/expiration UTC instant, and the timezone used for that boundary.

1. The original next midnight is a hard maximum deadline. DST is handled by `LocalDate.plusDays(1).atStartOfDay(zone)`, rather than assuming a day is 24 hours.
2. A different current timezone can produce an earlier midnight. Each successful validation persists the minimum deadline; changing the timezone back cannot extend it.
3. A current local date later than the login date expires the session. A local date rollback or observed wall-clock rollback also requires a fresh scan rather than prolonging access.
4. If the app was inactive beyond midnight, expiry is detected on the next validation. Audit event time is the effective midnight, with `detectedAtUtc` preserving the actual later observation.

Validation occurs at launch/resume and at the central boundary for employee operations, including order creation/opening, notes, and photos. Foreground checking is an interface convenience; there is no dependency on timely background execution.

Ending an expired session is a Room transaction: close the daily login, insert the deterministic expiration event, clear active working context, and mark any open order session interrupted. The interruption's private event is immediate; a pending operation retains its shared JSON projection if the selected folder is unavailable. Saved content is preserved and no normal completion event is invented. The expiration UUID is derived from the employee-session UUID; repeated checks cannot insert another expiration record for that login.

Test User midnight simulation constructs an injected clock at its deadline without changing Android's clock. Its explicitly marked future-effective audit entries are contained in test history.

## Shared-folder protocol

The administrator selects a local folder through SAF. A persisted `content://` tree permission is the only production filesystem capability. No broad storage permission, direct external path, cloud SDK, or hardware ID is used. A probe verifies that the selected provider supports the create/read/rename operations required by the protocol.

```text
<SAF root>/<order-number>/
  events/<installation-UUID>/<event-UUID>.json
  photos/<installation-UUID>/<order-number>-<sequence>.jpg
  reports/<installation-UUID>.md
  <order-number>.md
```

Order numbers are constrained to 1–64 ASCII letters/digits, `_`, and `-`. Device path segments and event UUIDs are validated. Each device owns its photo and event namespace. Numbering is monotonic over existing files in that namespace, and a final photo is reused on retry only if its digest matches the retained source. Another device can use `ord123-1.jpg` safely because its relative path is different.

The event codec writes `schemaVersion: 1` JSON. Import verifies types, timestamps, timezone, identities, safe paths, UUID filename, device directory, order directory, and developer/production partition. Unsupported schema, malformed file, mismatched metadata, or conflicting UUID payload is surfaced as a diagnostic and skipped. Unknown temporary `.pending` files do not become successful events. The protocol validates structure and integrity of local retries; it does not provide digital signatures or authenticate an external event's claimed author.

External sync is intentionally separate. Refresh/import scans the selected tree and merges unique UUID events into Room. A device can discover an order even if it did not create it. Events retain source timestamps and employee/device metadata. Deterministic rendering orders them by UTC timestamp and event UUID, grouping display by each event's recorded local date. Offline clock differences may therefore affect apparent ordering between devices; no global sequencing service is assumed.

Order Markdown is a disposable projection. The common `<order>.md` is convenient for any Markdown viewer, while `reports/<device>.md` avoids all devices contending on the same report name. A sync conflict in a derived report does not delete immutable event history; import and regeneration reconstruct the view. The system assumes one phone normally works on an order at a time and does not implement distributed locking.

## Durable operation and recovery sequence

Shared-folder IO and Room cannot participate in a single atomic transaction. `PendingOperationEntity` bridges that boundary with a stable UUID and serialized original event; it also captures the selected tree URI and retained photo path so retry does not move an operation to a different folder.

For a normal order action:

1. Validate current employee/day and working order. Resolve prior pending work before accepting another mutation in the same partition.
2. For a photo, copy the captured private-cache JPEG into private retained storage. Reserve its name in the operation intent.
3. Persist the pending intent in Room before shared writes.
4. Write photo/event content to temporary names, finish and verify the bytes, recheck the originating employee session immediately before finalization, then rename to final names. Final files are never deliberately overwritten. An existing final file is accepted only when it matches the original operation.
5. In one Room transaction, index the completed shared event, record the matching private device event using the same UUID, update the active order context, and delete the pending intent.
6. Delete the retained photo only after the commit. Regenerate order and device Markdown from persisted events.

A process death after the shared write but before the Room transaction leaves the original pending intent. On recovery, the identical UUID/file is recognized, the transaction is retried, and no second action is performed. A Markdown failure after the transaction is diagnostic rather than an action failure. A failed write before completion creates no normal success event; it remains a recoverable pending action while its authorization is valid. If midnight or logout invalidates authorization before a final file exists, recovery abandons the unfinished intent and retained photo instead of completing an unauthorized action. If a final photo or event already exists from a successful pre-expiry operation, recovery verifies it and completes the audit even after the employee login has ended.

Login/logout and employee administration mutate local authoritative state and audit records in Room transactions. Settings live in DataStore, so storage/developer changes use a small durable intent and compare the persisted settings during recovery before writing success. Private expiry cannot wait for an external folder: the login is ended locally and its shared interruption event is queued.

SAF does not promise atomic replacement across all providers. The storage adapter verifies temporary output, retains the previous report during replacement, and restores it when possible on failure. Immutable events plus retained pending intents are the recovery basis; a Markdown file is never the only history source. A production provider must actually support reliable local create/read/rename behavior.

## Private device activity

Only local operations enter `device_activity_events`. Remote events populate order history without claiming that this installation performed them. Administration and authentication events never enter order folders.

```text
<app-private files>/admin/activity/device-activity.md
<app-private files>/developer/activity/device-activity.md
<app-private files>/developer/orders/...
<app-private files>/pending-photos/...
<app-private databases>/orderflow.db
<app-private files>/datastore/private_settings.preferences_pb
```

`DeviceActivityRepository` generates Markdown from the persisted Room rows, with safe replacement through `AtomicFile`. A corrupted or missing report is regenerated on startup, refresh, or explicit export. Records are not pruned. Both the timeline and export require `AdminAccess`; test history is a separate selection with the same PIN protection. The timeline fetches bounded SQL pages for a Compose `LazyColumn`; it supports text, employee, order, action, recorded local-date bounds, and direction.

The admin export uses SAF `CreateDocument` to write a complete regenerated production or test Markdown copy to the chosen `content://` destination. It never returns a public URI for the private original. UI explains that other applications and sync services may access the exported copy. Exported data is outside the sandbox and outside the app's retention guarantees.

Backup is disabled in the manifest, with explicit backup/extraction exclusions for cloud backup and device transfer where Android supports them. Credentials, local activity and developer data are not intentionally synchronized. The sandbox is not a substitute for OS security, managed-device policy, physical access control, or explicit administrator backups. Clearing data/uninstalling loses private history.

## Developer partition

Developer Mode is an admin setting. It creates/activates the reserved Test User with a stable local test employee ID; turning it off deactivates that account and invalidates its active login. Production and developer order keys are different even for the same visible order number.

`OrderStorage` routes events with `isDeveloperEvent` exclusively to `filesDir/developer/orders`; it does not resolve the selected production tree for them. Device rows carry the test flag, Markdown is separately generated, and the UI displays a test-session banner. The simulation action cannot grant access to private history or production credentials. Historical developer events are retained when the mode is disabled.

## Testing and extension points

Core JVM tests use fake clocks for date boundaries, restart restoration rules, DST, timezone shifts, rollback, codec validation, PIN derivation, and deterministic report output. Android instrumentation tests exercise Room and storage behavior with a test-only DocumentsProvider, and Compose tests exercise PIN-gated navigation, first-use PIN constraints, developer control visibility, and explicit export initiation. `OfflineBarcodeTest` feeds a real 512×512 Test User QR fixture into the bundled ML Kit scanner and asserts that the installed application has no Internet permission. Repository workflow tests can instantiate the repository with a supplied clock provider and database for end-to-end login/order/recovery scenarios. Actual results must be taken from executed Gradle reports, not inferred from test source.

Potential future work should preserve the current ownership boundaries:

- Add explicit Room migrations; do not discard the local audit table to rebuild an order cache.
- Stream large report exports and incrementally import events for very large deployments.
- Add photo preview, immutable note-correction/removal events, or pagination of an individual order timeline through repository operations.
- If cryptographic provenance, central employee provisioning, or distributed edit locks become necessary, design those as explicit features rather than treating external folder sync as an authentication system.
- Keep camera handling behind callbacks and keep time policy injectable. Do not put successful audit writes directly into UI click handlers.

## Created project files

The complete application source, test fixtures, schema, resources and build infrastructure created for this implementation:

- `android/app/build.gradle.kts`
- `android/app/schemas/com.thexcoder.orderflow.data.OrderDatabase/1.json`
- `android/app/src/androidTest/AndroidManifest.xml`
- `android/app/src/androidTest/assets/README.txt`
- `android/app/src/androidTest/assets/test-user-001-qr.png`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/OfflineBarcodeTest.kt`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/UiWorkflowTest.kt`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/data/AppRepositoryWorkflowTest.kt`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/data/StorageDatabaseTest.kt`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/data/TestDocumentsProvider.java`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/data/TestStorageGrantReceiver.java`
- `android/app/src/androidTest/java/com/thexcoder/orderflow/data/TestStorageGrants.java`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/thexcoder/orderflow/MainActivity.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/OrderFlowApplication.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/EventCodec.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/LocalSystemClock.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/Markdown.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/Models.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/PinHasher.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/core/SessionPolicy.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/data/AppRepository.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/data/DeviceActivityRepository.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/data/OrderDatabase.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/data/OrderStorage.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/data/SettingsStore.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/ui/AppViewModel.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/ui/CameraScreen.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/ui/OrderFlowApp.kt`
- `android/app/src/main/java/com/thexcoder/orderflow/ui/UiContract.kt`
- `android/app/src/main/res/drawable/ic_orderflow.xml`
- `android/app/src/main/res/values/styles.xml`
- `android/app/src/main/res/xml/backup_rules.xml`
- `android/app/src/main/res/xml/data_extraction_rules.xml`
- `android/app/src/test/java/com/thexcoder/orderflow/EventCodecMarkdownTest.kt`
- `android/app/src/test/java/com/thexcoder/orderflow/PinHasherTest.kt`
- `android/app/src/test/java/com/thexcoder/orderflow/SessionPolicyTest.kt`
- `android/build.gradle.kts`
- `android/gradle.properties`
- `android/gradle/wrapper/gradle-wrapper.jar`
- `android/gradle/wrapper/gradle-wrapper.properties`
- `android/gradlew`
- `android/gradlew.bat`
- `android/settings.gradle.kts`

Repository documentation: `README.md` and `ARCHITECTURE.md`. `.gitignore` was updated for Android outputs and the checked-in Gradle wrapper. Local SDK configuration and generated APK/build outputs are ignored.
