# Emergency-access state machine

## Current scope

EmergencyAccess remains pure Kotlin. EmergencyRepository now persists its transitions transactionally using Preferences DataStore, and AndroidOverrideClock reads the native monotonic clock and boot identity. Neither is connected to the live blocker or React Native UI yet. Tests use real temporary files and simulated clock/boot inputs; no real time is slept. The installed prototype does not offer emergency access yet.

## Inputs and state

- EmergencyContext supplies a stable native session ID, elapsed realtime and boot count, and remaining milliseconds in the current morning window. Zero remaining means outside that window.
- EmergencyState records phase, completed-override usage, boot identity, last observed elapsed time, and waiting/access deadlines.
- advance must run before relying on a saved phase. A stale OVERRIDE_ACTIVE value alone must never authorize access.
- The session ID must come from the persisted native session identity, not JavaScript, editable settings, or a newly generated value on each reopen. Only a genuine new session may reset the allowance.

Morning hours continue to follow local wall time. Emergency countdowns use monotonic elapsed time. The native caller must recalculate the morning-window bound for each evaluation; a longer bound cannot extend a previously granted access deadline.

## Behavior

| Action/event | Result |
| --- | --- |
| Request during blocking with unused allowance | WAITING with a fixed 60-second deadline. |
| Waiting deadline reached | READY_TO_UNLOCK; apps remain restricted. |
| Explicit confirmation when ready | OVERRIDE_ACTIVE for at most five minutes, capped at remaining morning time; mark allowance used. |
| Cancel waiting/ready request | Return to BLOCKING without consuming allowance. A new request starts a full wait. |
| Repeat request/confirmation | Do not reset the wait or extend active access. |
| Access deadline reached | Return to BLOCKING; used flag remains true. |
| Morning window ends | INACTIVE; clear timers and retain any used allowance. Re-entry does not revive a wait/override. |
| Same-boot restored state | Preserve the original deadlines. |
| Different boot during wait/ready | Cancel request; do not consume allowance; require a fresh wait. |
| Different boot during active access | End access and retain used allowance. |
| Genuine new session ID | Fresh allowance and no inherited timers. |

Early/unsolicited confirmations, requests outside a session, and a second completed override are rejected with IllegalStateException. Negative clock values, same-boot elapsed regression, inconsistent saved fields, out-of-range deadlines, and deadline overflow are rejected with IllegalArgumentException. Canceling active access cannot restore its used allowance.

## Persistence and native clock

EmergencyRepository exposes suspend status/request/confirm/cancel commands. It accepts a trusted native suspend context provider, sampled inside the DataStore edit rather than before waiting for another command. Do not capture a one-time clock reading in that provider; sample AndroidOverrideClock and compute the current morning bound on each invocation. The context must be tied to the persisted frozen session, not supplied by JavaScript.

The application factory `emergencyRepository(context, provider)` shares one `emergency_access` DataStore. Schema 1 persists phase, session identity, boot count, elapsed sample, optional deadlines, usage flag, and a small append-only set of consumed session IDs. Returning to an older ID cannot regain a consumed allowance, even after visiting another session and reopening storage. No analytics/history UI is added.

Recovery and commands are atomic. Required boot/window recovery is committed even when a command is rejected with IllegalStateException; that rejection is thrown after the recovery commit. IO, context/clock failures, corrupt state, future schemas, or elapsed regression do not silently reset usage. A failed confirmation commit returns an error and cannot return an access grant. Deadline fields and the usage set are validated together.

AndroidOverrideClock reads [SystemClock.elapsedRealtime](https://developer.android.com/reference/android/os/SystemClock), which includes deep sleep, and [Settings.Global.BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT). Boot identity is reread each sample. Missing, unreadable, or negative boot identity reports IOException rather than guessing. Only emergency operations depend on that reader; morning schedules still use wall time/current zone. No permissions or dependencies were added.

## Verification and remaining integration

The new tests cover 25 file/transaction cases, five Android clock-reader cases, and one shared-store factory case. They verify real close/reopen, concurrent requests/confirmations, cancellation, simulated boot changes, corruption, IO failures, usage retention, and session-end caps. Clock tests run under Robolectric API 34. These are not actual device process-kill, deep-sleep, or reboot results.

A returned phase is a time-specific snapshot. Re-evaluate with a fresh native clock before authorizing access; do not treat a previously returned OVERRIDE_ACTIVE value as timeless authority. Connecting the context provider to PolicyRepository's frozen session, native UI/bridge, foreground enforcement, boot/time-change events, and physical Samsung testing remains separate work. Recovery currently occurs lazily when a native repository command/status call runs; no receiver has been added.
