# react-native-app-icon-switcher

Change your React Native app's launcher icon at runtime on iOS and Android: from a button, from a push notification, or on a schedule. The library never closes the screen the user is looking at, and it works on the old and the New Architecture.

- **Does not crash or close your app when the icon changes, unlike other icon libraries** 🙂
- **One API for both platforms.** `setAppIcon('Holiday')`, `setAppIcon(null)` for the primary icon, `getAppIcon()`, `useAppIcon()`.
- **Changes that wait for a safe moment.** A change requested when it cannot be applied yet is stored and applied later, even after the app was killed. On Android that is when the app leaves the foreground. On iOS it is the next time the app becomes active.
- **Triggers from outside the app.** A push payload such as `{ "flexAppIcon": "Holiday" }` can be handled from JavaScript or from native code that runs without JavaScript (a `FirebaseMessagingService` or an iOS `AppDelegate`). A schedule can switch icons on its own: on Android even when the app is not running, and on iOS the next time the app is opened.
- **No crashes from setup mistakes.** Android component names come from the installed manifest instead of being built from the application id, and setups that would disable the running activity are refused with a clear error.
- **No private APIs.** It uses only public APIs, so it is safe for App Store review.

## Install

```sh
npm install react-native-app-icon-switcher
cd ios && pod install
```

The iOS side has been run on an iOS 18 simulator with React Native 0.88 (New Architecture). The Android code has been compiled and run on an Android emulator; see [Compatibility](#compatibility).

You need a development build or a bare app. Expo Go cannot load the native module: there every call reports `supported: false` or rejects with `E_NOT_LINKED`.

## Setup

Icons must be part of the app binary on both platforms. Neither iOS nor Android can download a new launcher icon at runtime.

### iOS

1. In `Images.xcassets`, add one App Icon set per alternate icon, for example `Holiday` and `Dark`. With Xcode 14 or later, a single 1024×1024 image per set is enough.
2. In the app target's build settings, set:
   - **Alternate App Icon Sets** (`ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES`) to `Holiday Dark`.
   - **Include All App Icon Assets** (`ASSETCATALOG_COMPILER_INCLUDE_ALL_APPICON_ASSETS`) to `Yes`.

The set names are the icon names used in JavaScript. Xcode then writes `CFBundleAlternateIcons` into the built `Info.plist`, and that is where the library reads the available icons. A manual `CFBundleIcons` > `CFBundleAlternateIcons` entry in `Info.plist` also works.

### Android

Android has no icon API. Each icon is a launcher `<activity-alias>`, and the library enables exactly one of them.

1. Add the icon images to `res/mipmap-*`, for example `ic_launcher_holiday.png` and `ic_launcher_holiday_round.png`.
2. In `android/app/src/main/AndroidManifest.xml`, **remove the `MAIN`/`LAUNCHER` intent filter from `MainActivity`**. Then add one alias per icon. Exactly one alias, the primary icon, must have `android:enabled="true"`:

```xml
<activity
  android:name=".MainActivity"
  android:launchMode="singleTask"
  android:exported="true"
  ... />

<activity-alias
  android:name=".MainActivityDefault"
  android:targetActivity=".MainActivity"
  android:icon="@mipmap/ic_launcher"
  android:roundIcon="@mipmap/ic_launcher_round"
  android:enabled="true"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
  </intent-filter>
</activity-alias>

<activity-alias
  android:name=".MainActivityHoliday"
  android:targetActivity=".MainActivity"
  android:icon="@mipmap/ic_launcher_holiday"
  android:roundIcon="@mipmap/ic_launcher_holiday_round"
  android:enabled="false"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
  </intent-filter>
</activity-alias>
```

The icon name is the part of the alias name after `MainActivity` (`.MainActivityHoliday` is `Holiday`), the same rule `react-native-change-icon` uses. If an alias has no `MainActivity` in its name, the whole class name is used. To pick a name explicitly, add `<meta-data android:name="flexAppIcon" android:value="Holiday" />` to the alias.

Never rename or remove a shipped alias: launchers and existing installs refer to it by name.

`getAppIconState().issues` lists setup problems that are found at runtime. Two of them make `setAppIcon` reject with `E_CONFIG` instead of switching:

- `MainActivity` still has the launcher intent filter. Disabling the activity itself is the most common cause of apps that close or cannot be opened after an icon change.
- No alias, or more than one alias, is enabled in the manifest.

## Usage

```tsx
import { setAppIcon, getAppIcon, useAppIcon } from 'react-native-app-icon-switcher';

await setAppIcon('Holiday'); // { status: 'applied' | 'pending' | 'unchanged', icon: 'Holiday' }
await setAppIcon(null); // back to the primary icon ('default' works too)
await getAppIcon(); // 'Holiday', or null for the primary icon

function IconPicker() {
  const { state, setIcon } = useAppIcon();
  if (!state?.supported) return null;
  return [null, ...state.icons].map((icon) => (
    <Button key={icon ?? 'primary'} title={icon ?? 'Default'} onPress={() => setIcon(icon)} />
  ));
}
```

### When the change happens

| | iOS | Android |
| --- | --- | --- |
| App in the foreground | Applied at once. iOS shows its "You have changed the icon" alert, and the promise resolves when the user dismisses it. | Stored as `pending` and applied about 0.7 s after the app leaves the foreground. |
| App in the background or not running (push, schedule) | Stored and applied the next time the app becomes active. | Applied right away. |

Why Android waits: when the alias that launched the current task is disabled, Android finishes that task even with `DONT_KILL_APP`, which looks like a crash to the user. The switch is therefore applied only when all of these hold:

- None of the app's activities is started.
- The screen is on, so a user who locked the phone inside the app comes back to the app.
- No other app's activity, such as a browser tab for sign-in or the camera, sits on top of the app's task waiting to return a result.

The library tracks activities from process start through a small `ContentProvider`. To keep the app from ever having zero launcher entries, it enables the target alias first and then returns the other alternates to their manifest default state. On API 33 and later, all component changes are made in one call.

Why iOS waits: iOS only accepts the change while the app is active and always shows its alert. Hiding the alert requires a private API, which this library does not use.

### Push notifications

The payload key is `flexAppIcon`. Its value is an icon name, or `"default"` for the primary icon. An optional `flexAppIconAt` (Unix milliseconds) in the future schedules the change instead.

```json
{ "flexAppIcon": "Holiday" }
{ "flexAppIcon": "default", "flexAppIconAt": "1767225600000" }
```

**From JavaScript**, in any push handler (`@react-native-firebase/messaging`, Notifee, `react-native-notifications`, and others):

```ts
import { handleAppIconPayload } from 'react-native-app-icon-switcher';

messaging().onMessage((message) => handleAppIconPayload(message.data));
messaging().setBackgroundMessageHandler((message) => handleAppIconPayload(message.data));
```

**From Android native code**, which also works when no JavaScript runs:

```kotlin
import com.flexappicon.FlexAppIcon

class AppMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    if (FlexAppIcon.handleRemoteMessageData(this, message.data)) return
    // ...other messages
  }
}
```

**From the iOS `AppDelegate`**, for silent pushes (`content-available: 1`, with the `remote-notification` background mode) and for notification taps:

```swift
import react_native_app_icon_switcher

func application(_ application: UIApplication,
                 didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                 fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
  completionHandler(FlexAppIconManager.handleNotificationPayload(userInfo) ? .newData : .noData)
}
```

In Objective-C, use `#import <react-native-app-icon-switcher/FlexAppIconManager.h>`. The plain native setters are `FlexAppIcon.setIcon(context, "Holiday")` on Android and `FlexAppIconManager.setIconName("Holiday")` on iOS.

iOS cannot change the icon while the app is in the background, so a silent push only stores the change and the user sees it the next time they open the app. A Notification Service Extension cannot change the icon at all.

### Schedules

```ts
import { scheduleAppIcon, setAppIconSchedule, clearAppIconSchedule } from 'react-native-app-icon-switcher';

await scheduleAppIcon('Holiday', new Date('2026-12-24T00:00:00'));
await setAppIconSchedule([
  { icon: 'Holiday', at: new Date('2026-12-24T00:00:00') },
  { icon: null, at: new Date('2026-12-27T00:00:00') },
]);
```

Android uses an inexact alarm, which needs no permission. When the alarm fires, the switch is applied in the background with the same safety rules as above. Alarms do not survive a reboot. An entry that came due during a reboot is applied the next time the app's process starts.

On iOS, an entry that comes due while the app is open is applied at that moment. Otherwise it is applied the next time the app becomes active. When several entries have come due, only the latest one is applied.

## API

| Call | Result |
| --- | --- |
| `setAppIcon(name \| null)` | `{ status, icon }`. `status` is `applied`, `pending` or `unchanged`. |
| `resetAppIcon()` | `setAppIcon(null)` |
| `getAppIcon()` | Current icon name, or `null` for the primary icon. |
| `getAvailableAppIcons()` | Alternate icon names, without the primary icon. |
| `isAppIconChangeSupported()` | Whether this build can switch icons. |
| `getAppIconState()` | `{ supported, current, icons, pending, schedule, issues }` |
| `cancelPendingAppIcon()` | Drops a change that is still waiting. |
| `scheduleAppIcon(name, at)` / `setAppIconSchedule(entries)` / `clearAppIconSchedule()` / `getScheduledAppIcons()` | Manage the schedule. |
| `addAppIconChangeListener(listener)` | `{ icon, previous }` for every applied change while JavaScript is running. |
| `handleAppIconPayload(data, { key? })` | Applies or schedules a push payload. Resolves to `null` if the payload has no icon key. |
| `useAppIcon()` | `{ state, setIcon, refresh }`. Refreshes after changes and when the app returns to the foreground. |

Rejected promises carry `error.code`:

- `E_NOT_LINKED`: the native module is missing.
- `E_NOT_SUPPORTED`: the device or build cannot switch icons.
- `E_UNKNOWN_ICON`: no icon with that name is configured.
- `E_CONFIG`: the Android manifest setup is unsafe; see `issues`.
- `E_FAILED`: the system refused the change.

## Compatibility

| | Status |
| --- | --- |
| React Native 0.71–0.81, either architecture | Bridge module or TurboModule, chosen from `newArchEnabled` / `RCT_NEW_ARCH_ENABLED` |
| React Native 0.82+ | TurboModule. 0.82 removed the legacy architecture. |
| iOS | 13+ (alternate icons need iOS 10.3+) |
| Android | API 21+. The batched switch is used on API 33+. |

What has been run: React Native 0.88 with the New Architecture, on an iOS 18 simulator and an Android emulator (see the example app). Other versions are expected to work from the code paths above, but they have not been run yet.

## Migrating from react-native-change-icon

| react-native-change-icon | react-native-app-icon-switcher |
| --- | --- |
| `changeIcon('Holiday')` | `setAppIcon('Holiday')` |
| `changeIcon('Default')` | `setAppIcon(null)` or `setAppIcon('default')` |
| `getIcon()` returns `'Default'` | `getAppIcon()` returns `null` |
| Calls `activity.finish()`, so the app closes | Applies the change when the app leaves the foreground |
| Builds class names from the application id | Reads aliases from the installed manifest, so flavors and id suffixes just work |

Aliases named `.MainActivityDefault`, `.MainActivityHoliday` and so on keep working unchanged.

## Icon variants, adaptive icons and Expo

- **iOS 18 dark and tinted icons.** These come from the App Icon set itself. In Xcode, give each alternate set its Dark and Tinted appearances. The library only passes the set name to the system.
- **Android adaptive and themed icons.** Point each alias's `android:icon` and `android:roundIcon` at an adaptive icon (`res/mipmap-anydpi-v26/*.xml`) that declares `<monochrome>` to support Android 13+ themed icons.
- **Expo.** There is no config plugin yet. In a managed project, run `npx expo prebuild` and apply the iOS and Android setup above to the generated native projects. Re-apply it if you regenerate them, or keep it in your own config plugin.

## Known platform limits

- **iOS:** the system alert cannot be avoided with public APIs, and the icon cannot change while the app is in the background. On iOS 26 the system sometimes reports "Resource temporarily unavailable". The library retries with backoff and keeps the change pending if it still fails.
- **Android:** some launchers take a few seconds to show the new icon, and some remove a pinned home-screen shortcut and add the new icon to the app drawer. Point notification `PendingIntent`s and deep links at `MainActivity`, not at an alias, because a disabled alias cannot be launched. Libraries that look up the current activity's component info can fail while an alias is being disabled; applying the switch only in the background avoids most of these failures.

## Development

```sh
npm install
npm run typecheck
npm test
npm run build
```

`example/` is a React Native 0.88 app with three icons: Primary, Midnight and Sunset. `npm run example:install` packs the library and installs it there the way a consumer would. Then run `cd example/ios && pod install`.

Scripted runs:

- iOS: `xcrun simctl launch <device> org.reactjs.native.example.FlexAppIconExample -flexDemo icon/Midnight` switches icons. `-flexPayload '{flexAppIcon=Sunset;}'` feeds a push payload to the native handler before the app is active.
- Android: `adb shell am start -W -a android.intent.action.VIEW -d flexappicon://icon/Midnight`.
