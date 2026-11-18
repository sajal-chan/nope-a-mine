# Scheduled-blocking phone test — 1.1-scheduling

## Installation checkpoint

This is the next user-performed checkpoint. Automated native/JS tests and a standalone build do not establish actual scheduled enforcement on the phone. No results for this build have been observed on a physical device yet.

- Application source: `cd6a27c`.
- Android package: `com.morningblocker`; versionCode **2**, versionName **1.1-scheduling**.
- APK: `android/app/build/outputs/apk/release/morning-blocker-1.1-scheduling-arm64.apk` (ARM64, bundled JavaScript; no Metro needed).
- SHA-256: `e0f88e16afcde582a5e308871e2963883a4ab14ab066ee1c0455e68690e4bcb2` (18,242,834 bytes, built 2026-11-18).
- Transfer the APK to the phone and open it to install the update over the existing Morning Blocker. A laptop/USB connection is optional. Check the version in Android app information if uncertain which APK was installed.
- The package/signing identity is retained and the version code is increased. Android's [versioning](https://developer.android.com/studio/publish/versioning) and [signing](https://developer.android.com/studio/publish/app-signing#considerations) requirements were checked. The artifact uses the same local-test Android debug certificate; production distribution signing remains unconfigured.

Open Morning Blocker, review the disclosure and verify **Service connected**. If disabled, use the consent/settings action to enable accessibility. If enabled but disconnected, inspect Android accessibility settings and try disabling/re-enabling the service. Do not treat the enabled switch alone as successful enforcement.

**Unlike the old prototype, no saved configuration means no blocking.** The screen now has Instagram/YouTube/Reddit choices, weekdays, 24-hour start/end fields and **Save schedule**. Apps need not be installed to appear in the supported list. Emergency access and history are not available yet.

## First combined test

Choose a same-day window beginning about **two minutes from now** and ending about **eight minutes from now**. For example, if the phone reads 08:03, use 08:05–08:11. Do not cross midnight. Leave enough time to complete the checks before the original end.

1. Select **Instagram only**, select today's weekday, enter the future start/end in `HH:mm`, and tap **Save schedule**. Expect a successful save and no active session before the start. Close/reopen Morning Blocker and check the saved values remain.
2. Open Instagram before the start; it should remain usable. Leave it foreground as the window starts. Expect the native **Not right now.** screen with **Instagram** and the original scheduled unlock time. Record any exposure/interaction before interruption; callback intervals are not latency guarantees.
3. Use **Go Home** and open unrelated apps, including unselected YouTube/Reddit if installed. They should remain usable. Reopen Instagram repeatedly and through recents; record actual attempts and successful interruptions.
4. Swipe Morning Blocker away from recents, then try Instagram again during the same window. Repeat offline. Native enforcement should not require the React Native screen, network, Metro or a laptop.
5. Reopen Morning Blocker during the active session. Change the editable selection to **YouTube only**, keep valid times, and save. Expect **Changes apply from** a later date, while **Active session** still lists the original Instagram window/selection. Instagram should still block; YouTube should still be usable today. Saving must not shorten the current session or switch its selected apps.
6. Return to Instagram's blocking screen and leave it foreground through the **original end**. Expect the overlay to disappear without requiring another app launch or a React Native screen. Instagram should then be usable. Reopening Morning Blocker should show the saved future selection with no active session once refreshed.
7. Confirm Android accessibility settings remain reachable and disabling the service removes blocking. Re-enable as needed. Note that edits after a session starts stay deferred even after that session has ended; repeatedly editing the same day's window is not a way to restart or bypass it.

If the first setup is saved inside the chosen window, the session starts immediately rather than waiting for tomorrow. Empty apps or weekdays disable future blocking, but cannot cancel an already active frozen session. Uninstallation/revocation remain available; this app does not promise unbreakable blocking.

## Record results

```text
Date / APK version:
Exact phone model / Android / One UI / Instagram version:
Selected apps / weekdays / original start and end:
Initial save and reopen:
Before start / start with Instagram already foreground:
Launch route / attempts / successful interruptions:
Visible or interactable exposure before interruption:
UI dismissed / offline:
Deferred edit message / original selection remained enforced:
Original end while Instagram stayed foreground:
Unrelated-app interference / stuck overlay / native errors:
Permission revocation/re-enable:
```

For failures, record the exact step and on-screen error; do not clear data or reinstall first, since that discards relevant saved state. Settings remain the exit path if an overlay is stuck. USB diagnostics are optional; the logcat commands in PHASE_1_TESTING.md still apply.

## Deferred reliability work

Notification/deep-link entry, notification shade, split screen/pop-up view, sleep/lock transitions, process termination, force-stop, reboot, clock/time-zone changes and manufacturer battery management still need broader device verification. The initial prototype acceptance did not mark these scenarios as passing for scheduled enforcement. There is no boot receiver or wake alarm; see CLOCK_RECOVERY.md for native callback/lifecycle limits. Emergency-access expiry tests belong to a later build after that feature is integrated.
