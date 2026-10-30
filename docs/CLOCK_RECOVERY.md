# Morning clock behavior

## Confirmed product decision

Morning restrictions follow the phone's **current local hours and time zone**. They do not preserve a remaining duration. First-time setup inside an active window starts immediately.

| Situation | Behavior |
| --- | --- |
| Clock moves outside the configured hours | Unblock. |
| Clock moves back inside the hours | Block again using the same frozen policy. |
| Time zone changes | Evaluate the new local date, weekday, and time. |
| Process/device restarts | Re-read the saved policy and current local time on the next native evaluation. |
| DST jumps forward | Block if the new displayed local time is within the hours; a window wholly skipped by the jump never activates. |
| DST repeats an hour | Evaluate each occurrence directly; e.g. 01:50 is outside a 01:15–01:45 window, while a later return to 01:20 is inside. |

Start is inclusive and end is exclusive. A session's captured apps/times remain frozen against policy edits; re-entering the window must not activate pending edits. New policy dates are still deferred after a session has begun.

## Implementation and migration

ClockReading contains an Instant and ZoneId. AndroidSessionClock reads current wall time and re-reads the zone for each sample. Morning scheduling needs neither uptime nor a boot identifier. PolicyRepository evaluates local schedule rules and persists configuration, effective dates, and frozen policy snapshots.

The previous duration-preserving policy was superseded by the user's decision. Its `clock_*` checkpoints are ignored, including incomplete/invalid duration fields, and removed on the next native evaluation/save. Saved configuration and session records remain validated and preserved. Unknown policy schemas or corrupt policy/session fields still report errors.

Historical TDD entries describe the old behavior at those commits. The latest entry records the local-hours replacement and retirement of obsolete duration tests.

## Scope and limits

Manual clock changes can bypass morning restrictions; this is accepted for the MVP. No remaining-duration preservation, sticky expiry marker, or trusted-time workaround is used for morning hours.

Emergency access is a separate feature: its 60-second wait and five-minute access period must still use persisted monotonic deadlines and explicit reboot handling when implemented.

Service integration, boot/time-change receivers, and physical Samsung testing remain pending. Repository reopen tests are not claims of actual device reboot or enforcement validation.
