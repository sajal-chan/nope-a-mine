# Morning Blocker: development handoff

Updated: 2026-11-18. Current source includes native scheduled service integration. The previously supplied standalone APK was built from `808e1d1` (application baseline `ed265d4`) and still has continuous Instagram prototype behavior. See TDD_LOG.md for current source checks and the historical APK checksum.

## Start here

The next milestone is **schedule configuration through the native bridge and minimal UI**. Native scheduled service integration is implemented: selected packages use the frozen persisted session, with automatic reevaluation and scheduled overlay details. A fresh installation has no blocking until configured; no configuration bridge/UI exists yet. On 2026-11-18 the user accepted initial continuous-prototype phone checks for continued development. Remaining physical cases, including all scheduled-enforcement scenarios, are still pending; see PHASE_1_TESTING.md.

The APK installed on the user's phone still continuously blocks Instagram (`com.instagram.android`) while enabled. No replacement standalone APK has been produced by the service-integration subtask. Current source switches to saved schedules, with no continuous fallback, and truthfully discloses the missing configuration UI. Emergency access and history are not connected.

Scheduling and emergency-access rules have been developed independently. **The user has authorized scheduling integration after accepting the initial physical checks.** Read [AGENTS.md](../AGENTS.md) before continuing and use [PRODUCT_SPEC.md](PRODUCT_SPEC.md) for full scope.

## Repository and working agreement

- Workspace: `E:\nope-a-mine`; remote: https://github.com/sajal-chan/nope-a-mine.
- Default/development branch: `dev`; preserve it. The user requested publishing earlier work to `main`, which was verified at `5e3d0be`; remote `dev` was not advanced. This service-integration subtask is local until another push is requested.
- Work one subtask at a time. Explain behavior cases, write and run failing tests, implement to green, then refactor. Record evidence in [TDD_LOG.md](TDD_LOG.md).
- Make a local commit for each completed subtask. Do not push unless requested. Ask the user when a product decision is needed.
- `.serena/` is unrelated, untracked workspace data; leave it alone.

## Completed work

| Area | Implemented | Remaining boundary |
| --- | --- | --- |
| Android scaffold | React Native 0.84.1, React 19.2.3, TypeScript, Kotlin, Android-only app | No iOS scope |
| Native service | Accessibility foreground monitoring, asynchronous scheduled decisions, native status/errors, cancellation/reconnection guards, periodic clock/boundary checks and bounded fast recovery | Physical scheduled-enforcement reliability remains unvalidated; no wake alarms/boot receiver |
| Setup screen | Disclosure, explicit agreement/settings action, connected/enabled/error status, refresh on app return | Full onboarding/dashboard later |
| Schedule rules | Local start/end, weekdays, package identifiers, boundaries and next session | Configuration bridge/UI pending |
| Native restriction decisions | RestrictionEngine uses persisted frozen sessions; service/gate consume decisions and overlay shows actual app/unlock time | Configuration bridge/UI and emergency integration pending |
| Policy persistence | Atomic Preferences DataStore, validation, frozen active-session policy, deferred edits, file-reopen tests; shared repository now used by service | No schedule bridge |
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

Current source checks: **214 native tests and 9 Jest tests pass**, TypeScript/ESLint pass, ARM64 debug build passes, and Android debug lint has zero errors/18 warnings. No standalone release was rebuilt for the scheduling change. See the latest TDD_LOG.md entry for red/green evidence.

The following table describes the **earlier standalone prototype preflight** on 2026-11-18 from `808e1d1`; its results do not validate scheduled enforcement. See its TDD_LOG.md entry for the APK checksum.

| Check | Recorded result |
| --- | --- |
| Native JUnit/Robolectric | 172 tests; zero failures, errors, or skips |
| JavaScript Jest | 9 tests passed |
| TypeScript | Passed |
| ESLint on tracked source/configuration files | Zero errors; one warning for explicitly passing ignored `.prettierrc.js` |
| Android debug lint | Last run 2026-11-05: zero errors, 18 warnings; not rerun on November 18 |
| Standalone ARM64 release build and release vital checks | Passed |
| APK signature verification | Passed |
| Physical phone testing | Initial manual checks reported successful by user on November 18; full matrix pending. No ADB-connected device was observed. |

Jest passed using `--runInBand --watchman=false` after the local Watchman path stalled. PowerShell/npm also dropped the intended Jest argument on that run; use direct Node invocation if this recurs. Bundling subsequently completed. These are local tooling observations, not blocker reliability results.

File-reopen and simulated reboot tests are not actual process-kill, device-reboot, or deep-sleep validation. Older documents/TDD entries retain historical counts and superseded duration-preservation behavior; use the latest entries and [CLOCK_RECOVERY.md](CLOCK_RECOVERY.md).

## Next tasks, in order

1. **Phase 1 accepted for continued development.** Initial manual checks were reported successful and accepted by the user on 2026-11-18. Remaining device/version details, measurements and lifecycle cases in [PHASE_1_TESTING.md](PHASE_1_TESTING.md) are deferred to the broader reliability phase, not claimed as passing.
2. **Add schedule configuration.** The native service/gate integration is complete and tested, including selected apps, no configured session, foreground start/end, clock/zone changes, frozen pending edits, slow IO/navigation, cancellation/reconnection and dynamic overlay details. Next expose validated native configuration operations using fresh native time, then add minimal supported-app/weekday/start/end controls and explain deferred edits. Never drive enforcement directly from `load()`/`policies`, which may expose future configuration. After configuration is usable, produce a new standalone APK and test actual scheduled boundaries on the phone, including a selected app already foreground. Battery impact, sleep/reboot/force-stop behavior and manufacturer reliability remain physical verification work.
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

For ADB installation, enable USB debugging and accept the phone's authorization prompt; direct APK transfer/install is also possible without a laptop. Open Morning Blocker, review/agree to the disclosure, enable its accessibility service through Android settings, and return to verify **Service connected**. The old supplied APK uses continuous Instagram blocking; current source needs a saved schedule and has no configuration UI yet. Disable the service to stop enforcement. The release APK uses the public debug key for local testing; production signing is not configured.

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
