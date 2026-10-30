# Emergency-access state machine

## Current scope

EmergencyAccess is pure Kotlin. It has no Android, React Native, storage, or timer dependency. Tests advance explicit clock inputs; no real time is slept. The installed prototype does not offer emergency access yet.

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

## Next subtask: persistence

Persist state and command transitions atomically in Kotlin/DataStore before exposing access to the UI or service. Restore and advance it with a native monotonic clock and reliable boot identity. IO/corruption must surface as errors, never reset state to unused. Keep the used flag for the relevant session across reboot.

File-based tests should cover close/reopen without deadline changes, one-use limits under concurrent commands, cancellation/retry, boot recovery, corruption, and session-end truncation. The current tests use immutable state copies and simulated boot IDs; they do not prove process-death persistence or actual device reboot behavior. Live enforcement and Samsung validation remain pending.
