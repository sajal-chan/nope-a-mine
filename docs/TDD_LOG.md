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
