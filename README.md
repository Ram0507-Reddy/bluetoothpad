# BluetoothPad

A high-performance, low-latency Windows 11 remote controller for Android that works **exclusively over Bluetooth RFCOMM (SPP)** — no Wi-Fi or local network connection required.

Designed with an industrial monochromatic black-and-white aesthetic, responsive layout (optimized for landscape mode), and zero emojis.

---

## Features

- **Sub-millisecond Touchpad**:
  - 1-finger smooth tracking with sub-pixel accumulation.
  - Dedicated Left / Right Click buttons + tap-to-click.
  - Multi-finger gestures:
    - 2-finger scroll and right click.
    - 3-finger swipe (Task View / Desktop / App Switcher).
    - 4-finger swipe (Action Center / Virtual Desktops).

- **Media & Presentation Deck**:
  - Volume Up, Volume Down, Mute toggle.
  - Play / Pause, Next track, Previous track.
  - Presentation controls (Start F5, Next slide, Previous slide, Exit ESC).

- **App Launcher**:
  - Automatically indexes installed Windows applications (Start Menu + common programs).
  - High-res official brand logos dynamically fetched via CDN.
  - Instant 1-tap app launch on Windows.
  - Live search & filter bar.

- **Live Soft Keypad**:
  - Direct typing from phone keyboard into any active Windows application.
  - Supports Unicode text streaming, Enter, Backspace, and Tab.

- **System Actions**:
  - Quick access to Lock Workstation, Task Manager, File Explorer, Terminal, Search, Zoom In/Out, and Clipboard actions (Copy / Paste / Undo / Select All).

- **Industrial UI**:
  - Strict black & white monochromatic palette.
  - Instant Light Mode / Dark Mode toggle.
  - Full landscape and portrait responsiveness.

---

## Architecture

- **Server (`server/`)**:
  - Written in Python 3 using native Windows Bluetooth sockets (`AF_BTH`, `BTPROTO_RFCOMM`).
  - Registers Bluetooth Serial Port Profile (SPP) via Winsock `WSASetServiceW`.
  - Dispatches hardware events via 64-bit Win32 `SendInput` attached directly to the interactive Windows user desktop (`OpenDesktopW` / `SetThreadDesktop`).

- **Android Client (`android/`)**:
  - Native Android app (Java, API 26+).
  - Standard Bluetooth RFCOMM SPP socket.
  - JSON-based protocol with micro-packet compression for sub-millisecond touchpad responsiveness.

---

## Quick Start

### 1. Windows Server Setup
1. Pair your Android phone with your Windows laptop via Windows Bluetooth Settings.
2. Ensure Python 3 is installed.
3. Double-click `run_server.bat` or run:
   ```bash
   cd server
   python server.py
   ```
4. The server will bind to Bluetooth RFCOMM (Channel 4) and register the SPP service.

### 2. Android Client Setup
1. Open the Android project in Android Studio or build using Gradle:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
2. Install the APK to your phone:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open **Bluetooth Remote** on your phone.
4. Select your PC from the paired devices dropdown and tap **CONNECT**.

---

## License

MIT License. Free to use and modify.
