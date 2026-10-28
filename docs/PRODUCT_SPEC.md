# Morning Blocker — Product and implementation specification

This document consolidates the project handoff. The duplicated handoff describes one project and one set of requirements.

## Purpose

Help users honor an advance decision to avoid distracting social applications during a configured morning window. Interrupt selected apps within normal Android permissions, even when the React Native application is closed. Operate entirely offline. This is a personal digital-wellbeing tool, not device management.

## MVP behavior

Users select supported applications by Android package name, initially Instagram, YouTube, and Reddit. They configure a daily start time, end time, and active weekdays. Windows do not cross midnight. Only selected applications are interrupted; unrelated applications remain usable. Restrictions start and expire automatically.

On a restricted launch, detect the foreground package, evaluate native policy, and show a native blocking interface. The intended full-MVP wording is “Not right now.” and “Instagram is blocked until 9:00 AM.” with the actual app and unlock time, plus an emergency-access option. The proof of concept must identify itself as such and must not imply scheduling or emergency access already exists.

During a session, edits must not weaken its active policy. Save changes for the next eligible morning session. Users can always revoke permissions or uninstall through Android settings.

## Emergency access

1. Request access; restricted applications remain blocked for 60 seconds.
2. After the wait, require explicit confirmation. Elapsed wait alone does not grant access.
3. Unlock all selected applications for five minutes, capped at the current session end.
4. Resume blocking automatically, including when a selected app is already foreground.

Allow one completed override per session. Cancellation does not consume it. Kotlin owns persisted state and deadlines, using a monotonic clock within the current device boot. Closing the app does not reset a pending request; reopening does not extend access. Reboot cancels a pending request and conservatively terminates active access. A completed override remains consumed for the relevant session.

## Dashboard and history

Show restriction state, today's blocking window, selected apps, remaining time until normal access, required-permission status, and next session.

States: inactive, blocking, emergency wait pending, ready to unlock, temporary override active, and enforcement unavailable. Compute state from authoritative native data, not JavaScript countdown timers.

Local history records completed sessions, restricted-app opening attempts, emergency requests, completed overrides, and permission interruptions. Advanced analytics and cloud storage are excluded.

## Technical design

React Native with TypeScript owns the user interface. Add React Navigation when needed. Kotlin owns monitoring, policy evaluation, blocking presentation, persistence, emergency deadlines, lifecycle recovery, and the native bridge. Use DataStore for configuration and Room for history at the appropriate phase. Native enforcement must not require an active JavaScript runtime.

Keep foreground monitoring (`AppMonitorService`), pure policy (`RestrictionEngine`), presentation (`BlockController`), override logic (`OverrideManager`), persistence (`PolicyRepository`), reboot recovery (`BootReceiver`), and React Native integration (`BlockingModule`) modular. Names may follow project conventions.

Investigate AccessibilityService events such as `TYPE_WINDOW_STATE_CHANGED` and an accessibility-compatible blocking interface. Validate the actual interruption mechanism before treating it as reliable. Request display-over-other-apps or usage access only if the selected design needs it. Prefer targeted app queries. Add boot handling where needed without promising that a force-stopped app or disabled accessibility service can restart itself.

Android accessibility observes activity; it does not inherently disable another app. Overlays are bypassable through permission removal. Presentation and background-launch behavior vary by Android version and device. Validate Google Play accessibility disclosure, consent, and policy requirements. Never prevent access to settings, uninstallation, or permission revocation.

## Delivery phases

1. **Native proof of concept:** inspect/scaffold React Native Android support, detect Instagram, present a native interruption, and validate repeated launches on a physical device with the React Native UI closed. Provide install/setup/testing instructions and record limitations.
2. **Scheduling:** persisted schedules and packages, weekdays, boundaries, reboot handling, and future-effective edits.
3. **Emergency override:** persisted waiting and unlock deadlines, confirmation, expiry, per-session limit, process termination and reboot behavior.
4. **Interface:** minimal onboarding, permissions, dashboard, app selection, schedule editing, and history.
5. **Reliability:** repeated launches, UI closure, native process death, reboot, revocation, notification launches, recents, multi-window, clock changes, and expiry while a restricted app remains open.

Do not advance on the assumption that one displayed overlay proves enforcement. Distinguish compilation, automated tests, emulator observations, and physical-device results. Document unresolved limitations.

## Completion criteria

The full MVP permits app selection and recurring schedules, consistently interrupts selected apps during active windows on supported devices without the React Native UI, enforces a real wait and confirmed temporary access, automatically expires access, preserves active sessions against casual schedule edits, recovers or reports enforcement status after restart, clearly reports permission failures, and works offline.

## Out of scope

iOS, accounts, cloud synchronization, backend APIs, AI coaching, gamification, social features, subscriptions, browser blocking, automatic wake detection, advanced analytics, and complex habit tracking.
