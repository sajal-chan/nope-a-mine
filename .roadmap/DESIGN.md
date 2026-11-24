# Design
Revision: 1
Status: approved for task execution (user confirmation 2026-11-24)

## Problem and objectives

Complete the remaining Android-only, offline Morning Blocker MVP from the existing working application. Preserve native enforcement without React Native, frozen session policies, current-local-hours scheduling, and the existing persisted emergency state machine. Revision 1 was approved for task execution on 2026-11-24. Approval is not a claim of device validation.

The scheduled physical checkpoint is the 1.1-scheduling APK in docs/SCHEDULING_TESTING.md. On 2026-11-24 the user reported general testing without exact build/device details and explicitly directed development to continue, deferring remaining checkpoint details. This resolves the continuation gate without identifying the tested artifact or marking any unconfirmed scenario passed. Raise a device check when a concrete development blocker requires it; retain the later reliability matrix. The 2026-11-18 acceptance of initial continuous-prototype checks remains valid; its unconfirmed cases stay deferred rather than being relabeled as failures or passes.

## Requirements and non-goals

Implement the remaining emergency workflow, minimal onboarding/dashboard/navigation, local history, recovery integration, and physical reliability verification. Initial targets remain Instagram, YouTube, and Reddit by package name. Keep MVP windows within one local day, start inclusive/end exclusive. First setup inside a window activates immediately. Changes never weaken the frozen active session; pending policy applies on a later eligible date.

Emergency access requires a fixed native 60-second wait, explicit confirmation, and one completed grant per session for all frozen selected packages. Access lasts at most 300 seconds and never past the current morning end. Cancellation does not consume the allowance. Same-boot reopen retains deadlines; reboot cancels wait/ready and ends grants while retaining completed usage. Current local time controls morning windows; monotonic elapsed time controls emergency timers. Manual clock changes remain an accepted bypass.

Keep Settings, permission revocation, and uninstallation available. Show unavailable enforcement when permission/service/storage/foreground/presentation requirements fail. Offline operation needs no backend, network, Metro, or JavaScript runtime.

Non-goals: redoing the scaffold, pure scheduling, existing DataStore repositories, or pure emergency transitions; parental/enterprise control; unbreakable blocking; automatic restart after force-stop or disabled accessibility; iOS, cloud, accounts, billing, AI, gamification, websites, wake detection, advanced analytics; production signing or Play publication; unrelated project/global workflow improvements.

## Existing architecture and constraints

Repository evidence reviewed: AGENTS.md; docs/PRODUCT_SPEC.md, HANDOFF.md, EMERGENCY_ACCESS.md, CLOCK_RECOVERY.md, SCHEDULING_TESTING.md and PHASE_1_TESTING.md; actual Kotlin/TS source and test inventory. Existing tests/build counts in HANDOFF are historical evidence. Separately, the coordinator reports fresh 2026-11-24 checks at ec81932: 25 Jest tests/3 suites, TypeScript and full ESLint passed; 237 native tests with zero failures/errors/skips and ARM64 debug assembly passed using Node22.23.4/JDK17. Full ESLint has one generated-report no-shadow warning; tracked-file ESLint separately has an existing ignored .prettierrc.js warning. Native output has SDK XML/Gradle deprecation warnings. The planner ran no application tests/builds, and these checks supply no new physical-device evidence.

- AppMonitorService receives accessibility window events, reads package metadata, serializes asynchronous RestrictionEngine evaluations off the main looper, rejects stale foreground/session responses, and schedules native callbacks. ForegroundGate and BlockController present TYPE_ACCESSIBILITY_OVERLAY. Main-looper callbacks are nominally 250 ms while blocking and 1 s otherwise; these are not latency guarantees and do not wake a sleeping device.
- PolicyRepository persists editable/effective policy and frozen sessions in morning_policy Preferences DataStore. RestrictionSession currently has date and policy, with no explicit persisted emergency identity. configurationAt can return a re-entry session with the current local date while preserving a captured frozen policy.
- EmergencyAccess, EmergencyRepository, emergency_access DataStore, and AndroidOverrideClock already implement/persist transitions, ledger, monotonic time and boot identity. They are not connected to live enforcement, bridge or UI.
- BlockingModule exposes settings/status and atomic schedule configuration only. SetupScreen/ScheduleSettings already provide disclosure, consent/settings entry, service status, supported selections, validation, current/future policy separation and race protections.
- Tests exist for schedule/local-clock/policy/reopen/concurrency, emergency transitions/reopen/errors, Android clocks, service/gate/presentation, bridge and screen behavior. Build or Robolectric success is not physical enforcement evidence.
- Stack stays React Native 0.84.1, React 19.2.3, TypeScript, Kotlin, SDK 36, JDK 17; Node 22.13+ on the Node 22 line. Use React Navigation once multiple screens are introduced and minimal local state.

AccessibilityService lifecycle is system-managed; service connection/reconnection is the evaluation entry point. Official reference: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService). A broadcast receiver cannot be presented as a guaranteed real-time wakeup or restart mechanism; Android documents broadcast delivery constraints in [Broadcasts](https://developer.android.com/develop/background-work/background-tasks/broadcasts). Current exact overlay, accessibility policy, permission and lifecycle contracts must be verified against official documentation in TASK-002 before the new integration is designed around them.

## Proposed architecture and interfaces

Extend existing components incrementally. Do not create empty replacement layers.

1. Native session identity: attach an opaque stable identity to the persisted captured session. Its lifetime follows the frozen session, not editable policy, a UI reopen, returned local date, or a newly generated per-call value. Preserve it across clock rollback/re-entry and process reopen; rotate only for a genuinely captured later eligible session. Migrate existing policy data conservatively without resetting configuration or allowance. Existing ledger compatibility must be assessed before changing identity representation.

2. Trusted context/coordinator: add the smallest shared native coordination boundary needed by configuration saves, native status/commands and enforcement. It ties PolicyRepository's frozen identity and effective packages to a newly sampled AndroidOverrideClock and current local morning bound. EmergencyRepository's provider still runs inside its serialized DataStore edit. Never call emergency operations while holding a policy DataStore edit, or establish reverse nested locks. Document lock order, await points and cancellation. After any suspended IO, recheck time, boot, session and authorization before granting or presenting. Separate stores do not provide a cross-file transaction; correctness must come from serialization, stable identities, revalidation and conservative failures.

3. Enforcement: RestrictionEngine/native decision composition consults fresh recovered emergency status before allowing any selected package. WAITING and READY_TO_UNLOCK stay blocked. OVERRIDE_ACTIVE authorizes all packages from the frozen session only while native monotonic and current morning bounds both hold. Native callbacks and reconnects expire grants while selected apps remain foreground and React Native is closed. Stale asynchronous Allowed results must not remove a newly required block; stale Blocked results must not cover unrelated apps. IO/corruption/boot-clock errors surface unavailable enforcement and must never authorize a grant.

4. Native snapshots and commands: extend BlockingModule and its typed TS wrapper with read-only runtime snapshot and request/confirm/cancel operations. No command accepts session identity, deadlines, elapsed time, used flags or authorization from JavaScript. Snapshot includes phase, service/permission availability and reason, effective frozen selection/window, editable-policy deferral, next eligible session, native remaining values, usage and allowed actions. Return errors distinctly from INACTIVE. Poll/resume refresh uses native snapshots; displayed countdowns may interpolate for display but cannot enable confirmation or grant access independently.

5. Presentation: extend the existing native accessibility overlay with minimal emergency request/wait/ready/confirm/cancel controls and consumed-allowance messaging. Keep "Not right now.", blocked app and normal unlock time. Native controls use the same coordinator as the bridge so the workflow works with JavaScript absent. Async UI actions suppress duplicates and stale responses. Settings/Home exits remain usable. React Native dashboard also shows authoritative emergency state and appropriate commands without duplicating policy logic.

6. Lifecycle: reconcile saved state when native enforcement/status/commands resume, and test reboot/process restoration. Decide whether connected-service time/time-zone/screen events improve bounded refresh; use receivers only if official contracts and physical evidence justify them. A boot receiver is not presumed mandatory and must not attempt to enable accessibility. Handler-only sleep behavior remains a stated limitation unless a separately approved scoped change addresses a measured defect.

7. Interface: preserve tested configuration controls, add small React Navigation routes for onboarding/permission setup, dashboard/settings and history. Avoid replacing proven configuration behavior merely for style. Dashboard distinguishes all five native phases and unavailable enforcement; shows today's effective policy, remaining normal time, next session, and deferred edits. No motivational or analytical layer.

8. History: introduce Room for app-private on-device records, following [Room](https://developer.android.com/training/data-storage/room). Model native event identity, type, session identity, occurrence/observation time and applicable package; stable event IDs support idempotent retry. Record observed session start/completion, blocking attempts, emergency request/completion/cancellation as needed for correct interpretation, and observable permission interruptions. Define one attempt as a distinct observed selected-app entry/re-entry, never each 250 ms poll. Define delayed recovered completion separately from observation time; do not fabricate events before history existed or while execution was unobservable. Persist delivery intent with authoritative transitions where needed and retry idempotently into Room; no cross-DataStore/Room atomicity claim. Storage failure is explicit and must not weaken blocking or silently reset state. Query recent history with bounded paging; no retention deletion until separately decided.

## Data model and API contracts

Preserve existing schedule package/time/ISO-weekday types and validate both native and UI input. Extend session persistence with versioned identity metadata and explicit migration tests. Define the opaque identity format and cross-date rollback rules in TASK-003 before callers rely on it.

Retain emergency schema/state transitions and consumed-session ledger unless tests establish a necessary compatible extension. A saved OVERRIDE_ACTIVE phase is a snapshot, never a reusable permission token. For inactive/no-configuration reads, the coordinator returns INACTIVE without inventing an active session identity; it still recovers an existing pending/active emergency record against its original identity with a zero morning bound where appropriate. Corrupt or unknown schemas never silently reset allowances.

Runtime snapshot should be a coherent native view with a version/generation or equivalent ordering signal so old responses cannot overwrite newer command/save results. Unavailable reason is orthogonal to the policy/emergency phase. Exact field names are an implementation detail within TASK-006; external behavior is fixed by the acceptance criteria. Internal monotonic deadlines need not be exposed to UI.

History schema/DAO and event contracts are defined in TASK-012 before ingestion. Policy/emergency persistence remains authoritative; Room is an event record, not a second authorization engine.

## Decisions, alternatives and trade-offs

- Keep native accessibility overlays and existing window metadata monitoring. A replacement full-screen activity would introduce background-launch questions and require fresh evidence. Do not add application overlay permission or usage access by default.
- Keep current-local-hours morning behavior. Preserving remaining morning duration would violate an accepted decision; emergency countdowns remain monotonic.
- Prefer explicit stable captured identity over deriving identity from mutable settings/current returned date. A deterministic representation is acceptable only if migration and rollback tests prove identical semantics.
- Keep separate existing DataStores with explicit coordination. Unifying stores is a larger migration and is unjustified unless the concurrency tests prove a concrete unresolvable gap; escalate that architecture change before implementation.
- Native emergency controls ensure UI-closed operation; React Native-only countdown/presentation cannot satisfy it.
- Prefer existing callbacks plus reconnection recovery until evidence requires lifecycle additions. Do not introduce exact alarms, wake locks, foreground services or new permissions speculatively.
- Use Room for history as required; no ad hoc JSON analytics store or network dependency. Compatible AndroidX/Kotlin tooling versions must be chosen and verified when adding it.
- Keep current supported-app catalog; installation discovery and broad package queries are unnecessary. Any later discovery needs targeted visibility and official-source review.

## Security, reliability and performance

Use only package/window metadata needed by monitoring; do not read/store node text or other apps' content. History stores event metadata locally. Accessibility disclosure/consent remains prominent and matches actual capabilities. Official Play accessibility and distribution rules must be reviewed before making any distribution claim; sideloading is the current development delivery context.

Serialize commands and configuration interactions without blocking the Android main looper. Bound native callbacks, retries, Room queries and event delivery work. Preserve cancellation/unbind/generation protections. Do not report permission enabled as equivalent to connected enforcement. Permissions can change while no process can observe them; report current status and evidence gaps honestly.

Do not promise force-stop recovery, unsupported multi-window behavior or manufacturer-independent reliability. Unknown foregrounds, delayed IO, stale callbacks, permission revocation, process teardown and clock changes require specific regression cases and physical measurements.

## Test strategy

For every behavior subtask: explain cases, write a meaningful failing test, run it for red, implement minimally, run green checks, refactor only with checks, record exact evidence in docs/TDD_LOG.md, then create a local commit. One subtask at a time. Documentation/manual gates use static/manual validation and do not invent red tests. Existing completed tests are retained rather than recreated.

Use JVM/file-backed DataStore tests for identity/context/coordination and persistence; injected clocks and barriers for 60-second/five-minute boundaries, stale responses, race conditions and reboot; Robolectric for service/overlay/bridge lifecycle; Jest for wrapper/screens/navigation behavior. Never sleep real emergency durations in unit tests.

Repository commands: npm test -- --runInBand; npm run typecheck; npm run lint; from android, .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug. Select Node 22.13+ on line 22 and JDK 17 first. Run targeted red/green tests, then appropriate integration checks once per completed change; add release/lint/signature checks at device-artifact checkpoints.

Physical gates distinguish agent-observed from user-reported results and record exact device/OS/app/build versions, actual attempts, interruption exposure/latency, native errors and limitations. Scheduling checkpoint follows SCHEDULING_TESTING.md. Emergency checkpoint verifies real 60-second wait, explicit confirmation, <=300-second access, morning cap, UI closure, same-boot process restart, reboot and foreground expiry. Final matrix includes repeated launcher/recents/notification/deep-link routes, revocation, offline, sleep/lock, multi-window, clock/zone changes and force-stop semantics.

## Migration, rollout and rollback

Planning baseline supplied by coordinator: local dev ec8193268be0e144f3bdbd21ee0ea6edd7c3f2b3; origin/dev c415df0eb40bbf5e43a2d3ba5aaa45ada8095be0, 20 local commits ahead; remote main 5e3d0be. Default remains dev. Read-only provider query returned no PRs in its 20-result window. This is not evidence of merged PRs for the existing local work.

Before PR-based implementation delivery, reconcile the exact local commit list and obtain explicit user choice/authorization for integrating the existing local baseline with origin/dev and publishing task branches. Do not reset, drop, squash, force-push, repoint default branch, alter protections, or publish automatically. A branch from stale origin/dev would omit implemented scheduling/emergency prerequisites. Keep .serena untouched. Baseline capture/checks and roadmap approval are coordinator prerequisites, not fictional completed roadmap tasks.

This revision has user approval recorded in roadmap.json. Task status and evidence are authoritative there. The user explicitly deferred the remaining scheduled-phone checkpoint details on 2026-11-24; no unconfirmed device result is marked passed. The user separately authorized task-branch pushes and PR creation through the skill on 2026-11-24; baseline integration remains unresolved. Keep local commits per repository rules; task integration/merge evidence must be real, and only the user merges under the selected skill.

Additive/versioned migrations preserve schedule, frozen identity and emergency consumed usage. Test migration and interrupted writes before installing upgraded artifacts. No destructive clearing on failures. Reverting application commits does not automatically downgrade new persistence schemas: keep forward-compatible readers where practical, otherwise prepare a tested data-preserving forward fix. History schema downgrades never use destructive migration fallback; do not uninstall/clear data as routine rollback.

Roll out local standalone APKs at scheduled, emergency and final checkpoints with source SHA, package/version, checksum and signing identity. Current debug-key signing remains local-test-only; production signing is a separate requested deliverable.

## Risks and unresolved questions

- Scheduled APK test details remain unverified. The separate 2026-11-24 user continuation decision defers this gate; preserve later reliability checks and raise concrete blocking device uncertainties when necessary.
- Exact supported phone model/Android/One UI and installed target versions remain unconfirmed. Proposed final evidence target is the user's actual Samsung device; additional OS/manufacturer support requires measured evidence.
- Baseline publication/integration is unresolved; no push/PR authority is inferred from planning.
- Native identity semantics across cross-date rollback/zone changes and legacy migration must be settled in TASK-003 without refreshing allowance. If solving it changes accepted product semantics, request a design revision.
- Validate lock order, suspended IO, inactive context and session-bound calculations against actual separate-store behavior before any live authorization.
- Verify exact current official APIs/permissions for overlay interaction, lifecycle broadcasts, boot identity reads and AccessibilityService operations; inspect current Google Play disclosure/consent policy only for accurate claims, not assumed eligibility.
- Reboot receiver/wakeup necessity is evidence-driven; requirements do not authorize claiming guaranteed recovery or adding intrusive permissions.
- Define attempt deduplication, observable session completion and history delivery semantics before ingestion. Unobservable revocation/downtime cannot be reconstructed perfectly.
- History retention/privacy export/deletion features are not specified. Proposed MVP retains app-private local metadata with bounded reads; a retention/deletion UX requires a product decision if requested.
- Real process-death/reboot/deep-sleep/multi-window gaps can require scoped remediation tasks. Do not mark final reliability complete while required failures remain unresolved or support limitations are unexplained.
