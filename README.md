# FocusTrack & DayFlow 📱⏱️🌱

An intelligent native Android application combining **Real Device Usage Tracking**, **Smart Pop-Up Reminders**, **DayFlow Daily & Tomorrow Planner**, and an **On-Device AI Digital Wellbeing Coach**.

---

## 🌟 Key Features

### 1. Real Phone Usage Tracking (Section 1)
- **Native UsageStats & UsageEvents**: Connects directly to Android's `UsageStatsManager` and `UsageEvents`.
- **Event Chronology & Reconstructed Sessions**: Tracks screen-on (`SCREEN_INTERACTIVE`), screen-off (`SCREEN_NON_INTERACTIVE`), phone unlocks (`KEYGUARD_HIDDEN`), app foreground starts (`ACTIVITY_RESUMED`), and app switches.
- **Metrics**: Total daily screen-on time, screen-off time, estimated phone-free intervals, launch counts, average session durations, and longest phone-free breaks.
- **Samsung Wellbeing-Inspired Dashboard**: Circular progress ring with customizable daily budget (default 3h), glanceable session counts, and live application progress indicators.
- **Privacy First**: All queries execute 100% locally on-device. No data is ever transmitted to remote servers.

### 2. Smart App Time Limits & Alerts
- Set daily usage limits for any application (e.g., 15m, 30m, 45m, 60m or custom slider).
- Configurable **80% warning threshold**: alerts the user before the limit is fully consumed.
- High-importance notification warnings when approaching and reaching daily quotas.

### 3. Smart Pop-Up Reminders with Actionable Heads-Up Banners (Section 2)
- **Heads-up Alerts**: Dispatched via high-priority notification channels (`NotificationManager.IMPORTANCE_HIGH`) with custom sound and vibration patterns.
- **Interactive Action Buttons**:
  - **Done**: Confirms activity completion, logs record in Room database, advances recurring schedule.
  - **Snooze**: Reschedules alert by configurable snooze interval (e.g., +10 minutes).
  - **Skip**: Acknowledges missed or skipped event without falsely inflating completion streaks.
- **Ready-Made Templates**: Hydration Routine (every 30m), Anti-Scrolling Pause (every 25m), 20-20-20 Eye Rest, Desk Stretch, and Study Pomodoro.
- **Natural Language Quick Parser**: Type `"Drink water every 30 minutes"` to instantly configure repeating alerts.

### 4. DayFlow Daily & Tomorrow Planner (Section 3)
- **Scheduled Tasks & Flexible Checklists**: Time-block study, exercise, and assignments or maintain a flexible checklist.
- **Conflict Detection**: Automatically detects and warns when scheduled tasks overlap.
- **Tomorrow Planner Mode**: Dedicated view to plan the next day in advance.
- **One-Tap Carry-Over**: Move unresolved or missed tasks to tomorrow with a single tap.
- **11:00 PM Daily Planning Reminder**: Configurable evening notification to plan the upcoming day.
- **Outcome Statuses**: Explicit controls for Done (✓), Missed (✗), Skipped, and Pending with undo capabilities.

### 5. Pomodoro Focus Mode & AI Wellbeing Coach
- **Focus Timer**: 25/5 Pomodoro, 50/10 Sprint, or custom timer with live circular countdown ring.
- **Sound & Notification**: Plays chime and shows completion notification on finish.
- **On-Device AI Habit Coach**: Analyzes peak hours, rapid check-in micro-habits, goal completion rates, and generates achievable micro-challenges.
- **Report Export**: Instant plain-text / CSV export and share capabilities.

---

## 🔒 Permissions & Android Capabilities

| Permission | Purpose |
|---|---|
| `PACKAGE_USAGE_STATS` | Queries real app usage and UsageEvents chronologically |
| `POST_NOTIFICATIONS` | Displays heads-up pop-up alerts (Android 13+) |
| `SCHEDULE_EXACT_ALARM` | Precise reminder timing for time-sensitive tasks |
| `RECEIVE_BOOT_COMPLETED` | Restores scheduled alarms automatically after device restart |
| `VIBRATE` | Haptic feedback for notifications and focus alerts |

---

## 🏗️ Architecture & Technology Stack

- **Language**: Kotlin 2.2.10
- **UI Toolkit**: Jetpack Compose with Material 3 (M3)
- **Database**: Room 2.7.0 (with KSP code generation)
- **Preferences**: Jetpack DataStore Preferences
- **Scheduling**: Android `AlarmManager` with `BroadcastReceiver` and `PendingIntent` actions
- **Navigation**: Navigation Compose with animated tab switching
- **Design**: Material 3 edge-to-edge layout, dynamic theme support, and custom adaptive app icon
