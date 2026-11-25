# AGENTS.md — clock/ (':clock')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `clock/` + allowed shared modules. Do not root-scan.

_Install: ./install clock (dev by default)._
- Gradle: :clock / dir: clock/
- Package roots present: ui, data, platform, intents, service, widget
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:widgets, :library:room
- Metadata: metadata_data/clock.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline clock — alarms (exact, survive reboot pre-unlock), timer, stopwatch, world clock, analog widget; answers system alarm/timer intents so assistants can set them.
**Key features:**
- Alarms with full-screen lockscreen surface + sound service; timer + stopwatch controllable from notifications
- World-clock city picker; analog Glance widget (`AnalogClockGlanceWidget` + `AnalogClockWidgetProvider` receiver)
- Assistant `intents/` (`SetAlarm` / `SetTimer`) wired to the same schedulers as the UI
**Architecture / data-flow:** `ui/` tab pages → `platform/ClockViewModel` (+ `ClockUiContract`) → `data/ClockRepository` over Room; scheduling via `platform/AlarmScheduler` → `AlarmReceiver` → `AlarmActivity` + `service/AlarmSoundService`, re-armed by `BootReceiver`. Timer/stopwatch actions arrive from notifications via `*ActionReceiver`s.
**Entry points + reading order:** `Route` (alarm/timer/stopwatch/world-clock tabs) → `MainActivity.kt` → `Navigation.kt` → `ui/ClockTabs.kt` → `platform/ClockViewModel.kt` → `platform/AlarmScheduler.kt`.
**Gotchas:**
- Alarm scheduling must work before first unlock — test the boot path, not just in-app
- Notification action receivers and the sound service are the timer/stopwatch control plane; UI pages are views onto the same state
- The filename is `TimerReciever.kt`, spelled as-is — do NOT rename (receivers are manifest-registered)

## Key files
### Root — entry + navigation
- com/vayunmathur/clock/MainActivity.kt
- com/vayunmathur/clock/Navigation.kt
- com/vayunmathur/clock/Route.kt
### data/ — Room store
- com/vayunmathur/clock/data/ClockDatabase.kt
- com/vayunmathur/clock/data/ClockRepository.kt
### platform/ — ViewModel, schedulers, receivers
- com/vayunmathur/clock/platform/ClockViewModel.kt
- com/vayunmathur/clock/platform/ClockUiContract.kt
- com/vayunmathur/clock/platform/AlarmScheduler.kt
- com/vayunmathur/clock/platform/AlarmReceiver.kt
- com/vayunmathur/clock/platform/AlarmActivity.kt
- com/vayunmathur/clock/platform/BootReceiver.kt — re-arm alarms on boot
- com/vayunmathur/clock/platform/NotificationUtils.kt
- com/vayunmathur/clock/platform/StopwatchActionReceiver.kt
- com/vayunmathur/clock/platform/StopwatchNotificationHelper.kt
- com/vayunmathur/clock/platform/TimerActionReceiver.kt
- com/vayunmathur/clock/platform/TimerReciever.kt — spelled as-is, do not rename
- com/vayunmathur/clock/platform/WorldClockCities.kt
### service/ — alarm sound
- com/vayunmathur/clock/service/AlarmSoundService.kt
### ui/ + ui/components/ + ui/dialogs/ — tab pages and controls
- com/vayunmathur/clock/ui/ClockTabs.kt
- com/vayunmathur/clock/ui/ClockPage.kt
- com/vayunmathur/clock/ui/ClockScreen.kt
- com/vayunmathur/clock/ui/AlarmPage.kt
- com/vayunmathur/clock/ui/AlarmScreen.kt
- com/vayunmathur/clock/ui/AlarmSettingsPage.kt
- com/vayunmathur/clock/ui/TimerPage.kt
- com/vayunmathur/clock/ui/TimerScreen.kt
- com/vayunmathur/clock/ui/StopwatchPage.kt
- com/vayunmathur/clock/ui/StopwatchScreen.kt
- com/vayunmathur/clock/ui/InitialPermissionsScreen.kt
- com/vayunmathur/clock/ui/components/AlarmCard.kt
- com/vayunmathur/clock/ui/components/AlarmOptionControls.kt
- com/vayunmathur/clock/ui/components/AlarmRingingScreen.kt
- com/vayunmathur/clock/ui/components/KeypadButton.kt
- com/vayunmathur/clock/ui/components/KeypadRow.kt
- com/vayunmathur/clock/ui/components/LapRow.kt
- com/vayunmathur/clock/ui/components/TimerCard.kt
- com/vayunmathur/clock/ui/components/TimerKeypadContent.kt
- com/vayunmathur/clock/ui/components/TimerNotification.kt
- com/vayunmathur/clock/ui/components/TimeUnitDisplay.kt
- com/vayunmathur/clock/ui/dialogs/NewAlarmDialog.kt
- com/vayunmathur/clock/ui/dialogs/NewTimerDialog.kt
- com/vayunmathur/clock/ui/dialogs/SelectTimeZonesDialog.kt
### intents/ + widget/ — assistant hooks and widgets
- com/vayunmathur/clock/intents/SetAlarmIntent.kt
- com/vayunmathur/clock/intents/SetTimerIntent.kt
- com/vayunmathur/clock/widget/AnalogClockGlanceWidget.kt
- com/vayunmathur/clock/widget/AnalogClockWidgetProvider.kt

## Verify (this module only)
```
./gradlew :clock:compileDevKotlin
./gradlew :clock:lint
./gradlew :clock:checkMetadata
```
