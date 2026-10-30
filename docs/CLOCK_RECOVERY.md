# Clock recovery

## Current scope

The clock model, Android reader, and atomic DataStore checkpoint/recovery path are implemented and tested. They are **not yet connected to the accessibility service or boot/time-change receivers**. Recovery occurs when the native repository is called; automatic enforcement restoration after reboot has not been validated or implemented as a receiver workflow.

## Proposed behavior

Active-duration behavior was raised with the user and is provisional pending feedback. Tests currently preserve an active session's remaining duration across observed wall-clock/time-zone changes. Future sessions resolve using the phone's current zone.

| Situation | Clock-model behavior |
| --- | --- |
| Process restart in the same boot | Subtract elapsed realtime since the checkpoint. |
| Wall clock moves forward/backward | Keep the duration based on elapsed realtime; update the absolute reboot fallback. |
| Time zone changes | Retain the active session's original resolved window; use the new zone for future resolution. |
| Deep sleep | Count it through Android elapsed realtime, rather than uptime. |
| Different boot count | Recover against the last absolute fallback, capped at the checkpoint's remaining duration. |
| Completed checkpoint | Never revive it after rollback or reboot. |
| Same boot with elapsed time moving backward | Report an error rather than guessing. |
| Boot count missing or unreadable | Android reader throws IOException. |

`SessionClockState` is an immutable checkpoint: original start/end instants and zone, boot count, elapsed anchor, precise remaining duration, absolute recovery deadline, and completion flag. Pure `recover` returns a new checkpoint. PolicyRepository commits that checkpoint with the frozen policy before returning from a successful evaluation/edit.

## Daylight saving time

- A nonexistent local boundary shifts forward by the gap, using Java time-zone resolution.
- A repeated local start uses its earlier occurrence; a repeated end uses its later occurrence. This forms one continuous window covering both copies of the repeated interval.
- If resolution collapses or reverses the window, skip that occurrence rather than creating a negative or overnight restriction.
- India half-hour offsets and ordinary non-transition dates are also covered by tests.

These are explicit application choices. They are not an assumption that all devices share the same time-zone database version.

## Persistence and migration

The repository's production API takes ClockReading, not LocalDateTime. It recovers any active checkpoint before considering a new local-date session, so crossing zones cannot replace a running session. Pending edits, clock recovery, and completion are transactional. Optional schema-1 clock fields are validated as a complete group; partial/invalid groups raise CorruptionException and cannot be overwritten by ordinary saves.

Legacy snapshots have no recorded original zone or monotonic anchor. They are resolved once using the supplied current zone and then checkpointed. If this cannot produce a valid started window, evaluation reports an error instead of discarding the snapshot. Historical clock changes before that migration cannot be inferred.

DataStore tests close/reopen real files and cover clock jumps, zone changes, simulated boot-count changes, persistent completion, concurrent edits/recovery, legacy records, corrupt metadata, and DST transitions. Simulated inputs are not physical-device reboot tests.

## Remaining integration

Boot/time-change receivers and service behavior are separate integration work; they must not attempt to reactivate force-stopped applications or disabled accessibility access. Service integration should keep frequent countdown calculations in memory and checkpoint at recovery/clock/policy boundaries rather than blindly writing DataStore on every 250 ms foreground poll.

## Limits

No offline consumer app has trusted time across a reboot if the wall clock was changed while it was not observing. Capping the recovered remainder prevents it from increasing beyond the checkpoint; it does not prove the elapsed real-world time during that gap. Physical Samsung restart/sleep/clock tests remain pending. Emergency-override reboot rules are not implemented here.

References: [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock), [Android boot count](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT), and [Java zone gap/overlap resolution](https://docs.oracle.com/javase/8/docs/api/java/time/ZonedDateTime.html).
