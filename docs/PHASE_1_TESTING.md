# Phase 1 — Physical-device validation

## Status

No physical Android device was connected during initial environment inspection. Samsung A34-family physical-device results are **pending**. Automated tests cannot establish whether an accessibility overlay reliably interrupts Instagram on the phone.

Local checks on 2026-10-28: nine Jest tests and eleven native JUnit/Robolectric tests passed; TypeScript, ESLint, standalone ARM64 release build, release vital lint, and APK signature verification passed. See [TDD record](TDD_LOG.md). The APK uses the public debug signing key for local testing.

Follow-up recovery checks: the native suite now contains eighteen passing tests. Missing foreground snapshots, temporary foreground-read exceptions, and failed presentation attempts receive bounded delayed retries. This improves recovery in simulated tests; device interruption remains unvalidated.

## Setup

1. Record exact phone model, Android version, One UI version, Instagram version, and build/commit under test. Find OS details in Settings → About phone → Software information.
2. Enable Developer options and USB debugging. Connect a data-capable USB cable and accept the phone's debugging prompt.
3. Run `& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l`. The phone must appear as `device`, not `unauthorized`.
4. Build and install using the README. Use the standalone build to validate offline use without Metro. If using debug, run Metro and `adb reverse tcp:8081 tcp:8081`.
5. Open Morning Blocker. Read the disclosure: accessibility window/package metadata is used to detect Instagram and display a blocking surface; no screen text is stored or sent.
6. Explicitly agree and open accessibility settings. On Samsung, locate the installed/downloaded apps or services section and enable Morning Blocker. Labels vary by One UI version. If Android restricts sideloaded accessibility access, follow Android's system prompts and review the app before allowing restricted settings; the prototype cannot grant itself access.
7. Return to Morning Blocker and verify **Service connected**. Permission enabled with service disconnected is not a successful setup.
8. Open Instagram. Expected: a native “Not right now.” screen identifies continuous prototype blocking. No unlock time or emergency access is claimed.
9. Choose Go Home. Verify Home works, then reopen Instagram. Use Accessibility settings to revoke access at any time.

## Repeatable test matrix

For each case, record attempts, successful interruptions, visible/interactable exposure before interruption, stuck overlays, unrelated-app interference, and logs. Start with 20 launches per launch route; record actual counts and any failures rather than treating that sample size as proof for all devices.

| Case | Expected result | Result |
| --- | --- | --- |
| Instagram launcher icon, 20 launches | One blocking surface each launch | Pending |
| Instagram from recents, 20 launches | Same interruption | Pending |
| Instagram notification/deep link, 20 launches | Same interruption | Pending |
| Go Home and reopen repeatedly | Home usable; Instagram blocks again | Pending |
| Open Settings, dialer, and unrelated apps | No blocker over these apps | Pending |
| Swipe Morning Blocker out of recents | Blocking continues if service remains connected | Pending |
| Stop Metro / disconnect network with standalone build | Setup and blocking work offline | Pending |
| Disable accessibility while blocked | Settings accessible; overlay removed; disconnected reported | Pending |
| Re-enable accessibility with Instagram already foreground | Native service reevaluates foreground | Pending |
| Lock and unlock the phone | Lock screen usable; foreground reevaluated | Pending |
| Open notification shade while blocked | System controls usable; no unrelated interception | Pending |
| Split screen / pop-up view / multi-window | Record interference and bypasses; unsupported until validated | Pending |
| Native process termination | Record service reconnection and gaps; no automatic recovery promise | Pending |
| Force-stop through Android settings | Report unavailable; do not expect automatic restart | Pending |
| Restart phone | Record service rebind behavior; boot recovery is not implemented in Phase 1 | Pending |

“UI closed” means dismissing the activity/swiping it from recents. Force-stop is a separate Android state and can prevent service execution. Do not conflate the two.

## Diagnostics

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" logcat -s MorningBlocker AndroidRuntime
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell dumpsys accessibility
```

The setup screen includes native status and a manual refresh. Report whether permission is enabled, the service is connected, and any native error. Do not include private screen content in test records.

## Known design limits

- This prototype cannot disable Instagram's package or guarantee an unbreakable restriction. Users retain settings, revocation, and uninstallation.
- Foreground identification and overlay presentation have a race; exposure before interruption must be measured.
- Window/package metadata may be missing or delayed. An unknown window does not automatically dismiss an existing overlay; Home and accessibility settings provide exit paths. Test for stuck overlays.
- Unresolved detection/presentation receives four delayed attempts over approximately 1.85 seconds, then waits for another relevant Android event. While blocking, foreground checks continue every 250 ms. These are recovery intervals, not guarantees about Android event delivery or blocking latency.
- The blocking surface covers the app area while retaining system navigation. Multi-window and OEM-specific surfaces require device validation.
- Accessibility permits system-managed service operation, but reconnect after process death, reboot, or battery management varies. There is no boot receiver or persistent scheduling in Phase 1.
- Service connection status is not a certification of reliable enforcement. Presentation errors must be reported.
- Emergency access, deadlines, schedule edits, reboot policy recovery, history, and time-change handling belong to later phases and remain unimplemented.

## Results template

```text
Date / build:
Phone model / Android / One UI:
Instagram version:
Route / lifecycle condition:
Attempts / successful interruptions:
Interaction possible before interruption:
Unrelated-app interference / stuck overlay:
Native status / errors:
Conclusion / unresolved issue:
```
