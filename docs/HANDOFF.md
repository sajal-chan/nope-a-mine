# Morning Blocker: development handoff

Prepared: 2026-11-18. Code baseline: `ed265d4` (`Persist emergency access state and add native override clock`). This document records repository inspection and previously completed checks; application tests were not rerun for this documentation update.

## Start here

The next milestone is **completing physical Phase 1 validation on the user's Samsung A34-family phone**. On 2026-11-18, the user reported successful initial checks of blocking, Home/unrelated apps, repeated launcher/recents entry, UI dismissal, offline use, lock/unlock, and accessibility revocation/re-enabling. Exact device/version details, attempt counts, and remaining lifecycle/multi-window cases are still pending; see PHASE_1_TESTING.md. This user report does not complete the validation milestone.

The installed prototype continuously blocks Instagram (`com.instagram.android`) while its accessibility service is enabled. It has a disclosure/setup screen, native service status, a blocking overlay, Go Home, and an accessibility-settings exit. It does not yet offer schedules, emergency access, app selection, or history.

Scheduling and emergency-access rules have been developed independently while phone testing is deferred. **Complete the device-validation milestone before connecting either feature to live enforcement.** Read [AGENTS.md](../AGENTS.md) before continuing and use [PRODUCT_SPEC.md](PRODUCT_SPEC.md) for full scope.

## Repository and working agreement

- Workspace: `E:\nope-a-mine`; remote: https://github.com/sajal-chan/nope-a-mine.
- Default/development branch: `dev`; preserve it. At inspection, it was ten commits ahead of the locally recorded `origin/dev`, before this documentation commit. Remote state was not fetched or changed.
- Work one subtask at a time. Explain behavior cases, write and run failing tests, implement to green, then refactor. Record evidence in [TDD_LOG.md](TDD_LOG.md).
- Make a local commit for each completed subtask. Do not push unless requested. Ask the user when a product decision is needed.
- `.serena/` is unrelated, untracked workspace data; leave it alone.

## Completed work

| Area | Implemented | Remaining boundary |
| --- | --- | --- |
| Android scaffold | React Native 0.84.1, React 19.2.3, TypeScript, Kotlin, Android-only app | No iOS scope |
| Native prototype | Accessibility foreground monitoring, Instagram interruption through an accessibility overlay, native status bridge, service cleanup and bounded detection/presentation retries | Reliability on the Samsung is unvalidated |
| Setup screen | Disclosure, explicit agreement/settings action, connected/enabled/error status, refresh on app return | Full onboarding/dashboard later |
| Schedule rules | Local start/end, weekdays, package identifiers, boundaries and next session | No live schedule enforcement or configuration UI |
| Policy persistence | Atomic Preferences DataStore, validation, frozen active-session policy, deferred edits, file-reopen tests | No schedule bridge or enforcement wiring |
| Morning clock handling | Current local hours/time zone, immediate first setup inside the window, rollback/re-entry with frozen policy, retirement of old duration checkpoints | No boot/time-change receiver or live scheduling lifecycle |
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

Last preflight: **2026-11-18**, clean app rebuild from `808e1d1`, against the same application code baseline. See the latest TDD_LOG.md entry for the APK checksum and exact commands.

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

1. **Install and validate Phase 1.** Follow [PHASE_1_TESTING.md](PHASE_1_TESTING.md). Record exact phone model, Android/One UI, Instagram version, commit, attempt counts, exposure before interruption, stuck overlays, unrelated-app interference, and logs. Start with 20 launches per launcher/recents/notification route. Include UI dismissal, offline use, Home/settings, permission revocation, lock/unlock, multi-window, process termination, force-stop, and reboot. Document limitations and resolve observed defects with TDD before declaring the milestone complete.
2. **Connect native scheduling after that milestone.** Make foreground evaluation use the frozen effective session and selected packages rather than the hardcoded Instagram gate. Handle start/end while an app is already foreground, service startup/reconnection, clock/time-zone changes, and reboot where Android permits. Test exact boundaries, pending edits, inactive sessions, and permission failure. Never drive enforcement directly from `load()`/`policies`, which may expose future configuration.
3. **Supply the trusted emergency context.** Tie each command to the persisted frozen native session identity, fresh elapsed time/boot identity, and current remaining morning bound. Test genuine session rollover, clock re-entry, reopen, concurrent operations, and failures without resetting used allowances. Review coordination between the separate policy and emergency stores.
4. **Connect the emergency workflow and enforcement.** Add native bridge operations and minimal request/countdown/confirm/cancel presentation. Kotlin remains authoritative. Ensure native re-evaluation resumes blocking at expiry even if a restricted app stays foreground and the React Native UI is closed. Validate real reboot/process-death behavior and session-end caps.
5. **Build the minimal product UI.** Add navigation as needed, onboarding/permission status, supported-app selection (Instagram, YouTube, Reddit), schedule editing, and a dashboard showing native state, today's window, selected apps, remaining time, and next session. Keep enforcement-unavailable states explicit.
6. **Add local history.** Use Room when implementing session completions, blocked-app attempts, emergency requests/completions, and permission interruptions. No cloud or advanced analytics.
7. **Run full reliability verification.** Extend Samsung testing to scheduled boundaries, policy edits, time changes, override expiry in the foreground, restart recovery, and the complete offline workflow. Record observed support/limitations. Review current official Android/Google Play requirements and configure distribution signing if distribution becomes a requested deliverable.

Each numbered item contains multiple subtasks: implement and commit one at a time. Integration steps remain gated on physical Phase 1 validation. Independent tests do not remove that gate.

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

Enable USB debugging and accept the phone's authorization prompt. Open Morning Blocker, review/agree to the disclosure, enable its accessibility service through Android settings, return and verify **Service connected**, then follow the test matrix. Disable the service to stop continuous prototype blocking. The release APK uses the public debug key for local testing; production signing is not configured.

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
