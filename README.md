# OrderFlow

OrderFlow is an offline Android application for employee barcode login, order photographs and notes, shared-folder order history, and a private administrator device activity log.

- Package: `com.thexcoder.orderflow`
- Android 10 or later (`minSdk 29`); Google Pixel 4a on Android 13 is supported by the SDK and APIs used.
- Kotlin, Jetpack Compose / Material 3, MVVM, Room, DataStore, CameraX, bundled ML Kit barcode scanning, and Android Storage Access Framework (SAF).
- No cloud account, OneDrive API, Internet permission, broad storage permission, or hardware device identifier is required. Barcode recognition works offline after installation.

## Build and install

Open the `android` directory in Android Studio, or use JDK 17 and the Android SDK with API 36 installed:

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle and Maven dependencies require network access on the first build; the installed application does not. Configure the SDK through Android Studio or an untracked `android/local.properties` containing `sdk.dir=/path/to/Android/sdk`.

For a synchronized workspace or CI, optionally pass `-Porderflow.buildRoot=/tmp/orderflow-build` to keep generated outputs outside the checkout. With that option, outputs and test reports are under `/tmp/orderflow-build/app/`. The supplied validated APK copies are in `android/dist/`.

A standard debug build produces:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

On the Pixel 4a, enable Developer options and USB debugging, connect the phone, accept its USB debugging authorization, then run:

```sh
adb devices
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.thexcoder.orderflow/.MainActivity
```

Alternatively, copy the APK to the phone and allow installation from the application opening it. Grant Camera permission when scanning or taking the first photograph. Updates installed with the same package and signing key retain private records. The debug key is for development; arrange a stable release signing key before distributing production updates.

## First use

1. Open **Admin** and create a 6–12 digit administrator PIN, entering it twice. There is no default PIN.
2. In the admin dashboard, choose **Choose storage directory**. Select a local subfolder, such as `Documents/OrderFlow`, that your external synchronization application can synchronize. The selected provider must support creating, reading, and renaming files. Android may prohibit selecting entire storage roots or protected directories.
3. Open **Employees** and register each employee's username and unique barcode. Employees may be edited or deactivated. Employee registration and PIN credentials are local to this installation; register employees separately on each phone.
4. Lock the admin panel and choose **Scan employee barcode**. A registered, active barcode signs the employee in and displays **Welcome, {username}**.
5. Create an order using letters, numbers, hyphens, or underscores, or search and open an existing order. Add optional initial notes, further notes, and camera photographs.
6. **Close order session** returns to the orders list while keeping the employee logged in. A cancelled session also preserves everything already saved. Orders can be reopened to start another working session. **Log out** ends the employee login explicitly.

The PIN is stored as a salted PBKDF2 verifier, with a persisted delay after repeated incorrect attempts. Administration must be unlocked with the PIN; employee login and Developer Mode do not grant admin access. Leaving administration or backgrounding the application locks admin access, with an exception of at most two minutes for an administrator-initiated Android storage picker. A longer picker interaction requires fresh PIN authentication. No deep link grants administrator access. Keep the PIN safely: there is no recovery PIN or remote reset. Clearing app data or uninstalling resets the PIN and also deletes private employees, sessions, device history, and test data.

## Daily employee sessions

An employee login remains valid for its current local calendar day, including app restarts and multiple orders. Closing an order does not end that login.

The next local midnight is calculated when login succeeds and persisted with the login's date and timezone. Session validity is checked on launch, resume, and before protected actions, including creating or opening an order and adding photos or notes. Foreground checks update the UI; correctness does not depend on Android executing a midnight background job.

When expiry is detected after inactivity, the device log records the effective midnight as the event time and retains the later detection timestamp. A deterministic event UUID prevents duplicate expiration records for the same employee session. A changed timezone can shorten the deadline and cannot extend a previously established deadline. Calendar rollback or a detected backward wall-clock change requires a fresh login. Device time should still be managed correctly; this offline application cannot establish a trusted external time source.

If an order is open at expiration, its saved photographs and notes are retained. Its working session is marked interrupted/cancelled, rather than normally completed. Further changes require another employee barcode login. An interruption destined for the shared folder is retained for retry if that folder is temporarily inaccessible.

## Order storage and synchronization

The administrator's SAF folder contains production orders only:

```text
<chosen-orders-folder>/
  ord123/
    ord123.md
    events/
      <installation-device-id>/
        <event-uuid>.json
    photos/
      <installation-device-id>/
        ord123-1.jpg
        ord123-2.jpg
    reports/
      <installation-device-id>.md
```

Photos use numbered JPEG filenames inside a device namespace. This preserves readable numbering while preventing different phones from claiming the same shared path. Events have unique UUID filenames, are immutable after completion, and include the originating employee, employee ID, installation ID, model, UTC timestamp, recorded timezone, order, and working-session ID. Existing final event files and photographs are never deliberately overwritten; a conflicting payload is reported as an error.

Room is the local index for shared order events. Foreground discovery runs about once a minute and on resume. **Refresh** discovers orders and events delivered into the folder by other software; creating or opening an order also imports available events. Order history merges events from all discovered devices. A malformed, incomplete, or mismatched event is skipped with an admin diagnostic, while valid history continues importing. Pending temporary files are not imported as completed events.

`ord123.md` is a readable, derived chronological report. Each device also writes a report in `reports/`. Immutable JSON events are authoritative: external synchronization can deliver files out of order or temporarily conflict on the convenient common Markdown report. Refresh regenerates reports from the discovered event history. Do not manually alter completed JSON event files.

OrderFlow has no OneDrive integration and does not start a sync. Configure external software to synchronize the selected local production folder. Normally only one phone should work on an order at a time. There is no distributed lock or server arbitration; simultaneous offline creation of the same order number will merge histories after synchronization. Device namespaces protect event and photograph files, but an external sync program can still remove, rename, corrupt, or create conflict copies of shared files. Check its configuration and retention settings.

## Reliable operations

OrderFlow persists an operation intent before writing order files. A photograph is first retained privately for recovery. After the photograph, if applicable, and immutable JSON event are successfully finalized and verified, Room records the order event and this phone's corresponding device event in one transaction. The event UUID correlates both histories and deduplicates retries.

If a write or database commit fails after an action has started, the pending intent is retained. Restore folder access and use **Refresh**; do not repeat an action whose message says it was retained for retry. Recovery reuses the original UUID and verifies existing bytes, so it does not create another photo or repeat an already completed event. Private logout/expiration is committed immediately; the interrupted order's shared projection can be retried later. Authorization is checked again immediately before finalizing a new order event or photo. If midnight passes during a slow write before anything has been finalized, the unfinished action is abandoned without a success record and another barcode scan is required. A photograph already finalized before expiry is preserved, and recovery finishes its corresponding audit instead of discarding it. Markdown generation failures do not undo successful work because reports can be regenerated.

The diagnostics screen reports pending operations and recent storage/import errors. Diagnostic messages are bounded; persisted device activity history is not truncated by this policy.

## Admin Device Activity Log

Open **Admin → Device Activity Log** after entering the administrator PIN. The screen shows the installation model and ID, chronological event cards, employee, order, details, and recorded local date/time. It defaults to newest first and supports oldest first, date range (`YYYY-MM-DD`), employee, order number, action type, and text search. The timeline loads pages in a lazy list.

Recorded actions include:

- Employee login, manual logout, and midnight session expiration.
- Order creation, opening, normal session closure, and cancellation/interruption.
- Successful photo and note additions.
- Administrator login/logout, employee registration/updates/deactivation, storage changes, and Developer Mode changes.

An unsupported operation, such as deleting photographs or correcting an existing note in place, is not exposed. Add a further note to preserve a correction in the history.

This log is different from an order log. An order log is shared and contains that order's merged activity across devices. A device log records only actions performed on this installation, including authentication and administration across all its orders. Importing another phone's order events does not attribute those actions to this device.

The authoritative private records are in the `device_activity_events` Room table in the app-private `orderflow.db`. The readable production report is generated at:

```text
filesDir/admin/activity/device-activity.md
```

Developer activity uses a separate private report. These paths are inside Android's application sandbox, outside the selected orders folder. No public URI, normal order export, or sync path exposes the private original. The report is rebuilt from Room on startup, refresh, and export, so a missing or damaged Markdown file can be regenerated without deleting event history. Writes use Android `AtomicFile` replacement.

### Explicit export

An authenticated administrator can press **Export Device Activity Log**, then choose a destination in Android's **Create document** picker. The suggested production filename is `OrderFlow-DeviceActivity-DEVICEID-YYYY-MM-DD.md`; test exports are marked as test activity. Export generates the full selected production or developer history, rather than only the currently filtered page.

The exported copy is outside the app's protected storage and may be read by other applications or uploaded by the chosen provider or synchronization software. The app explains this after a successful export. Export is never automatic, and the private source file is not shared through a public file URI.

## Developer Mode

An administrator can enable **Developer Mode**. This creates or activates:

```text
Username: Test User
Barcode: TEST-USER-001
```

The login screen then offers **Simulate Test Barcode**, using the same employee-validation and daily-session path as camera scanning. Test User can create and reopen orders, add notes and photos, close sessions, and log out. **Simulate midnight expiration** exercises expiry using an injected clock without changing Android's actual date or time.

Test orders live under `filesDir/developer/orders`; test device activity is marked explicitly and stored separately at `filesDir/developer/activity/device-activity.md`. Developer operations never use the production SAF tree, including when it has been configured. The Room index separates production and test keys and events. An administrator can select **View developer test activity** in the activity filters. Test login cannot bypass the PIN gate for either the production or test administrator timeline. Disabling Developer Mode prevents further Test User login and invalidates an existing test session; historical test records remain available to an administrator.

## Tests

Run local unit tests with:

```sh
cd android
./gradlew :app:testDebugUnitTest
```

Run instrumentation tests on a connected emulator or device with:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

Core unit tests exercise barcode/event validation, salted PIN hashing, deterministic Markdown regeneration and ordering, and daily-session rules with fake clocks, including DST, restart, duplicate expiration inputs, timezone changes, and clock rollback. Instrumentation tests exercise Room persistence/deduplication, private developer files, production SAF through a test DocumentsProvider, imported shared events, collision handling, and UI access guards. An offline barcode instrumentation test decodes a real Test User QR bitmap with the bundled ML Kit model and asserts that the installed application has no Internet permission. Repository workflow tests cover the integrated login, administration, order, expiration, and recovery paths. Consult generated reports under `android/app/build/reports/`; the existence of test source files alone is not proof of an executed test run.

Physical-camera quality, real printed barcodes, third-party storage providers, external OneDrive synchronization, and Android background timing should be verified on deployment hardware. SDK compatibility and an emulator run do not replace a physical Pixel 4a check.

## Executed validation (2026-11-19)

| Check | Observed result |
| --- | --- |
| Debug APK | Built successfully |
| Release APK | Built successfully; unsigned, requires production signing |
| JVM unit tests | 44 passed, 0 failed, 0 skipped |
| Android instrumentation | 35 passed, 0 failed, 0 skipped on Android 13 / API 33 ARM64, Pixel 4a display profile |
| Android lint | 0 errors, 18 warnings (dependency/target update notices and a KTX style suggestion) |
| Launcher smoke test | PIN setup, actual local-folder picker, Test User login, order/note creation, runtime camera permission, actual CameraX JPEG capture, order closure with login retained, process restart restoration, PIN gate after restart, actual Markdown export and privacy message verified |

Commands executed successfully (JDK 17):

```sh
cd android
./gradlew -Porderflow.buildRoot=/tmp/orderflow-build :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew -Porderflow.buildRoot=/tmp/orderflow-build :app:connectedDebugAndroidTest
```

Latest APK copies: `android/dist/OrderFlow-debug.apk` (installable) and `android/dist/OrderFlow-release-unsigned.apk` (requires signing). With this isolated build root, test reports are in `/tmp/orderflow-build/app/reports/tests/testDebugUnitTest/` and `/tmp/orderflow-build/app/reports/androidTests/connected/debug/`.

The emulator is an AOSP Android 13 system image with Pixel 4a resolution/density, not a physical Pixel 4a. Physical camera quality, printed-barcode scanning and actual external synchronization still need deployment-device testing. The bundled ML Kit model was separately tested against a real QR fixture without Internet permission.

## Storage and privacy limits

- App backup is disabled and cloud/device-transfer extraction rules exclude private data, including database, settings, reports, and retained photographs. Android/OEM behavior can vary; verify managed-device policy. No recovery from clearing data, uninstalling, device loss, or private-database damage is implemented. Export important device history explicitly.
- Records are persistent across ordinary restarts and compatible app updates, but are not an encrypted vault or a cryptographically signed audit trail. Android's sandbox and normal device encryption protect private files; a rooted device, compromised OS, or someone with unrestricted developer access may bypass that boundary.
- Employee barcodes are credentials suitable for a controlled work environment, not biometric proof of identity. Anyone holding a registered barcode can use it. The initial device setup must be performed by a trusted administrator.
- The shared production folder is readable and writable by applications or people authorized to that folder. Its events contain employee names, IDs, notes, device identifiers, and activity times. Shared imported events are validated for structure and safe paths, but are trusted inputs rather than digitally authenticated statements.
- The stable device ID is a generated installation UUID, not IMEI. It survives updates and changes after private app data is reset. Employees and credentials are not synchronized by this application.
- Reports and export currently materialize the selected complete history in memory on a background thread. The screen pages records for routine browsing, but exceptionally large histories may require a future streaming exporter. Shared discovery scans the selected order tree and is designed for modest operational folders, not an unbounded remote filesystem.

See [ARCHITECTURE.md](ARCHITECTURE.md) for implementation details, data ownership, and extension points.
