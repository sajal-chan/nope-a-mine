# Morning Blocker

Android-only React Native / Kotlin proof of concept for interrupting Instagram. The native accessibility service enforces independently of the React Native screen.

**Prototype behavior:** Instagram is blocked whenever the accessibility service is enabled. There is no schedule, emergency access, app selection, history, or backend yet. Disable the service in Android accessibility settings to stop the prototype.

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
- `android/app/src/test/`: Kotlin policy, presentation, and service tests.
- `__tests__/`: setup-screen behavior tests.
- [Physical-device testing and limitations](docs/PHASE_1_TESTING.md).
- [Product specification](docs/PRODUCT_SPEC.md) and [development instructions](AGENTS.md).
- [TDD evidence](docs/TDD_LOG.md).

## Design references

The prototype uses [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) and [TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY). It does not request usage access, broad package visibility, or application-overlay permission. Accessibility-window retrieval is used only to identify the foreground package; no node text is traversed, stored, or transmitted.

This is not an accessibility tool for people with disabilities. Google Play distribution will require a separate review of [accessibility policy, disclosure, consent, and declaration requirements](https://support.google.com/googleplay/android-developer/answer/10964491). No Play acceptance is claimed.
