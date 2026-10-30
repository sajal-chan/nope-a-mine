# TDD record

Use red → green → refactor for every behavior change. Generated framework/build scaffolding is infrastructure; application behavior starts with a failing test. Tests do not substitute for physical-device enforcement validation.

## Phase 1

- Native gate tests written before implementation: Instagram matching, repeated-event deduplication, unrelated-app access, leaving/reopening, unknown foreground, presentation failure/retry, and cleanup.
- Native overlay tests written before implementation: touch interception and window type, focus flags, duplicate presentation, Home behavior, and settings access.
- Setup-screen tests written before implementation: disclosure and explicit consent, enabled versus connected, refresh after returning from settings, and failure reporting.

### Executed on 2026-10-28

1. **Red (setup screen):** five executable tests failed against an empty `App`, with missing disclosure, status, refresh, and settings controls. The earlier initial run also failed module resolution before the skeleton existed.
2. **Red (TypeScript bridge):** four tests failed against throwing bridge stubs: status forwarding, settings forwarding, missing-module reporting, and preservation of native failures.
3. **Red (native):** `:app:testDebugUnitTest` failed at `compileDebugUnitTestKotlin` because `ForegroundGate`, `BlockingSurface`, `BlockController`, and `AppMonitorService` did not exist. This was a compile-time red, not an executed assertion failure.
4. **Green:** implemented the native components and React Native setup/bridge; corrected safe-area and AppState test mocks. Nine Jest tests passed. Eleven native tests passed: six pure gate tests, three Robolectric overlay tests, and two Robolectric service tests. No native tests skipped.
5. **Refactor/checks:** formatted TypeScript, normalized the generated Kotlin source paths, removed iOS-only scaffold files, and separated the debug-only network permission. TypeScript and ESLint passed. `:app:assembleRelease` and its release vital lint checks passed for `arm64-v8a`; APK signing verification passed.

Environment: React Native 0.84.1, React 19.2.3, TypeScript, JDK 17, SDK/Build Tools 36, NDK 27.1.12297006. Node 22.23.4 was downloaded for tooling without changing the system Node installation. The initial dependency install used system Node 22.12; the final direct Jest check used Node 22.23.4.

Final Jest run: two suites, nine tests, zero failures. Native JUnit XML reports: three suites, eleven tests, zero failures/errors/skips. The standalone APK is generated at `android/app/build/outputs/apk/release/app-release.apk` and intentionally remains ignored by Git.

Physical Samsung validation is pending; `adb devices -l` listed no attached device. Robolectric simulates Android APIs and cannot establish actual foreground detection, touch interception, OEM lifecycle reliability, or runtime React Native bridge behavior on the phone. The device matrix in `PHASE_1_TESTING.md` remains open. Scheduling and emergency access have not been started.

### Phase 1 follow-up — transient failure recovery (2026-10-28)

Inspection found that the original service made one delayed check and stopped if foreground identification or overlay presentation was still unavailable. Recovery then depended on a new Android event.

1. **Red:** added seven service regression tests before changing production code. The service suite executed nine tests, with four assertion failures: late foreground snapshot, temporary presentation failure, temporary read failure, and multiple bounded recovery attempts. Existing behavior already passed navigation cleanup, unbind cancellation, and interrupt handling checks.
2. **Green:** the gate now reports whether an observation is resolved. The service retries unresolved observations/presentation with delays of 100, 250, 500, and 1,000 ms, stopping after the budget is exhausted. A new relevant event or service connection starts a fresh budget. Existing 250 ms monitoring continues while an overlay is displayed. Recovered detection errors are cleared without discarding unrelated native errors.
3. **Verification:** the full native suite passed all eighteen tests: six gate, three overlay, and nine service tests, with zero failures/errors/skips. The standalone ARM64 APK rebuild and release vital lint checks passed.

No JavaScript behavior or dependencies changed. Physical-device testing remains pending; these tests simulate lifecycle and timing through Robolectric and a fake foreground reader/presentation surface.

## Phase 2, subtask 1 — pure schedule rules (2026-10-29)

The user authorized isolated schedule-rule development while Samsung validation is deferred, with cases explained before development and a local commit for each completed subtask. The rules are not connected to the accessibility service or React Native bridge.

Cases covered by twenty-one tests:

- Inclusive start, active interior, immediately before end, exclusive end, immediately before start, and after end.
- Unselected weekdays; each of the seven weekdays independently; empty weekdays disabling active and upcoming sessions.
- Rejecting equal times and overnight windows; allowing a valid window to start at midnight.
- Next session before today's start, exactly at start, during a current session, at end, and after end on a daily schedule.
- Choosing the closest eligible weekday irrespective of set order; next-week/month and year rollover.
- Copying the caller's mutable weekday set so later input mutation cannot change the schedule.

**Red:** all twenty-one tests executed against compileable stubs. Sixteen assertion failures established missing boundary evaluation, validation, upcoming-session calculation, and defensive copying. Five inactive-result cases already passed against null-returning stubs.

**Green:** implemented immutable `MorningSchedule`, `ScheduledSession`, and stateless `ScheduleRules`. The caller supplies local date/time, so tests do not depend on the system clock. Active windows are `[start, end)`; `nextSession` excludes any start at or before now. Empty weekdays return no session. Date calculations use `java.time`, with Android core library desugaring enabled for minimum API 24 compatibility.

**Regression check:** all thirty-nine native tests passed (twenty-one schedule tests plus the existing eighteen blocker tests), with zero failures/errors/skips. `:app:lintDebug` completed with zero errors and sixteen warnings concerning existing resources/manifest configuration and a test-dependency update notice; none refer to the scheduling sources. `:app:assembleDebug` passed for ARM64, including core-library desugaring. No JavaScript behavior changed, so the JavaScript suite was not rerun for this native-only subtask.

Persistence, package selection storage, active-session policy freezing, enforcement integration, clock/time-zone/DST recovery, and device validation remain separate work. This subtask makes no claim that real-device blocking is reliable or schedule-driven.

## Phase 2, subtask 2 — DataStore configuration persistence (2026-10-30)

Cases announced before implementation and covered by twenty-one new tests:

- Fresh-install unconfigured state; complete schedule/package round trip; closing and reopening a real temporary store; seconds/nanoseconds retained exactly.
- Empty weekdays/packages; complete replacement of previous selections; defensive copying; malformed package identifiers rejected.
- Observable complete policy updates; concurrent saves retaining a whole policy rather than mixed fields.
- Partial records, invalid times, overnight windows, unknown weekdays, invalid packages, and wrong preference types reported as corruption.
- Future schema rejected for reads and writes; corrupt file bytes preserved; read/write IO failures propagated.
- Android context wrappers sharing one application-level DataStore instance.

**Red:** the twenty repository tests ran against compileable stubs; nineteen failed, with only the fresh-install case passing. The additional Android factory test failed at its unimplemented factory stub after correcting a test API signature. The observer test timed out waiting for updates and the concurrent-save test hit its missing saved policy; other failures established absent validation, persistence, and error reporting.

**Green:** implemented immutable `BlockingPolicy`, `PolicyRepository` using Preferences DataStore 1.2.1, and an application-context factory using a file-scope `preferencesDataStore` delegate. Reads expose a nullable policy and a flow. Writes replace all policy fields transactionally after validating existing schema/data. Schema 1 persists ISO local times, weekday names, and package names. No corruption replacement handler is installed and IO failures are not swallowed. Unknown schema versions cannot be overwritten by ordinary saves. Coroutines 1.10.2 are explicitly declared.

**Test runtime finding:** the first implementation run passed fifty-seven of sixty tests, but repeated writes failed on Windows when the Android DataStore artifact ran with stub Android APIs outside Robolectric. Running repository tests under Robolectric API 34 (still using actual temporary disk files and real DataStore instances) resolved all three file-replacement failures without a production workaround.

**Regression result:** all sixty native tests passed: twenty repository tests, one Android store-factory test, twenty-one schedule-rule tests, and eighteen blocker tests. No failures/errors/skips. `:app:lintDebug` completed with zero errors and eighteen warnings (existing manifest/resources plus dependency update notices). `:app:assembleDebug` passed for ARM64. No JavaScript behavior changed, so the JavaScript suite was not rerun for this native-only subtask.

The configuration repository remains independent of the service and JavaScript bridge. Active-session freezing, pending edits, recovery, overrides, and physical-device enforcement validation are not implemented by this subtask. Saved package names are not assumed to be installed. Explicit storage repair/migration and UI error presentation remain future work.

## Phase 2, subtask 3 — active-session policy protection (2026-10-30)

Cases explained before implementation:

- Unconfigured and pre-window states; exact start/end boundaries; late first evaluation retaining the original scheduled start.
- Earlier/later end edits, app removal, clearing selected days/apps, and edits exactly at start or before any session evaluation preserving the original session.
- Pre-session edits applying that morning; latest repeated edit and weekday changes applying on the next eligible date.
- No second same-day session after an edit, including when the original window elapsed without being observed.
- Frozen policy and pending edits surviving DataStore close/reopen; concurrent edit/evaluation preserving one original policy.
- Partial saved session and storage failures surfacing as errors rather than clearing protection.
- First-time setup inside its active window starting immediately on evaluation (provisional assumption, raised with the user asynchronously).

**Red:** the first twenty session tests executed against a null-returning session stub; fifteen failed. Added two tests for first-time in-window setup and an elapsed unobserved window before implementation. The second red run executed twenty-two tests with seventeen failures; five inactive cases already passed. Existing persistence tests were adapted to pass explicit pre-window timestamps through the new save API.

**Green:** added immutable `RestrictionSession` and optional schema-1 session metadata. `save(policy, now)` captures the old policy and stores a later effective date for edits once a session has begun. `activeSessionAt(now)` captures/returns the frozen session transactionally. Expired snapshots remain as a same-date guard; new eligible dates can capture the latest policy. Read-only configuration APIs return the latest configured/pending policy and are explicitly documented as unsuitable for direct enforcement. Shared policy encoding/validation is reused for configuration and snapshots. Existing configuration-only records remain compatible.

**Verification:** all eighty-two native tests passed: twenty-two session tests plus the previous sixty. Zero failures/errors/skips. Android lint completed with zero errors and eighteen warnings; the ARM64 debug build passed. No JavaScript changes; the JavaScript suite was not rerun.

No service, alarm, boot receiver, JavaScript bridge, emergency override, or history integration is included. Device validation and wall-clock/time-zone/DST recovery remain pending. The immediate-first-setup behavior is a stated provisional choice, not a user approval or a completed UI decision.
