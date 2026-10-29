# Morning Blocker

## Project and repository

This repository builds an Android-first, offline morning social-media blocker for personal digital wellbeing. It is not parental-control or enterprise-management software.

- Remote: https://github.com/sajal-chan/nope-a-mine
- Default branch: `dev`. Preserve it as the default; do not assume `main`.
- Stack: React Native 0.84.1, React 19.2.3, TypeScript, Kotlin, Android SDK 36, JDK 17. Android-only scaffold; no iOS deliverable.
- Commands: `npm test -- --runInBand`, `npm run typecheck`, `npm run lint`; native checks from `android`: `./gradlew :app:testDebugUnitTest :app:assembleDebug` (Windows: `gradlew.bat`). Use Node 22.13+ on the Node 22 line.
- **User requirement: follow TDD for all development.** Write a failing behavior test, run it to establish red, implement to green, then refactor. For scaffolding/documentation use appropriate build/static/manual checks. Record red/green evidence in `docs/TDD_LOG.md`. Do not introduce application behavior first and retrofit tests.
- **Work one subtask at a time.** Explain the cases being tested before development, report red/green results, and make a local commit for each completed subtask. Do not push unless requested.
- Product specification: [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md).

## Immediate priority

The first deliverable is a minimal React Native TypeScript application with native Kotlin code that detects Instagram (`com.instagram.android`) entering the foreground and interrupts interaction on a physical Android device, including when the React Native UI is closed.

Physical Phase 1 testing is pending. The user authorized independently tested schedule rules while device testing is deferred. Complete the device-validation milestone before integrating scheduling into enforcement; emergency overrides, history, and polished UI remain later phases. Explain the chosen Android APIs, permissions, and limitations. Supply installation, permission setup, and repeatable physical-device test instructions. Record observed reliability problems. A successful build or one displayed overlay does not establish reliable enforcement. Do not claim physical-device validation without performing it.

## Architecture

- React Native / TypeScript: onboarding, permissions interface, app selection, schedule configuration, dashboard, history. Use React Navigation when navigation is needed and minimal state management.
- Kotlin: foreground monitoring, restriction evaluation, blocking interface, authoritative persisted session/override state, lifecycle and reboot handling.
- Enforcement must run without the JavaScript runtime or React Native UI.
- Use Jetpack DataStore for configuration and Room for event history when those phases require them. No backend or network dependency for normal operation.
- Keep policy evaluation independently unit-testable; keep Android presentation, persistence, monitoring, and React Native bridging separate. Avoid speculative abstractions and unnecessary dependencies.

Suggested native responsibilities: `AppMonitorService`, `RestrictionEngine`, `BlockController`, `OverrideManager`, `PolicyRepository`, `BootReceiver`, and `BlockingModule`. Introduce components as needed rather than generating empty placeholders.

## Platform boundaries

Validate current Android API behavior and Google Play requirements using official sources before making platform or distribution claims.

- Investigate AccessibilityService foreground-window events and a permitted native interruption mechanism. Accessibility does not disable other apps.
- Request only permissions required by the selected implementation. An accessibility overlay and an application overlay have different permission requirements; do not assume `SYSTEM_ALERT_WINDOW` is always needed.
- Usage access is optional, not a default requirement. Prefer targeted package visibility over broad visibility.
- Explain accessibility disclosure and consent. Never obstruct Android settings, permission revocation, or uninstallation.
- Do not promise unbreakable blocking or automatic recovery from force-stop or a disabled accessibility service.
- Account for Android-version and manufacturer differences, background activity limits, service lifecycle, and multi-window behavior.

## Product invariants

- Supported initial targets: Instagram, YouTube, Reddit, identified by package name; allow future additions.
- Recurring local-time schedules have start/end times and active weekdays. MVP schedules do not cross midnight.
- Once a session starts, freeze its effective policy. Edits apply to the next eligible session, never immediately bypass the active one.
- Blocking UI is minimal: “Not right now.”, the blocked app and scheduled unlock time, and emergency-access action when implemented. No motivation, gamification, or unnecessary interactions.
- Emergency access requires a 60-second wait and explicit confirmation, then allows all selected apps for at most five minutes and never past session end.
- Permit one completed override per session. Cancellation does not consume it. Closing/reopening the UI must not reset the wait or extend access.
- Kotlin persists and enforces deadlines. Use monotonic elapsed time within a boot session. On reboot, cancel pending requests and end active overrides conservatively; preserve the completed-override usage limit for that session.
- Derive states from policy, native timestamps, permissions, and events: `INACTIVE`, `BLOCKING`, `WAITING`, `READY_TO_UNLOCK`, `OVERRIDE_ACTIVE`. Report enforcement unavailability when required permissions are missing.
- Record sessions, blocked-app attempts, override requests/completions, and permission interruptions locally when history is implemented.

## Implementation and verification order

1. Native proof of concept: scaffold Android-capable React Native, monitor Instagram, interrupt access, test repeatedly with UI open and closed.
2. Persisted scheduling: packages, weekdays, boundaries, reboot recovery, future-effective edits.
3. Native emergency override state machine, including process death and reboot cases.
4. Minimal onboarding, permission setup, dashboard, selection, schedule, and history screens.
5. Broader reliability verification and documented limitations.

Use meaningful native policy/state-machine tests as logic grows. Physical-device checks must include repeated launches, notification entry, recents, UI closure, native process termination, reboot, permission revocation, split screen, time changes, and override expiry while a restricted app is already foreground. Distinguish observed results from untested scenarios.

## Scope exclusions

No iOS, authentication, backend APIs, cloud sync, AI coaching, gamification, social features, billing, browser-site blocking, wake detection, sophisticated analytics, or complex habit tracking.
