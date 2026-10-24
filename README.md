<a id="readme-top"></a>

<div align="center">

<img src="resources/logo/sidescreen-icon.png" alt="Side Screen" width="128"/>

<h1>Side Screen</h1>

<p><em>Turn your Android tablet into a second display for macOS, Windows and Linux — over a USB-C cable or wirelessly over Wi-Fi</em></p>

<p>
  <a href="https://github.com/duynguyenxdev/SideScreen/releases/latest">
    <img src="https://img.shields.io/github/v/release/duynguyenxdev/SideScreen?style=for-the-badge&label=version&color=blue" alt="Version">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/duynguyenxdev/SideScreen?style=for-the-badge&color=34C759" alt="License">
  </a>
</p>

![macOS](https://img.shields.io/badge/macOS_13+-000000?style=for-the-badge&logo=apple&logoColor=white)
![Windows](https://img.shields.io/badge/Windows_10+-0078D6?style=for-the-badge&logo=windows&logoColor=white)
![Linux](https://img.shields.io/badge/Linux_X11-FCC624?style=for-the-badge&logo=linux&logoColor=black)
![Android](https://img.shields.io/badge/Android_8+-3DDC84?style=for-the-badge&logo=android&logoColor=white)

</div>

---

<div align="center">
  <img src="resources/screenshots/hero_screenshot.jpeg" alt="Side Screen — computer + Android tablet as a second display" width="800"/>
</div>

---

## Contents

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Download](#download)
- [Step 1 — Set up the Android tablet](#step-1--set-up-the-android-tablet)
- [Step 2 — Set up your computer](#step-2--set-up-your-computer) ([macOS](#macos) · [Windows](#windows) · [Linux](#linux))
- [Step 3 — Connect](#step-3--connect) ([USB](#connect-with-a-usb-cable-lowest-latency) · [Wi-Fi](#connect-over-wi-fi-no-cable))
- [Using the tablet](#using-the-tablet)
- [Settings](#settings)
- [Troubleshooting](#troubleshooting)
- [Build from source](#build-from-source)

---

## How it works

Side Screen has two parts:

1. **The desktop app** runs on your computer. It creates an extra (virtual) display — or picks one of your existing monitors — and streams it as video.
2. **The Android app** runs on your tablet. It shows that video full screen and sends your touches back as mouse clicks, drags and scrolls.

They talk over a **USB-C cable** (lowest latency, best for drawing and games) or over **Wi-Fi** (no cable; pair once with a QR code).

| Your computer | What you can do |
|---|---|
| **macOS 13+** (Apple Silicon or Intel) | Extend the desktop with a virtual display, or mirror |
| **Windows 10 / 11** (64-bit) | Extend the desktop (with a free virtual display driver), or mirror any monitor |
| **Linux** (X11 session, 64-bit) | Mirror any monitor; extend where the graphics driver supports it |

The tablet needs **Android 8.0 or newer**. An H.265 (HEVC) hardware decoder gives the best picture; tablets without one fall back to H.264 automatically.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Quick start

1. **Install** the Android app on your tablet and the desktop app on your computer ([Download](#download)).
2. **On the tablet**, turn on USB debugging if you will use a cable ([Step 1](#step-1--set-up-the-android-tablet)).
3. **On the computer**, open Side Screen and click **Start**.
4. **On the tablet**, open Side Screen and tap **Connect** (USB) or **Scan QR Code** (Wi-Fi).

That's it — drag a window onto the tablet.

---

## Download

Get everything from the [**latest release**](https://github.com/duynguyenxdev/SideScreen/releases/latest):

| Device | File | Notes |
|---|---|---|
| Android tablet | `SideScreen-<version>-android.apk` | |
| macOS | `SideScreen-<version>-mac-universal.dmg` | Apple Silicon and Intel |
| Windows | `SideScreen-<version>-windows-x64.msi` | Installer (recommended) |
| Windows | `SideScreen-<version>-windows-x64-portable.zip` | No install — unzip and run |
| Linux | `SideScreen-<version>-linux-amd64.deb` | Debian, Ubuntu, Mint, Pop!_OS… |
| Linux | `SideScreen-<version>-linux-x64.tar.gz` | Any distribution — unpack and run |

---

## Step 1 — Set up the Android tablet

1. Download the `.apk` onto the tablet and open it. If Android asks, allow your browser or file manager to **install unknown apps**.
2. **For USB connections only** — turn on USB debugging (once):
   1. Open **Settings → About tablet** and tap **Build number** seven times until it says you are a developer. (On some brands it's under **Software information**.)
   2. Go back to **Settings → System → Developer options** and turn on **USB debugging**.
   3. The first time you plug into the computer, the tablet asks **"Allow USB debugging?"** — tick **Always allow from this computer** and tap **Allow**.
3. **For Wi-Fi only** — nothing to set up. Keep the tablet on the same Wi-Fi network as the computer (5 GHz if you have it).

The app's **USB** tab shows a checklist (Developer Mode, USB Debugging, cable, desktop app running) so you can see what is missing at a glance.

> No computer handy? Tap **No computer nearby? Try the demo** on the tablet to see what Side Screen looks like.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Step 2 — Set up your computer

### macOS

1. Open the `.dmg` and drag **Side Screen** into **Applications**.
2. Open it. If macOS says the app is **"damaged"** or from an unidentified developer, run this once in Terminal (the app isn't notarized by Apple), then open it again:
   ```bash
   sudo xattr -cr /Applications/SideScreen.app
   ```
3. Grant the permissions macOS asks for:
   - **Screen Recording** — to capture the display (System Settings → Privacy & Security → Screen & System Audio Recording)
   - **Accessibility** — so touches can move the cursor (System Settings → Privacy & Security → Accessibility)
   - **Local Network** — only for Wi-Fi mode
4. **For USB connections**, install `adb` (Android's USB tool):
   ```bash
   # Install Homebrew first if you don't have it: https://brew.sh
   brew install --cask android-platform-tools
   ```

The Mac app lives in the **menu bar**. Click its icon to open the settings and press **Start**.

> **Tip:** turn on **System Settings → Desktop & Dock → Displays have separate Spaces**. Without it, macOS blanks the tablet whenever an app goes full screen on another display.

### Windows

1. Run the `.msi` installer (or unzip the portable `.zip` and run `SideScreen\SideScreen.exe`).
   If Windows SmartScreen warns about an unknown publisher, click **More info → Run anyway**.
2. **To use the tablet as an extra screen** (not just a copy of an existing one), install the free **Parsec Virtual Display Driver**:
   1. Download `ParsecVDisplay` / `parsec-vdd` from [github.com/nomi-san/parsec-vdd/releases](https://github.com/nomi-san/parsec-vdd/releases) and install it.
   2. Restart Side Screen. **Virtual display (extend desktop)** in the **Screen** list becomes available.

   Without the driver you can still pick any existing monitor in the **Screen** list to mirror it on the tablet.
3. **For USB connections**, install `adb`:
   - With winget: `winget install Google.PlatformTools`, **or**
   - Download [SDK Platform-Tools for Windows](https://developer.android.com/tools/releases/platform-tools), unzip it (e.g. to `C:\platform-tools`), and either add that folder to your `PATH` or set **Advanced → adb path** in Side Screen to `C:\platform-tools\adb.exe`.
4. When Windows Firewall asks about Side Screen, allow it on **Private networks** (needed for Wi-Fi mode).

Video encoding (ffmpeg) is included — Side Screen uses your NVIDIA, Intel or AMD graphics card automatically.

### Linux

Side Screen needs an **X11 (Xorg) session**. Wayland is not supported yet — on the login screen, pick "Ubuntu on Xorg" / "GNOME on Xorg" / "Plasma (X11)".

1. Install the app:
   ```bash
   # Debian / Ubuntu / Mint / Pop!_OS
   sudo apt install ./SideScreen-*-linux-amd64.deb

   # Any distribution — portable build
   tar -xzf SideScreen-*-linux-x64.tar.gz
   ./SideScreen/bin/SideScreen
   ```
2. Install the helpers (names for Debian/Ubuntu; Fedora: `ffmpeg libXtst xrandr android-tools`):
   ```bash
   sudo apt install ffmpeg libxtst6 x11-xserver-utils adb
   ```
   - `ffmpeg` — video encoding (a bundled copy is used if it's missing, but the system one works best with your GPU)
   - `libxtst6` — lets touches control the mouse
   - `x11-xserver-utils` — provides `xrandr` for listing and creating displays
   - `adb` — only for USB connections
3. **Extending vs. mirroring:** Side Screen can mirror any monitor. To *extend* the desktop it needs a spare `VIRTUAL` output from your graphics driver (check with `xrandr | grep VIRTUAL`). The Intel Xorg driver provides one with `Option "VirtualHeads" "1"`; many other drivers don't. If none is available, choose a monitor in the **Screen** list instead.
4. **For USB**, if `adb devices` shows `no permissions`, install your distribution's Android udev rules (`sudo apt install android-sdk-platform-tools-common`) and re-plug the tablet.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Step 3 — Connect

### Connect with a USB cable (lowest latency)

1. Plug the tablet into the computer with a **data** USB cable (not a charge-only one) and accept **Allow USB debugging** on the tablet.
2. On the computer, open Side Screen:
   - **Windows / Linux:** under **Connection**, choose **USB cable**, then click **Start**.
   - **macOS:** keep the **USB** tab selected and click **Start**.

   The computer sets up the cable connection (`adb reverse`) automatically. On Windows/Linux the **Status** card shows **USB: Ready** when it worked.
3. On the tablet, open Side Screen, stay on the **USB** tab and tap **Connect**.

If you plugged the cable in *after* pressing Start, click **Stop** and **Start** again.

### Connect over Wi-Fi (no cable)

1. Put the computer and the tablet on the **same Wi-Fi network**.
2. On the computer, open Side Screen:
   - **Windows / Linux:** under **Connection**, choose **Wi-Fi**, then click **Start**. A QR code and an 8-digit code appear in the **Wireless pairing** card.
   - **macOS:** switch to the **Wireless** tab and click **Start**.
3. On the tablet, open Side Screen, switch to the **Wireless** tab and tap **Scan QR Code** → allow the camera → point it at the QR code.
   No camera? Tap **No camera? Enter code instead** and type the computer's **Address**, **Port** and the 8-digit **Pairing code** shown under the QR.
4. Done. The tablet remembers the computer — next time just open the app and tap **Reconnect**.

To revoke access, click **Unpair all devices** (Windows/Linux) or **Reset Token (forget all)** (macOS), then pair again.

Wi-Fi adds roughly 10–50 ms of delay depending on your network; **5 GHz Wi-Fi** is strongly recommended. Use USB for drawing or fast games.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Using the tablet

Once connected, the tablet works like a touch screen for your computer:

| Gesture on the tablet | What happens on the computer |
|---|---|
| Tap | Left click |
| Double-tap | Double click |
| Touch and hold, then release | Right click |
| Touch and hold, then drag | Click-and-drag (move windows, select text) |
| Drag with one finger | Scroll (with momentum) |
| Drag with two fingers | Scroll |
| Pinch | Zoom (⌘ + scroll on macOS, Ctrl + scroll on Windows/Linux) |

Use the Android back gesture to leave the stream and return to the connection screen.

**Desktop app tips (Windows / Linux):**
- Closing the window keeps Side Screen running in the **system tray**; use the tray icon to reopen it, start/stop, or quit.
- Turn on **Start streaming when the app opens** to have it ready automatically.

**Mac app tips:**
- **Headless Mac** (Mac mini/Studio with no screen, or a closed laptop): in Settings → Startup, turn on **Launch at Login** and **Auto-start streaming on launch**. The Mac then boots straight into serving the tablet. Grant Screen Recording once with a screen attached first.

---

## Settings

| Setting | What it does | Options |
|---|---|---|
| **Screen** *(Windows/Linux)* | What the tablet shows | Virtual display (extends the desktop) or any existing monitor (mirror) |
| **Resolution** | Size of the virtual display | Presets for 16:10, 16:9, 4:3, 3:2, 5:3, or a custom size |
| **Refresh rate** | Frames per second | 30, 60, 90, 120 Hz |
| **HiDPI (Retina)** *(macOS)* | Sharper text by rendering at 2× | On / Off |
| **Rotation / Flip** | Turn the picture for a portrait tablet | 0°, 90°, 180°, 270°; flip horizontally or vertically |
| **Bitrate** | Picture quality vs. bandwidth | Higher = sharper; lower if Wi-Fi stutters |
| **Quality** | Encoder speed vs. quality | Ultra low latency, Low latency, Balanced, High quality |
| **Gaming boost** | One switch for games | 120 Hz, lowest latency, higher bitrate |
| **Encoder** *(Windows/Linux)* | Which video encoder to use | Automatic (graphics card first), or pick one |
| **Touch control** | Let the tablet control the mouse | On / Off |
| **Advanced** *(Windows/Linux)* | Custom `ffmpeg` / `adb` locations and network port | Leave empty for automatic; port 54321 |

Changes apply right away; some (like resolution) briefly restart the stream.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Troubleshooting

### All platforms

<details>
<summary><strong>Tablet says "Connection refused" or the checklist shows "Desktop app running" in red (USB)</strong></summary>

- Click **Start** in the desktop app first, then tap **Connect** on the tablet.
- Make sure `adb` is installed (see [Step 2](#step-2--set-up-your-computer)) — the Windows/Linux Status card says **adb not found** if it isn't.
- Run `adb devices` in a terminal. The tablet should be listed as `device`. If it says `unauthorized`, unlock the tablet and accept the USB debugging prompt.
- Try another cable or port — many cables only charge.
- If you plugged in after starting, click **Stop** then **Start**.
</details>

<details>
<summary><strong>Wi-Fi: "Couldn't reach computer" or the connection times out</strong></summary>

- Both devices must be on the same network. Guest networks and some mesh routers isolate devices from each other.
- Click **Start** on the computer before scanning — it only listens while running.
- Allow Side Screen through the firewall (Windows: Private networks; macOS: Local Network permission; Linux: open TCP port 54321 if you use `ufw`/`firewalld`).
- If the computer's IP address changed, scan a fresh QR code.
</details>

<details>
<summary><strong>Wi-Fi: "Re-pair required"</strong></summary>

The computer's pairing was reset (Unpair all / Reset Token, or a reinstall). Tap **Scan QR Code** on the tablet and scan the new code.
</details>

<details>
<summary><strong>Laggy or stuttering picture</strong></summary>

- Use USB, or 5 GHz Wi-Fi instead of 2.4 GHz.
- Lower the resolution, refresh rate or bitrate.
- On Windows/Linux, check the Status card's **Encoder** line — `(software)` means no graphics-card encoder was found; update your GPU driver.
</details>

<details>
<summary><strong>Black screen or "No video output" on the tablet</strong></summary>

The resolution may be larger than the tablet's video decoder can handle. Lower the resolution or refresh rate (and turn off HiDPI on macOS).
</details>

<details>
<summary><strong>Touches don't move the mouse</strong></summary>

- Check **Touch control** is on.
- macOS: grant **Accessibility** permission to Side Screen.
- Linux: install `libxtst6`, and make sure you're in an X11 session.
- Windows: touches can't control apps running as Administrator unless Side Screen also runs as Administrator.
</details>

### macOS

<details>
<summary><strong>"SideScreen is damaged and can't be opened"</strong></summary>

The app isn't notarized by Apple. Run `sudo xattr -cr /Applications/SideScreen.app`, then open it again.
</details>

<details>
<summary><strong>"Screen & System Audio: Required" stays red after an update</strong></summary>

macOS ties the permission to each build. In Terminal run `tccutil reset ScreenCapture com.sidescreen.app`, open Side Screen and grant the permission again.
</details>

<details>
<summary><strong>Tablet goes black when an app is full screen on the Mac</strong></summary>

Turn on **System Settings → Desktop & Dock → Mission Control → Displays have separate Spaces**, then log out and back in.
</details>

### Windows

<details>
<summary><strong>"Virtual display (extend desktop) — unavailable"</strong></summary>

Install the [Parsec Virtual Display Driver](https://github.com/nomi-san/parsec-vdd/releases), then click **Refresh** next to the Screen list (or restart Side Screen). Until then, pick an existing monitor to mirror.
</details>

<details>
<summary><strong>The virtual display has the wrong resolution</strong></summary>

The Parsec driver only offers a fixed list of common resolutions. If your choice isn't one of them, Side Screen keeps the driver's default — pick a common size, or add custom modes as described in the parsec-vdd documentation.
</details>

### Linux

<details>
<summary><strong>"Wayland sessions aren't supported yet"</strong></summary>

Log out, and on the login screen choose the Xorg/X11 session (gear icon → "Ubuntu on Xorg", "GNOME on Xorg" or "Plasma (X11)").
</details>

<details>
<summary><strong>"no VIRTUAL output in xrandr"</strong></summary>

Your graphics driver doesn't offer a spare virtual output, so Side Screen can't add a new display. Choose an existing monitor in the **Screen** list to mirror it, or use a driver/config that provides virtual heads.
</details>

<details>
<summary><strong>Settings and log files</strong></summary>

Settings and `sidescreen.log` live in `~/.config/sidescreen/` (Linux), `%APPDATA%\SideScreen\` (Windows) and `~/Library/Application Support/SideScreen/` (macOS desktop host). Use **Show log** in the app to view the log.
</details>

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Build from source

```bash
git clone https://github.com/duynguyenxdev/SideScreen.git
cd SideScreen

# macOS app (Swift) — Xcode command line tools
cd MacHost && swift build -c release

# Windows / Linux desktop app (Kotlin + Compose Desktop) — JDK 21
cd DesktopHost && ./gradlew :app:run          # run
cd DesktopHost && ./gradlew :app:packageMsi   # or packageDeb / packageDmg

# Android app — JDK 17
cd AndroidClient && ./gradlew assembleDebug
```

Every push to `main` builds all four apps and publishes a GitHub release automatically (see [`.github/workflows/release.yml`](.github/workflows/release.yml)).

---

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Report bugs and ideas via [Issues](https://github.com/duynguyenxdev/SideScreen/issues).

## Support

Side Screen was created by **Tran Vuong Quoc Dat**. If it's useful to you, consider supporting the original author:

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/tranvuongqk)
[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/tranvuongquocdat)

## License

[MIT License](LICENSE) — free for personal and commercial use.
