# AC Remote (Videocon 175A layout)

Every button from the Videocon remote No. 175A: Power, Temp −/+, MODE, AUTO CLEAN,
LED DISPLAY, FAN, SLEEP, TURBO, SWING ↔, SWING ↕, HR, MIN, CLOCK, TIMER.
Sends through the phone's built-in IR blaster (Redmi / Xiaomi / Poco etc.).

## Install with Android Studio (Windows PC)
1. Unzip this folder. Open Android Studio → **File › Open** → pick the `ACRemote` folder.
   Wait for "Gradle sync" to finish (first time downloads a few hundred MB).
2. On the phone: Settings › About phone › tap **MIUI/OS version** 7 times → Developer options
   on. In Developer options turn on **USB debugging** (and **Install via USB** on Xiaomi).
3. Plug the phone in, pick it in the device list, press the green ▶ Run button.

Or build without running: **Build › Build App Bundle(s)/APK(s) › Build APK(s)**,
then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it.

## Install without a PC (GitHub builds it)
1. Make a free GitHub account, create a new repository, upload all files from this folder
   (keep the `.github` folder; on the website use "Add file › Upload files").
2. Open the **Actions** tab → "Build APK" runs automatically (≈5 min).
3. Open the finished run → download **AC-Remote-apk** (a zip) on the phone, extract,
   tap `app-debug.apk`, allow "install unknown apps".

## First use
Point the phone's top edge at the AC and press **Power**.
- AC beeps → done. Try **LED DISPLAY** to switch the light off.
- No beep → switch to **Type B**, press Power again; then **Type C**.

Type A/C = TCL 112-bit format (what the Mi Remote/IRext database lists for Videocon).
Type B = Midea/Coolix format, used by many rebadged Indian ACs.

Notes
- The app can't read the AC's real state; if they get out of sync, press Power.
- CLOCK only sets the clock shown in the app (AC remotes don't send the time).
- Type A/C: no known Auto Clean code. Type B: no separate left–right swing code;
  its timer is run by the phone (leave the phone where it can see the AC).
