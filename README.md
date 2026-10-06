# ARGUN Mapper

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com/about/versions)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

Open-source Android app for the **ARGUN** gaming accessory (Blackfin AR003, FCCID `2AMXIAR003`)
that turns it into a Bluetooth gamepad for any Android phone or Android TV.

> **Heads-up on input injection** — Android blocks key injection from ordinary apps.
> Read [Input delivery](#input-delivery-read-this) and pick a route before expecting
> button presses to reach your games.

## Features

- **BLE scanning & connection** to any device advertising the ARGUN GATT service
- **Live button events** decoded from the vendor's 16-byte notification payloads
- **Customisable mapping** — tap any button and choose which Android key it emits
- **Persistent mappings** via Room, with one-tap restore of factory defaults
- **Three injection routes** — root, adb/Shizuku-granted permission, or off
- **Auto-detection** that reports what the current device actually supports
- **Foreground service** keeps the connection alive while you play
- **Runs on Android TV / Fire OS** as well as phones and tablets (minSdk 21)
- **No hardcoded MAC address** — works with any AR003 unit in range

## Requirements

| | |
|---|---|
| Android | 5.0 (API 21) or newer — includes Fire OS 7.x (API 25) |
| Bluetooth | BLE 4.0+ |
| Device | Any Blackfin AR003 ARGUN |
| For input | Root, or adb/Shizuku to grant `INJECT_EVENTS` (see below) |

## Building

### One-command build (mxLinux / Ubuntu / Debian / Fedora)

The script installs a **private** JDK and Android SDK under `$HOME` — it does not need
`sudo`, does not touch your system packages, and is safe to re-run.

```bash
chmod +x build-on-mxlinux.sh
./build-on-mxlinux.sh            # build only
./build-on-mxlinux.sh --install  # build, then install over adb
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Building manually

```bash
export JAVA_HOME=/path/to/jdk-17        # a *full* JDK is required (needs `jlink`)
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | sdkmanager --licenses

./gradlew assembleDebug
```

<details>
<summary>Where to get a full JDK</summary>

The build fails with `jlink executable ... does not exist` if only a JRE is installed
(e.g. Debian's `openjdk-21-jre-headless`). Install `openjdk-17-jdk-headless`, or grab a
Temurin tarball and point `JAVA_HOME` at it. `build-on-mxlinux.sh` does this for you.
</details>

### Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Turn on your ARGUN and enable Bluetooth on your device.
2. Open **ARGUN Mapper** and grant the Bluetooth permission when asked.
3. Tap **Scan for ARGUN**. Matching devices appear with a signal-strength dot.
4. Tap a device to connect — a foreground-service notification confirms the connection.
5. On the mapping screen, scroll to **Input delivery** and enable a route
   (**Enable automatically** picks the best one available).
6. Tap a button row to change its key, and **Test** to verify input arrives.
7. Open your game and play.

## Button mapping

Factory defaults, matching the physical layout of the ARGUN:

| ARGUN | Physical button | Default key | Typical use |
|-------|-----------------|-------------|-------------|
| `B2` | Trigger | `KEYCODE_BUTTON_A` | Fire |
| `B3` | Game button 2 | `KEYCODE_BUTTON_B` | Switch weapon |
| `B4` | D-Pad up | `KEYCODE_DPAD_UP` | Aim up |
| `B5` | D-Pad right | `KEYCODE_DPAD_RIGHT` | Strafe right |
| `B6` | D-Pad down | `KEYCODE_DPAD_DOWN` | Crouch |
| `B7` | D-Pad left | `KEYCODE_DPAD_LEFT` | Strafe left |
| `B8` | D-Pad push | `KEYCODE_DPAD_CENTER` | Confirm / jump |
| `B9` | Game button 3 | `KEYCODE_BUTTON_X` | Grenade |

Every mapping is editable from the mapping screen and stored locally, so your choices
survive restarts. **Restore Defaults** puts the table above back.

## Input delivery (read this)

Android does not let an ordinary installed app inject key events into other apps.
`injectInputEvent()` needs `android.permission.INJECT_EVENTS`, which is
`signature|privileged` — grantable only to system apps or by an external helper.

**If the ARGUN "connects but buttons do nothing", this is why.** The app declares
`INJECT_EVENTS`, so the permission can be granted — but nothing grants it automatically.
Pick one of these routes, then set it under **Input delivery** on the mapping screen.

### Route 1 — Root (recommended if available)

Needs a working `su`. No Android permission involved, so it works on a stock-but-rooted
Fire TV stick.

1. Install the app.
2. Open it once so the root manager prompts you, and grant superuser access.
3. On the mapping screen, choose **Root (su)** or tap **Enable automatically**.

The app dispatches via `input motionevent DOWN/UP <code>` on Android 10+, and
`input keyevent <code>` below that. On older releases only `keyevent` exists, which sends
a complete press in one call — so the release event is dropped. Held inputs will feel
different on API 21–28.

### Route 2 — adb

No root needed; works from a computer over USB.

```bash
adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS
```

Then choose **Permission (adb / Shizuku)** in the app. The permission persists until the
app is uninstalled.

### Route 3 — Shizuku

Install [Shizuku](https://shizuku.rikka.app/), start it (ADB or root), then use Shizuku's
app list to grant `android.permission.INJECT_EVENTS` to ARGUN Mapper. Choose
**Permission (adb / Shizuku)** afterwards.

Note that on Android 9+ Shizuku cannot grant most permissions unless Shizuku itself is
running as root; on Fire OS 7.x (API 25) there are no hidden-API restrictions, so the
ADB-started Shizuku service works reliably there.

### Checking what works

The **Input delivery** card lists exactly what was detected on your device:

```
✓ Root access                    ✓ INJECT_EVENTS granted
✓ Root manager installed         ✗ Shizuku running
```

If nothing is available the app still connects and decodes presses — they simply are not
delivered. Each button row also has a **Test** button, which sends that mapping once so
you can confirm the route works before starting a game.

### What is not implemented

**HID-over-GATT (HOGP)** — acting as a BLE peripheral advertising a HID gamepad, so the
Android Bluetooth stack generates key events itself. This needs no privilege at all and is
the cleanest long-term fix. It is not implemented here; the BLE layer, payload decoding and
mapping UI are all reusable when it is.

## Protocol notes

Reverse-engineered from the vendor's GATT dump and an nRF Connect capture.

### Service layout

```
Service  0000fff0-0000-1000-8000-00805f9b34fb   (vendor-specific)
├── fff1  Notify + Read   device status
├── fff2  Read            device id
├── fff3  Notify + Read   button events   <-- the one we use
├── fff4  Read            unknown
└── fff5  Read + Write    control
```

Notifications are enabled by writing `0x0001` to the characteristic's CCCD
(`00002902-0000-1000-8000-00805f9b34fb`).

### Payloads (16 bytes)

| Payload | Meaning |
|---------|---------|
| `"B2DOWN"` + NUL padding | Button `B2` pressed |
| `"B2UP"` + NUL padding | Button `B2` released |
| `"ARGun KeyPressed"` | Trigger / handshake |
| 16 × `0x00` | Idle, no action |

Note the case: the handshake reads `ARGun`, not `ARGUN`.

## Project layout

```
app/src/main/java/com/argun/mapper/
├── ArgunMapperApp.kt          Application class
├── ArgunService.kt            Foreground service; owns the BLE connection
├── ble/
│   ├── BleManager.kt          Scan, connect, GATT callbacks, payload parsing
│   ├── ConnectionState.kt     Sealed connection state
│   └── NotificationHelper.kt  CCCD descriptor encoding
├── input/                     Key event delivery
│   ├── InputSimulator.kt         Routes events to the selected strategy
│   ├── InjectionStrategy.kt      Strategy interface + InjectionMode
│   ├── RootInjectionStrategy.kt  `su` + `input` (no permission needed)
│   ├── PermissionInjectionStrategy.kt  InputManager, needs INJECT_EVENTS
│   └── PrivilegeDetector.kt      Detects root / Shizuku / granted permission
├── model/                     ArgunButton, ArgunDevice, KeyMapping, Notification
├── data/                      Room entity, DAO, database, repository
└── ui/                        Compose screens + MainViewModel
```

## Architecture

- **MVVM** — `MainViewModel` exposes a single immutable `MainUiState` via `StateFlow`
- **Jetpack Compose** + Material 3, dark/light themes
- **Room** for mappings, **Coroutines/Flow** for BLE events
- **Foreground service** (`connectedDevice` type) so the link survives backgrounding
- **Strategy pattern** for input delivery, so root/permission/off are interchangeable
- **minSdk 21** for Fire OS and older Android TV hardware

## Contributing

Issues and pull requests are welcome. Since the project is aimed at the ARGUN community,
please keep it lightweight and dependency-light.

## Acknowledgements

- Blackfin Co. for the ARGUN gaming accessory
- The Android Bluetooth LE community
- Everyone contributing mappings and fixes

## License

[MIT](LICENSE)