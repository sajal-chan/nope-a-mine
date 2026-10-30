# Morning Blocker

Android-only React Native / Kotlin proof of concept for interrupting Instagram. The native accessibility service enforces independently of the React Native screen.

**Prototype behavior:** Instagram is blocked whenever the accessibility service is enabled. There is no schedule, emergency access, app selection, history, or backend yet. Disable the service in Android accessibility settings to stop the prototype.

Pure Kotlin schedule rules, DataStore persistence, active-session policy protection, and persisted clock recovery are implemented independently. They do not yet change the installed prototype's behavior. Service/broadcast integration and physical-device testing remain pending.

## Development

- Node 22.13 or newer on the Node 22 line, npm, JDK 17.
- Android SDK Platform 36, Build Tools 36.0.0, NDK 27.1.12297006, and platform-tools. Set `ANDROID_HOME` and `JAVA_HOME` for your machine.
- Windows instructions below use PowerShell. On macOS/Linux use `./gradlew` instead of `./gradlew.bat`.
- The initial machine has system Node 22.12.0; update/select a compatible runtime before using the commands below. `.node-version` pins 22.23.4. No system-wide runtime was changed during setup.

```powershell
npm ci
npm test -- --runInBand
npm run typecheck
npm run lint
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

For interactive development, run `npm start` in one terminal and `npm run android` in another with your device connected. Debug builds require Metro for the setup screen; native blocking does not depend on Metro after the service connects.

For a standalone, offline prototype build with bundled JavaScript:

```powershell
cd android
.\gradlew.bat :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/release/app-release.apk
```

The release build currently uses the template's public debug key **for local testing only**. Distribution signing is not configured.

## Structure

- `src/screens/SetupScreen.tsx`: disclosure, explicit agreement, Android settings, native status.
- `src/native/BlockingModule.ts`: typed native bridge.
- `android/app/src/main/java/com/morningblocker/blocking/`: native service, presentation, policy gate, and bridge.
- `android/app/src/main/java/com/morningblocker/scheduling/`: immutable schedule configuration and pure local-time rules, with the current time supplied by the caller.
- `android/app/src/main/java/com/morningblocker/storage/`: immutable policy and atomic Preferences DataStore repository. `policyRepository(context)` shares one application-level store; suspend `save`/`load` and the `policies` flow do not require React Native.
- `android/app/src/test/`: Kotlin policy, presentation, and service tests.
- `__tests__/`: setup-screen behavior tests.
- [Physical-device testing and limitations](docs/PHASE_1_TESTING.md).
- [Product specification](docs/PRODUCT_SPEC.md) and [development instructions](AGENTS.md).
- [TDD evidence](docs/TDD_LOG.md).
- [Clock recovery behavior and integration limits](docs/CLOCK_RECOVERY.md).

## Design references

Configuration persistence uses [Preferences DataStore](https://developer.android.com/topic/libraries/architecture/datastore), pinned to 1.2.1. Schema version 1 stores ISO local start/end times, weekday names, and selected package identifiers in one transaction. Empty storage means unconfigured; empty selections are retained as empty. Package identifiers are validated without checking installed applications. Read/write failures, invalid saved fields, and future schema versions are surfaced instead of silently resetting data. A normal save cannot overwrite damaged or newer-schema data; explicit repair/migration is future work. The repository is not yet exposed through the React Native bridge.

`save(policy, now)` and `activeSessionAt(now)` require a native `ClockReading` (instant, elapsed realtime, boot count, and current zone), obtained through `AndroidSessionClock.read()`. Before editing, the repository recovers/captures the original session, including elapsed windows, and atomically checkpoints its clock with its frozen policy. Subsequent changes apply from a later eligible date. `load()` and `policies` return the latest configured policy, which may be deferred and must not directly drive enforcement. The last completed snapshot prevents same-date revival. Existing schema-1 snapshots without clock metadata are anchored once using the supplied current zone; unavailable historical clock information cannot be reconstructed. Override state is not implemented.

Provisional setup behavior: a first policy saved within its window becomes active immediately on evaluation. Edits before today's original window starts can apply that morning; edits after it starts wait until a later eligible date. Pending edits, clock checkpoints, and snapshots survive reopening DataStore. Recovery runs on native repository calls; no boot/time-change receiver or live enforcement integration has been added. Physical reboot, time-change, sleep, and service reliability checks remain pending.

The schedule rules use `java.time` with [Android core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) to retain minimum API 24 compatibility. Start is inclusive, end is exclusive, and empty weekdays disable the schedule. “Next session” is the next strictly future start, excluding an active session. The repository handles freezing and clock recovery; zone/DST rules and offline recovery limits are documented in [CLOCK_RECOVERY.md](docs/CLOCK_RECOVERY.md).

The prototype uses [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) and [TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY). It does not request usage access, broad package visibility, or application-overlay permission. Accessibility-window retrieval is used only to identify the foreground package; no node text is traversed, stored, or transmitted.

This is not an accessibility tool for people with disabilities. Google Play distribution will require a separate review of [accessibility policy, disclosure, consent, and declaration requirements](https://support.google.com/googleplay/android-developer/answer/10964491). No Play acceptance is claimed.
