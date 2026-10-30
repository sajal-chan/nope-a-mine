# Clock recovery — first subtask

## Current scope

The clock model and Android clock reader are implemented and tested. They are **not yet connected to PolicyRepository, DataStore, the accessibility service, or a boot receiver**. The existing repository still evaluates local date/time. This commit alone does not make persisted sessions resistant to clock changes or recover enforcement after reboot.

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

`SessionClockState` is an immutable checkpoint: original start/end instants and zone, boot count, elapsed anchor, precise remaining duration, absolute recovery deadline, and completion flag. `recover` returns a new checkpoint; it does not persist it. Its consumer must commit it durably before relying on its updated deadline or completion marker.

## Daylight saving time

- A nonexistent local boundary shifts forward by the gap, using Java time-zone resolution.
- A repeated local start uses its earlier occurrence; a repeated end uses its later occurrence. This forms one continuous window covering both copies of the repeated interval.
- If resolution collapses or reverses the window, skip that occurrence rather than creating a negative or overnight restriction.
- India half-hour offsets and ordinary non-transition dates are also covered by tests.

These are explicit application choices. They are not an assumption that all devices share the same time-zone database version.

## Next subtask

Persist checkpoints atomically with the frozen policy, validate and migrate old records, and recover them through the native repository. Tests must cover actual DataStore reopen, missing/partial clock metadata, concurrent recovery/edits, sticky completion after reopen, and clock-aware gating of future sessions. Boot/time-change receivers and service behavior are separate integration work; they must not attempt to reactivate force-stopped applications or disabled accessibility access.

## Limits

No offline consumer app has trusted time across a reboot if the wall clock was changed while it was not observing. Capping the recovered remainder prevents it from increasing beyond the checkpoint; it does not prove the elapsed real-world time during that gap. Physical Samsung restart/sleep/clock tests remain pending. Emergency-override reboot rules are not implemented here.

References: [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock), [Android boot count](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT), and [Java zone gap/overlap resolution](https://docs.oracle.com/javase/8/docs/api/java/time/ZonedDateTime.html).
