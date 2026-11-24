# Morning Blocker roadmap

<!-- Generated from roadmap.json. JSON is authoritative; regenerate this view after JSON changes. -->

Revision: 1 | Status: proposed; awaiting user approval

[Design](DESIGN.md) | [Authoritative task data](roadmap.json)

This plan covers the remaining MVP. The existing scaffold, scheduled blocking/configuration, and emergency rules/storage are already implemented. Physical scheduled-device validation remains pending.

Plan approval does not waive the phone checkpoint or authorize pushes. The 20 local commits ahead of origin/dev must be preserved and reconciled before PR delivery. Work remains sequential, with TDD and one local commit per completed subtask.

## Sequence

| Order | Task | Dependencies | Status |
| --- | --- | --- | --- |
| 1 | [TASK-001: Resolve the scheduled-blocking physical checkpoint](#task-001) | None | pending |
| 2 | [TASK-002: Verify Android contracts for the remaining integration](#task-002) | TASK-001 | pending |
| 3 | [TASK-003: Persist an immutable native session identity](#task-003) | TASK-002 | pending |
| 4 | [TASK-004: Connect trusted emergency context across native stores](#task-004) | TASK-003 | pending |
| 5 | [TASK-005: Apply recovered emergency grants to native enforcement](#task-005) | TASK-004 | pending |
| 6 | [TASK-006: Expose coherent native runtime snapshots and emergency commands](#task-006) | TASK-005 | pending |
| 7 | [TASK-007: Add the native emergency interaction to the blocking surface](#task-007) | TASK-006 | pending |
| 8 | [TASK-008: Reconcile emergency state through native lifecycle changes](#task-008) | TASK-007 | pending |
| 9 | [TASK-009: Verify the integrated emergency feature on the phone](#task-009) | TASK-008 | pending |
| 10 | [TASK-010: Organize minimal onboarding, permissions and settings navigation](#task-010) | TASK-009 | pending |
| 11 | [TASK-011: Show the native dashboard and emergency status](#task-011) | TASK-010, TASK-006 | pending |
| 12 | [TASK-012: Create local Room history storage and event contracts](#task-012) | TASK-009 | pending |
| 13 | [TASK-013: Record session lifecycle and blocked-app attempts](#task-013) | TASK-012, TASK-005 | pending |
| 14 | [TASK-014: Record emergency and observable permission events](#task-014) | TASK-013, TASK-008 | pending |
| 15 | [TASK-015: Display bounded local history](#task-015) | TASK-014, TASK-011 | pending |
| 16 | [TASK-016: Complete the MVP physical reliability matrix](#task-016) | TASK-015, TASK-009 | pending |
| 17 | [TASK-017: Prepare the final local MVP artifact and handoff](#task-017) | TASK-016 | pending |

## TASK-001

Resolve the scheduled-blocking physical checkpoint

Follow docs/SCHEDULING_TESTING.md on the standalone 1.1-scheduling artifact; preserve the prior Phase 1 acceptance and all still-unconfirmed cases. Record actual observations or an explicit user decision to defer this checkpoint before new application development.

**Acceptance criteria**

- Record artifact/source/version/hash, exact phone/OS/One UI/target versions where available, and whether each result is directly observed or user-reported.
- Record before-start usability, start/end while Instagram remains foreground, repeated entry, unrelated-app behavior, UI dismissal/offline, persisted configuration and edits retaining original frozen policy.
- Record permission revocation/re-enable and Settings accessibility; mark unrun cases pending.
- Any failure has an exact reproduction and continuation decision; new emergency implementation proceeds only after satisfactory checkpoint evidence or explicit user authorization to defer.

**Test plan**

- Manual device sequence in docs/SCHEDULING_TESTING.md; compare artifact identity before attributing results.
- Documentation checks preserve the original Phase 1 report and do not imply all reliability cases passed.
- Commit only actual test records/decisions; no manufactured TDD red for manual evidence.

**Rollback**

Revert only inaccurate documentation; preserve raw observations and pending cases. Do not clear app data or reinstall to conceal a failure.

## TASK-002

Verify Android contracts for the remaining integration

Review current official Android documentation for existing overlay/accessibility APIs and planned native emergency controls, elapsed-time/boot identity, service reconnects, broadcasts and storage. Record the selected mechanisms, permission requirements and evidence limits before code relies on them.

**Acceptance criteria**

- Document official URLs and access date for AccessibilityService/window events, TYPE_ACCESSIBILITY_OVERLAY interaction, SystemClock.elapsedRealtime, Settings.Global.BOOT_COUNT and any proposed lifecycle broadcasts.
- Confirm current selected permission set and explain why accessibility overlay and application overlay differ; keep settings/revocation/uninstall reachable.
- State lifecycle/deep-sleep/broadcast latency and manufacturer boundaries, and when a receiver is justified instead of assuming it can restart a disabled service.
- Review current official accessibility disclosure/consent and Google Play policy only to qualify claims; do not assert distribution eligibility.

**Test plan**

- Documentation review against actual manifest/accessibility config and native APIs; no behavior test needed.
- Check every platform/distribution statement has an official supporting source; escalate any requirement-changing API limitation before implementation.

**Rollback**

Revert documentation corrections only if superseded by verified evidence. No permissions or runtime components change in this task.

## TASK-003

Persist an immutable native session identity

Extend the captured frozen session model/persistence with a stable native identity and tested migration. Separate captured identity from local-date presentation so reopening, edits and clock/date/zone re-entry cannot create a fresh override allowance.

**Acceptance criteria**

- The same captured frozen session has the same identity across file reopen, current/pending edits, same-day rollback/re-entry and cross-date/zone rollback under existing frozen-policy rules.
- Only a genuine later eligible captured session rotates identity; edits, transient inactivity and current returned date never rotate it.
- Legacy policy data migrates without changing selections, effective date or frozen policy; identity representation and consumed-ledger compatibility are explicitly documented.
- Corrupt/future metadata fails explicitly; migration interruption never silently resets policy or allowance.

**Test plan**

- Explain identity/migration/rollback cases; write native model and real-file DataStore tests and run for red before adding behavior.
- Cover concurrent capture/save, no active configuration, old schema fixtures, partially written metadata and cross-date rollback.
- Run targeted green native tests plus existing PolicyRepository/ActiveSession/ClockRecovery/RestrictionEngine regression suites; record red/green commands in docs/TDD_LOG.md.

**Rollback**

Revert callers/model together only while persistence remains readable. If schema downgrade is unsafe, use a tested forward correction preserving identity/policy/usage; never clear data.

## TASK-004

Connect trusted emergency context across native stores

Introduce the minimal shared native coordinator/context provider using persisted captured identity, frozen policy, fresh wall clock/zone and Android monotonic/boot identity. Define serialization/lock order for configuration operations, emergency commands and enforcement.

**Acceptance criteria**

- EmergencyRepository samples trusted context inside its serialized edit; the context is revalidated after suspended policy IO and before any access authorization.
- Inactive/no-configuration evaluation returns INACTIVE without a synthetic active identity and recovers existing pending/active state with a zero remaining bound as required.
- Freeze/edit/re-entry/rollover and reopen retain the correct consumed allowance; remaining bound uses current local morning hours and cannot extend an existing grant.
- Concurrent save/request/confirm/status operations cannot deadlock or return a grant for an obsolete session; document lock order and separate-store consistency limits.
- Clock/boot/policy corruption or IO errors propagate without resetting usage or returning a grant.

**Test plan**

- Write failing integration tests with real temporary DataStores, injected wall/monotonic clocks and suspension barriers before coordinator implementation.
- Cover crossing morning end or date while an operation awaits IO, boot change, clock rollback/re-entry, command/save races, cancellation and failed persistence.
- Run green context/repository/clock/policy suites and record evidence; no real-duration sleeps.

**Rollback**

Disconnect unused coordinator factory/callers if not live. Preserve existing stores/ledger; unsafe schema rollback requires forward repair.

## TASK-005

Apply recovered emergency grants to native enforcement

Compose policy and recovered emergency state in the native restriction path and service callbacks; expire access with the React Native UI absent, including selected-app foreground and delayed IO.

**Acceptance criteria**

- WAITING and READY_TO_UNLOCK never allow restricted apps; only a fresh valid OVERRIDE_ACTIVE decision allows all frozen selected packages.
- Native evaluation caps grants at 300 seconds and current morning end and resumes blocking at expiry while the selected app stays foreground without JavaScript.
- Stale Allowed/Blocked responses are checked against current session, foreground, boot and both time bounds before presentation changes.
- Unrelated apps/settings remain usable; unknown foreground and failure paths preserve existing safeguards and report unavailable enforcement.
- Reconnect/session rollover conservatively recovers existing repository state and cannot reset a consumed grant.

**Test plan**

- Write red RestrictionEngine/service/gate cases using injected clocks and main-looper advancement.
- Cover wait boundary, ready-without-confirm, access deadline exact boundary, shorter/longer local window, foreground expiry, UI absence, stale IO, reconnect and storage/clock failure.
- Run green native enforcement/emergency suites and Android debug build; record evidence without claiming device reliability.

**Rollback**

Revert enforcement composition and its callers together; keep repository data and usage. An older build lacking emergency awareness must never be installed as if it preserves a current active grant.

## TASK-006

Expose coherent native runtime snapshots and emergency commands

Extend BlockingModule and the typed TypeScript wrapper with runtime snapshot and request/confirm/cancel commands using the same native coordinator. Keep unavailable status distinct from INACTIVE and preserve response ordering.

**Acceptance criteria**

- All commands derive identity/deadlines/usage natively, reject invalid transitions and return explicit native errors.
- Snapshot contains phase, enforcement availability/reason, frozen window/apps, deferred configuration, next eligible session, remaining display values, usage and allowed actions.
- Native calls remain correct across duplicate commands, async cancellation/invalidation and concurrent service evaluation.
- Ordering metadata or equivalent stale-response protection stops old reads from overwriting newer command/save results.
- Malformed/unavailable native bridge errors never claim success or allow confirmation.

**Test plan**

- Write red native bridge/codec/access tests plus Jest wrapper tests before implementation.
- Test every command forwarding/rejection, absent native module, permission/service unavailable, IO failure, duplicate request/confirm, stale reads and no JS timestamp fields.
- Run green native/JS suites, npm run typecheck and npm run lint; record red/green evidence.

**Rollback**

Revert both native and TS bridge additions together. Retain policy/emergency persistence and existing configuration APIs.

## TASK-007

Add the native emergency interaction to the blocking surface

Extend the native BlockController presentation with minimal request, waiting, ready confirmation, cancel and consumed-allowance states using coordinator commands and snapshots. Controls must function without React Native.

**Acceptance criteria**

- Blocked screen shows Not right now., actual app and normal unlock time; unused session offers emergency request.
- Request starts one fixed 60-second native wait, cancel preserves allowance, wait expiry enables an explicit confirmation action and never auto-unlocks.
- Confirmed grant removes interception only on valid native result; expired/used state cannot offer another completed grant.
- Closing/recreating the surface preserves native wait/access deadlines; duplicate taps and stale async results cannot extend/reset access or cover unrelated apps.
- Settings/Home remain accessible and command/presentation failures are visible.

**Test plan**

- Write failing Robolectric BlockController/service interaction tests before native control implementation.
- Advance injected/native test clocks for <60 s, =60 s, confirmation, cancellation/re-request, consumed usage, reopening and failed command commits.
- Run green presentation/service/bridge suites and assemble debug; record evidence. Physical behavior is TASK-009.

**Rollback**

Revert overlay controls while keeping consumed usage. Retain working Settings/Home and base blocking surface; do not clear an active grant by resetting persistence.

## TASK-008

Reconcile emergency state through native lifecycle changes

Complete recovery hooks for service reconnect, UI/native process restoration and time/zone changes using TASK-002's verified contracts. Add a narrowly scoped receiver only where required by demonstrated behavior; preserve native cleanup/stale-callback protection.

**Acceptance criteria**

- Same-boot restore preserves pending wait and fixed grant deadlines; no reopen operation extends them.
- New boot cancels waiting/ready and ends active access while keeping completed usage for the original captured identity.
- Current local-window exit clears pending/active timers and re-entry does not revive them; a genuine next session gets a fresh allowance.
- Service teardown cancels callbacks and late results; reconnect reevaluates saved configuration/current foreground without React Native.
- Document the selected callback/reconnect/receiver mechanism, why it is sufficient within stated limits, and any physical recovery gaps still pending.

**Test plan**

- Write red service/coordinator recovery tests with persisted reopen, simulated boot changes, pending IO and cancellation before adding hooks.
- Cover process-like recreation in every emergency phase, clock/time-zone exit/re-entry, permission/service disconnect, new-session rollover and no JS runtime.
- Run green native regression suites and manifest/static checks if receivers change; no simulated reboot is labeled a physical result.

**Rollback**

Revert only added lifecycle hooks with callers/manifest synchronized. Preserve existing conservative lazy recovery and saved usage; forward-fix incompatible persistence.

## TASK-009

Verify the integrated emergency feature on the phone

Build an identifiable standalone local-test APK and perform the emergency workflow with scheduling, UI closure, process/lifecycle events and offline use on the actual phone. Record observations before treating integration as reliable.

**Acceptance criteria**

- Record exact artifact/source/version/signing/hash and device/OS/target versions, distinguishing user-reported and directly observed checks.
- Observe a real >=60-second wait, explicit confirmation, <=300-second access for every selected app, session-end cap and reblocking while a selected app stays foreground.
- Check cancel/re-request, consumed limit, UI dismissal/reopen, same-boot native process recreation where possible, reboot wait/grant recovery and offline operation.
- Record unsuccessful/unrun cases, exposure and errors; carry reliability defects forward explicitly and obtain a continuation decision if this gate fails.
- Keep revocation/settings/uninstallation available and make no automatic force-stop restart claim.

**Test plan**

- Run appropriate full JS/native/static/debug checks and standalone build/signature verification before device testing; preserve actual output.
- Repeat the documented manual sequence with clocks and observed times; do not shorten production 60/300-second constants for the acceptance run.
- Manual test record and TDD_LOG artifact/build notes use real evidence; no artificial red test needed.

**Rollback**

Keep the last known local-test APK and data. Revert faulty code through isolated corrective work, preserve observations, and do not downgrade to an unreadable schema or erase consumed usage.

## TASK-010

Organize minimal onboarding, permissions and settings navigation

Add React Navigation routes for minimal onboarding/permissions, dashboard entry, existing app/schedule settings and later history. Reuse tested ScheduleSettings controls and the supported package catalog.

**Acceptance criteria**

- New setup presents accurate accessibility disclosure and explicit consent before settings; returning reports enabled and connected states separately.
- Existing Instagram/YouTube/Reddit, weekdays and same-day time validation/save behavior survives navigation and process reopen.
- Active frozen policy and future edits remain visibly distinct; navigating/resuming never auto-saves defaults or discards an unfinished draft without clear behavior.
- Permissions/settings remain reachable when unavailable; navigation does not authorize enforcement or require a JS runtime for blocking.
- Dependencies are minimal and verified compatible with existing React Native/React versions.

**Test plan**

- Write red navigation/user-flow tests covering first setup, returning setup, permission settings resume, configuration draft/deferral and error paths.
- Retain existing screen regression tests; check unmount/background refresh cancellation and stale saves.
- Run green Jest, typecheck, lint and Android assemble after adding native navigation dependencies; record red/green evidence.

**Rollback**

Revert routes/dependencies and entry component together while retaining the prior setup/configuration screen and native persistence.

## TASK-011

Show the native dashboard and emergency status

Implement the minimal dashboard from coherent native snapshots, showing current restriction/emergency state, effective window/apps, normal remaining time, next session, permission/service health and deferred edits.

**Acceptance criteria**

- All INACTIVE/BLOCKING/WAITING/READY_TO_UNLOCK/OVERRIDE_ACTIVE states and enforcement unavailable have clear native-backed presentation.
- Native-authorized request/confirm/cancel actions expose wait/ready/active/consumed behavior without accidental auto-confirmation.
- Today's effective window/selection, normal remaining time, next eligible session and pending-policy date are accurate across resume and refresh.
- Errors, stale responses, duplicate taps and disconnected service do not show enforcement or grant success.
- Refresh stops on background/unmount; any display interpolation cannot grant access or enable confirmation independently.

**Test plan**

- Write red component/user-flow tests for every phase, missing permission/disconnected/storage errors, command failures, stale snapshots, background/resume and next-session display.
- Run green Jest, typecheck and lint; use native snapshot integration tests for boundary/zone data rather than duplicating policy logic in JS.
- Record actual red/green evidence and verify minimal layout manually.

**Rollback**

Revert dashboard route/component while retaining working native controls, settings and bridge. No state migration or persistence reset.

## TASK-012

Create local Room history storage and event contracts

Introduce compatible Room dependencies, app-private schema/DAO and bounded native query interface. Define stable event/session IDs, occurrence versus observation time, attempt deduplication and delivery/failure semantics before connecting producers.

**Acceptance criteria**

- Schema represents observed session start/completion, selected-app entry attempts, emergency requests/completions and observable permission interruptions with stable event IDs and applicable package/session metadata.
- Document one attempt per distinct observed entry/re-entry, not per monitor poll; delayed completion retains scheduled occurrence and separate observation time.
- Idempotent insertion and bounded deterministic newest-first paging work across reopen; queries never block enforcement's main looper.
- Migration/error tests preserve data and report failures; history is never used as an authorization source.
- Define a minimal recoverable transition-delivery journal/acknowledgment strategy where authoritative DataStore commits must be retried into Room; explicitly document separate-store limits.

**Test plan**

- Write red DAO/file-backed database tests for insert/dedup/paging/reopen, failure and migration behavior before Room implementation.
- Test event contract/ID generation as behavior, including repeated native polling and clock rollback timestamps.
- Run green native tests, schema/static validation and Android build; record dependency choices/red-green evidence.

**Rollback**

Remove unused Room callers/dependencies only before capture is enabled. Once records exist, preserve the database and use a data-preserving forward migration if an older reader is incompatible.

## TASK-013

Record session lifecycle and blocked-app attempts

Capture native session start/completion and distinct selected-app blocking attempts into Room through the event contract, with idempotent recovery/retry and no polling duplicates.

**Acceptance criteria**

- Observed session start and completion produce one event each per identity/transition, including delayed recovery without claiming continuous prior observation.
- Repeated overlay refreshes and duplicate accessibility events for the same entry do not inflate attempts; leaving/re-entering or a new launch produces a distinct observed attempt.
- Event delivery survives reopen/retry without duplicates at the tested journal/Room boundaries.
- History write failure is explicit and cannot permit a selected app or reset policy; bounded retry does not block the main looper.
- Record no text/node content and no unsupported assertions about launch routes that were not observed.

**Test plan**

- Write red native integration tests for entry/re-entry, duplicate events/polls, unknown foreground, start/end, late recovery and event-write/reopen failures.
- Inject failure between authoritative transition/journal and Room insert/acknowledgment and prove retry idempotence.
- Run green history/service/policy suites and record red/green evidence; verify small local record samples on device when available.

**Rollback**

Disable producers/retry hooks together without deleting Room records or changing policy/identity. Preserve pending event intent for a later forward repair.

## TASK-014

Record emergency and observable permission events

Connect emergency request/completion and observable permission interruption/recovery events to local history, using native authoritative transitions and idempotent delivery.

**Acceptance criteria**

- Request and successful completion events reflect committed transitions once; duplicate commands/reopens do not create duplicate completions.
- Cancellation can be distinguished from a completed grant; failed confirmation commits never produce a successful completion.
- Reboot/session-end recovery and consumed-allowance behavior remain correct while delivery is interrupted/retried.
- Observed disconnect/revocation/reconnection and current unavailable status are recorded honestly; unseen changes are labeled as observed later or unknown rather than invented exact timestamps.
- History failure remains separate from authorization and is surfaced without changing emergency usage.

**Test plan**

- Write red transition/history tests for request/cancel/re-request/confirm, duplicate taps, failed commits, Room failures, reopen, boot recovery and permission observation gaps.
- Exercise event journal/Room failure boundaries and actual unavailable-status producer paths.
- Run green emergency/history/service suites; record evidence and update observable-event documentation.

**Rollback**

Disable added producers without deleting events or modifying used allowances. Retain original recovery and error reporting.

## TASK-015

Display bounded local history

Add native typed history reads and a minimal React Native history screen using bounded paging and clear event labels/times. Keep Room as local storage and preserve existing navigation.

**Acceptance criteria**

- Screen displays recorded sessions, blocked-app attempts, emergency requests/completions and permission interruptions with correct app/session/timing context.
- Empty, loading, error, paging/end and retry states are explicit; no fabricated records or misleading continuous-observation claims.
- Repeated requests/stale responses/unmount cannot reorder/duplicate visible rows or overwrite newer pages.
- History remains available offline; bounded reads do not stall monitoring and failures do not change enforcement.
- Event labels explain delayed observed completion or uncertain interruption timing when applicable.

**Test plan**

- Write red native query/bridge tests and Jest screen tests for ordering/paging/empty/error/retry/stale/unmount behavior.
- Run green JS/native history suites, typecheck and lint; manually inspect representative content and large enough paging fixture.
- Record red/green evidence and no advanced analytics scope.

**Rollback**

Remove history route/read APIs together while keeping stored records and event producers readable for a future compatible screen.

## TASK-016

Complete the MVP physical reliability matrix

Run broader scheduled/emergency/offline reliability checks on the exact supported device/build; reconcile deferred Phase 1 cases and classify support/limitations from observed evidence. Any code remediation requires its own test-first scoped subtask and recheck.

**Acceptance criteria**

- Record actual attempts/successes/exposure for launcher, recents, notification and deep-link entry; start with 20 per available route and mark unavailable routes pending with reason.
- Cover UI closure, native process termination, reboot, permission revocation/re-enable, Settings/unrelated apps, split-screen/pop-up, sleep/lock/deep sleep and manufacturer battery settings.
- Cover selected foreground at scheduled start/end and override expiry, saved edits, first in-window setup, clock/date/zone changes and full offline onboarding/configuration/emergency/history.
- Record exact app/device/OS/build versions and observed gaps; force-stop is documented as unavailable until user/system recovery, not treated as automatic restart.
- No known unresolved failure contradicts the declared supported MVP behavior; unsupported cases have explicit evidence-based limits or user-approved scope decisions.

**Test plan**

- Run current required JS/native/static/build checks once for the final integration candidate, then physical matrix with artifact identity.
- Preserve raw device/user reports separately from interpretation and keep unrun scenarios visible.
- Any required implementation fix starts with a red behavior reproduction, receives independent review and appropriate recheck; use a roadmap amendment if new task scope is required.

**Rollback**

Keep candidate and prior APKs/checksums and raw observations. Withdraw unsupported claims or candidate availability rather than erase failures; preserve data during any forward fix.

## TASK-017

Prepare the final local MVP artifact and handoff

Prepare an identifiable standalone Android local-test APK and final installation/disclosure/permission/repeatable-device instructions. Reconcile handoff, limitations and TDD evidence with actual final checks and history/migration behavior.

**Acceptance criteria**

- Final APK has recorded source SHA, package/version/ABI, checksum and signing identity; bundled JS works without Metro/network.
- Final required checks and actual physical evidence are linked with dates/results; historical prototype/scheduling evidence remains labeled correctly.
- README/HANDOFF explain setup, disabled/disconnected/unavailable states, clock bypass, frozen edits, 60-second confirmation/five-minute cap, reboot/force-stop limits and local history.
- Migration and rollback instructions preserve policy, identity/used allowance and history; no routine clear-data or downgrade instruction.
- Local commits/delivery status and still-unresolved publication/baseline prerequisites are stated honestly; task completion is not recorded as merged without provider evidence.

**Test plan**

- Use Node 22.13+ on line22/JDK17; run npm test -- --runInBand, npm run typecheck, npm run lint and android gradlew.bat :app:testDebugUnitTest :app:assembleDebug with recorded actual output.
- Run standalone release build, relevant lint/vital checks, APK metadata/signature/hash verification and final manual install/smoke against the exact artifact.
- Validate documentation links/commands and roadmap consistency; documentation/artifact work uses static/manual checks rather than invented red tests.

**Rollback**

Withdraw the final local artifact/handoff recommendation and retain the last verified compatible candidate. Revert documentation/build metadata only when accurate; preserve data and evidence and use forward corrections for schema issues.
