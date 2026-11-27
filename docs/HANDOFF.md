# Morning Blocker: development handoff

Updated: 2026-11-18. The new standalone **1.1-scheduling** APK is built from application source `cd6a27c` (versionCode 2), with native scheduled enforcement and app/day/time configuration. It is ready for user installation; actual scheduled phone behavior is not validated. See SCHEDULING_TESTING.md for installation, checksum and the test sequence; TDD_LOG.md retains historical prototype results.

## Start here

The next milestone is **installing and physically testing the new scheduled-blocking APK**. Native service, atomic configuration bridge and minimal app/day/time controls are complete. A fresh installation has no blocking until a schedule is saved. Current/future policies and deferred edits are displayed separately. The user asked work to stop at this installation checkpoint; do not claim scheduled device validation from automated checks. Initial continuous-prototype checks were accepted for continued development, with broader physical cases still deferred.

The previously installed APK continuously blocks Instagram while enabled. Its replacement is `android/app/build/outputs/apk/release/morning-blocker-1.1-scheduling-arm64.apk` (18,242,834 bytes). Installation has not been performed by the agent. Emergency access and history are not connected.

Scheduling and emergency-access rules have been developed independently. **The user has authorized scheduling integration after accepting the initial physical checks.** Read [AGENTS.md](../AGENTS.md) before continuing and use [PRODUCT_SPEC.md](PRODUCT_SPEC.md) for full scope.

## Repository and working agreement

- Workspace: `E:\nope-a-mine`; remote: https://github.com/sajal-chan/nope-a-mine.
- Default/development branch: `dev`; preserve it. Earlier work was published to `main` at user request and verified at `5e3d0be`; remote `dev` was not advanced. Later service/configuration/build work is committed locally and has not been pushed.
- Work one subtask at a time. Explain behavior cases, write and run failing tests, implement to green, then refactor. Record evidence in [TDD_LOG.md](TDD_LOG.md).
- Make a local commit for each completed subtask. Do not push unless requested. Ask the user when a product decision is needed.
- `.serena/` is unrelated, untracked workspace data; leave it alone.

## Completed work

| Area | Implemented | Remaining boundary |
| --- | --- | --- |
| Android scaffold | React Native 0.84.1, React 19.2.3, TypeScript, Kotlin, Android-only app | No iOS scope |
| Native service | Accessibility foreground monitoring, asynchronous scheduled decisions, native status/errors, cancellation/reconnection guards, periodic clock/boundary checks and bounded fast recovery | Physical scheduled-enforcement reliability remains unvalidated; no wake alarms/boot receiver |
| Setup/configuration screen | Disclosure, agreement/settings action, native status, supported app/day/time controls, atomic save/load, active-session/effective-date display and stale-response/duplicate-tap protection | Full dashboard/history later; actual native bridge/UI device behavior needs testing |
| Schedule rules | Local start/end, weekdays, package identifiers, boundaries and next session | Physical scheduled boundary tests pending |
| Native restriction decisions | RestrictionEngine uses persisted frozen sessions; service/gate consume decisions and overlay shows actual app/unlock time | Emergency integration pending |
| Policy persistence | Atomic Preferences DataStore, validation, frozen sessions, deferred edits, reopened-file/concurrency tests; shared repository and validated native configuration bridge | Physical update/restart recovery needs verification |
| Morning clock handling | Fresh local hours/zone on native callbacks, start/end while app stays foreground, rollback/re-entry and delayed-response/known-overlay expiry guards | No boot/time-change receiver or wake alarm; physical lifecycle testing pending |
| Emergency rules | Fixed 60-second wait, explicit confirmation, up to five minutes of access, one completed override per session, cancellation, expiry, session caps and reboot rules | No user-facing emergency workflow |
| Emergency persistence | Atomic commands, fixed deadlines across file reopening, concurrent-command tests, consumed-session ledger, corruption/IO handling, native elapsed-time/boot reader | Trusted policy context provider, UI/bridge, and enforcement integration pending |

## Decisions to preserve

- Morning restrictions follow **current local hours and time zone**, not a preserved duration. Clock changes are an accepted bypass limitation. First-time setup during an active window starts immediately.
- Freeze the effective policy once a session begins. Edits apply to the next eligible session; clock rollback/re-entry must not activate pending edits.
- Emergency waiting and temporary access use native persisted monotonic deadlines within one boot. JavaScript timers never authorize access.
- A wait reaching 60 seconds only makes confirmation available. Confirmation grants all selected apps at most five minutes, never beyond morning end.
- Cancellation of waiting/ready does not consume the allowance. Reboot cancels pending requests and ends active access, retaining completed usage for that session.
- Use a stable native session identity. Returning to a consumed session must not restore its allowance. Re-evaluate saved state with fresh native time before granting access.
- Keep Android settings, permission revocation, and uninstall available. Do not promise unbreakable blocking or automatic recovery after force-stop.

## Verification available at handoff

Current source checks: **237 native tests and 25 Jest tests pass**, TypeScript/ESLint pass, ARM64 debug/release builds and release vital checks pass, and Android debug lint has zero errors/18 warnings. APK v2 signature verifies with the same local-test certificate as the old APK; version/package/ABI and bundled UI/bridge/native-library contents were checked. See the latest TDD_LOG.md entry for red/green evidence.

Latest standalone scheduled-build preflight: **2026-11-18**, source `cd6a27c`. SHA-256: `e0f88e16afcde582a5e308871e2963883a4ab14ab066ee1c0455e68690e4bcb2`. Earlier prototype figures/checksums remain in TDD_LOG.md.

| Check | Recorded result |
| --- | --- |
| Native JUnit/Robolectric | 237 tests; zero failures, errors, or skips |
| JavaScript Jest | 25 tests passed |
| TypeScript | Passed |
| ESLint on tracked source/configuration files | Zero errors; one warning for explicitly passing ignored `.prettierrc.js` |
| Android debug lint | Zero errors, 18 warnings |
| Standalone ARM64 release build and release vital checks | Passed |
| APK signature verification | Passed |
| Physical phone testing | Initial continuous-prototype checks accepted; installation and tests of 1.1-scheduling pending. |

Jest ran directly through Node with `--runInBand --watchman=false`. Release bundling waited 12.5 seconds for Watchman, then completed; no workaround was needed. These are tooling observations, not device reliability results.

File-reopen and simulated reboot tests are not actual process-kill, device-reboot, or deep-sleep validation. Older documents/TDD entries retain historical counts and superseded duration-preservation behavior; use the latest entries and [CLOCK_RECOVERY.md](CLOCK_RECOVERY.md).

## Next tasks, in order

1. **Phase 1 accepted for continued development.** Initial manual checks were reported successful and accepted by the user on 2026-11-18. Remaining device/version details, measurements and lifecycle cases in [PHASE_1_TESTING.md](PHASE_1_TESTING.md) are deferred to the broader reliability phase, not claimed as passing.
2. **Install and test scheduled blocking.** Service/gate, atomic validated native configuration and minimal app/day/time UI are complete, and the standalone APK is ready. Follow SCHEDULING_TESTING.md: save a short future window, test start/end while Instagram stays foreground, UI closure/offline behavior, persistence and edits staying deferred. Report exact device/version details and observed delays/failures. Never drive enforcement from latest/pending configuration. Battery impact, sleep/reboot/force-stop behavior and manufacturer reliability remain physical verification work.
3. **Supply the trusted emergency context.** Tie each command to the persisted frozen native session identity, fresh elapsed time/boot identity, and current remaining morning bound. Test genuine session rollover, clock re-entry, reopen, concurrent operations, and failures without resetting used allowances. Review coordination between the separate policy and emergency stores.
4. **Connect the emergency workflow and enforcement.** Add native bridge operations and minimal request/countdown/confirm/cancel presentation. Kotlin remains authoritative. Ensure native re-evaluation resumes blocking at expiry even if a restricted app stays foreground and the React Native UI is closed. Validate real reboot/process-death behavior and session-end caps.
5. **Build the minimal product UI.** Add navigation as needed, onboarding/permission status, supported-app selection (Instagram, YouTube, Reddit), schedule editing, and a dashboard showing native state, today's window, selected apps, remaining time, and next session. Keep enforcement-unavailable states explicit.
6. **Add local history.** Use Room when implementing session completions, blocked-app attempts, emergency requests/completions, and permission interruptions. No cloud or advanced analytics.
7. **Run full reliability verification.** Extend Samsung testing to scheduled boundaries, policy edits, time changes, override expiry in the foreground, restart recovery, and the complete offline workflow. Record observed support/limitations. Review current official Android/Google Play requirements and configure distribution signing if distribution becomes a requested deliverable.

Each numbered item contains multiple subtasks: implement and commit one at a time. Item 1's initial checks were accepted by the user on 2026-11-18; remaining physical cases are deferred to item 7. Proceed with item 2 under that explicit acceptance.

## Build and install

Use Node 22.13+ on the Node 22 line (`.node-version`: 22.23.4), JDK 17, SDK/Build Tools 36, NDK 27.1.12297006, and Android platform-tools. The machine's default Node was 22.12 at last preflight; select the compatible runtime before running commands. Set `JAVA_HOME` and `ANDROID_HOME`.

From the repository root, with dependencies installed:

```powershell
npm test -- --runInBand
npm run typecheck
npm run lint
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

For a standalone build with bundled JavaScript, from `android`:

```powershell
.\gradlew.bat :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/release/app-release.apk
```

For ADB installation, enable USB debugging and accept the phone's authorization prompt; direct APK transfer/install is also possible without a laptop. Open Morning Blocker, review/agree to the disclosure, enable its accessibility service through Android settings, and return to verify **Service connected**. Version 1.1-scheduling needs a saved schedule: use the app/day/time controls and Save schedule. Disable the service to stop enforcement. The release APK uses the public debug key for local testing; production signing is not configured.

For interactive development, use `npm start` and `npm run android` in separate root terminals. Debug setup needs Metro; use `adb reverse tcp:8081 tcp:8081` when needed. The standalone build needs no Metro. This repository uses the React Native CLI and custom Kotlin code, so Expo Go QR scanning is not the installation workflow.

## Code and documentation map

- `src/screens/SetupScreen.tsx`, `src/native/BlockingModule.ts`: current setup and typed bridge.
- `android/app/src/main/java/com/morningblocker/blocking/`: monitor, foreground gate, overlay, status and React Native bridge.
- `android/app/src/main/java/com/morningblocker/scheduling/`: pure rules, session models and current-local-time reader.
- `android/app/src/main/java/com/morningblocker/storage/`: policy configuration, frozen sessions and shared DataStore repository.
- `android/app/src/main/java/com/morningblocker/emergency/`: pure transitions, transactional repository, shared store and monotonic clock reader.
- `android/app/src/test/`, `__tests__/`: native and JavaScript tests.
- [EMERGENCY_ACCESS.md](EMERGENCY_ACCESS.md): command, persistence and recovery contracts.
- [README.md](../README.md), [PRODUCT_SPEC.md](PRODUCT_SPEC.md), [PHASE_1_TESTING.md](PHASE_1_TESTING.md), [CLOCK_RECOVERY.md](CLOCK_RECOVERY.md), [TDD_LOG.md](TDD_LOG.md): setup, scope, testing and decisions.

Keep the MVP offline and Android-only. No backend, authentication, sync, billing, coaching, gamification, website blocking, wake detection, or sophisticated analytics.
