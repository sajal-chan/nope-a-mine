# Morning Blocker

Android-only React Native / Kotlin proof of concept for interrupting Instagram. The native accessibility service enforces independently of the React Native screen.

**Prototype behavior:** Instagram is blocked whenever the accessibility service is enabled. There is no schedule, emergency access, app selection, history, or backend yet. Disable the service in Android accessibility settings to stop the prototype.

Pure Kotlin local-hour schedule rules, DataStore persistence, and active-session policy protection are implemented independently. They do not yet change the installed prototype's behavior. Service/broadcast integration and physical-device testing remain pending.

The independent emergency-access state machine covers waiting, explicit confirmation, cancellation, capped access, and simulated restart/reboot transitions. Its persistence, native clock adapter, UI, and enforcement integration are not implemented yet.

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
- `android/app/src/main/java/com/morningblocker/emergency/`: pure emergency-access rules consuming monotonic time, boot identity, stable session identity, and remaining morning-window time.
- `android/app/src/main/java/com/morningblocker/storage/`: immutable policy and atomic Preferences DataStore repository. `policyRepository(context)` shares one application-level store; suspend `save`/`load` and the `policies` flow do not require React Native.
- `android/app/src/test/`: Kotlin policy, presentation, and service tests.
- `__tests__/`: setup-screen behavior tests.
- [Physical-device testing and limitations](docs/PHASE_1_TESTING.md).
- [Product specification](docs/PRODUCT_SPEC.md) and [development instructions](AGENTS.md).
- [TDD evidence](docs/TDD_LOG.md).
- [Clock recovery behavior and integration limits](docs/CLOCK_RECOVERY.md).
- [Emergency-access state machine and persistence contract](docs/EMERGENCY_ACCESS.md).

## Design references

Configuration persistence uses [Preferences DataStore](https://developer.android.com/topic/libraries/architecture/datastore), pinned to 1.2.1. Schema version 1 stores ISO local start/end times, weekday names, and selected package identifiers in one transaction. Empty storage means unconfigured; empty selections are retained as empty. Package identifiers are validated without checking installed applications. Read/write failures, invalid saved fields, and future schema versions are surfaced instead of silently resetting data. A normal save cannot overwrite damaged or newer-schema data; explicit repair/migration is future work. The repository is not yet exposed through the React Native bridge.

`save(policy, now)` and `activeSessionAt(now)` take a native `ClockReading` containing the current instant and zone, obtained through `AndroidSessionClock.read()`. Before editing, the repository captures the original policy if its window has begun. Subsequent changes apply from a later eligible date. `load()` and `policies` return the latest configured policy, which may be deferred and must not directly drive enforcement. Re-entering the hours after a clock rollback uses the frozen policy, not pending edits. Legacy `clock_*` duration checkpoints are ignored and removed on the next native evaluation/save; schedule and session data are retained. Emergency-access rules exist independently, but their state is not yet persisted.

Confirmed behavior: first-time setup inside the window starts immediately. Morning activity follows the current local hours, including time-zone changes. Moving outside the window unblocks; returning inside blocks again. It does not preserve a remaining duration. Edits before the original window starts can apply that morning; edits afterward wait for a later eligible date. Pending edits and frozen policies survive reopening DataStore. No boot/time-change receiver or live enforcement integration has been added; physical testing remains pending.

The schedule rules use `java.time` with [Android core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) to retain minimum API 24 compatibility. Start is inclusive, end is exclusive, and empty weekdays disable the schedule. “Next session” is the next strictly future start, excluding an active session. Current local-clock/DST behavior is documented in [CLOCK_RECOVERY.md](docs/CLOCK_RECOVERY.md).

The prototype uses [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) and [TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY). It does not request usage access, broad package visibility, or application-overlay permission. Accessibility-window retrieval is used only to identify the foreground package; no node text is traversed, stored, or transmitted.

This is not an accessibility tool for people with disabilities. Google Play distribution will require a separate review of [accessibility policy, disclosure, consent, and declaration requirements](https://support.google.com/googleplay/android-developer/answer/10964491). No Play acceptance is claimed.
