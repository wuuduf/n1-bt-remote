# N1 Bluetooth Remote

**A remote and power on/off solution built for the Phicomm N1 running webpad's firmware ("W大固件").**

webpad's firmware keeps the system framework of Phicomm's official V2.32, and V2.32 removed Android sleep: the power key really shuts the box down, and only unplugging it brings it back. This project turns an Android phone into a Bluetooth remote for the N1 and adds a small helper on the box that brings back standby and wake from the remote. The original Phicomm Bluetooth remote can use the same power handling.

[中文](README.md) | **English**

![build](https://github.com/wuuduf/n1-bt-remote/actions/workflows/build.yml/badge.svg)

| Part | Runs on | What it does |
|---|---|---|
| `n1-remote.apk` | Android phone (Android 9+) | The remote. Every key goes over Bluetooth HID, no Wi-Fi needed |
| `n1-standby-helper.apk` | N1 box | Install it and open it once: it sets itself up. Then it takes the power key in the background, toggles a soft standby and ignores other keys while in standby |
| `scripts/box.sh` | Computer (optional) | Installs the helper over the box's network ADB, plus maintenance commands |
| `tools/confirm_pairing.py` | Computer | Taps "Pair" on the box for you when you have no remote |
| `tools/selftest.py` | Computer | One-command check of the box side: key mapping, held keys, TV key, standby, wake |

The app follows the phone's system language: Chinese on Chinese phones, English everywhere else.

## Firmware

| | |
|---|---|
| Firmware | webpad's mod v2.2, file `N1_mod_by_webpad_v2.2_20180920.img` |
| Image SHA-256 | `6adffe7f9085ddd5411930d7fb1829f2b06c281255dfcf9b73822884be208863` |
| Based on | Phicomm official V2.32: `N1_A0_V2.32_A00_0904_NS`, built 2018-09-04, Android 7.1.2, kernel 3.14 |
| Test phone | Xiaomi 13 Ultra (Android 13) |

Check the version on your box (needs network ADB, see below):

```bash
python3 tools/adb_tcp.py <box-ip> 'getprop ro.build.display.id'
```

It should print `N1_A0_V2.32_A00_0904_NS`.

- **Why "built for webpad's firmware"**: the power part targets exactly the V2.32 problem (sleep removed, no way to wake from shutdown), and relies on this firmware's details: its power-key setting, Amlogic's HDMI and CEC interfaces, and network ADB running as root. See [docs/findings.md](docs/findings.md) (Chinese).
- **Phicomm's stock V2.32**: its system framework is file-for-file the same as webpad's v2.2, so it should work too, but it was not tested separately.
- **Firmware based on v2.19** (Rush's slim build, for example) reportedly keeps native sleep and doesn't need the power part. Not verified. The phone remote itself is standard Bluetooth HID and should work there.
- **Requirement**: the box's network ADB (port 5555) is on. On this box with webpad's firmware it is on from boot, running as root with no authorization. Check with `python3 tools/adb_tcp.py <box-ip> --probe`: an answer like `device::ro.product.name=p230;...` means it is on.

## Quick start

### 1. Get the APKs

Download `n1-remote.apk` and `n1-standby-helper.apk` from Releases, or build them (see below).

### 2. Install the standby helper on the box

**Install it and open it once; that's the whole setup.** Either way works:

- **No computer**: copy `n1-standby-helper.apk` to a USB stick, install it with the box's file manager, then open "N1 standby helper" from the box's home screen. You need something to drive the box first: the original remote, a USB mouse, or an already paired phone.
- **With a computer** (needs `adb`): this installs the helper and opens it for you:

  ```bash
  scripts/box.sh <box-ip> install-helper
  ```

The first time it opens, the helper does three things through the box's own root ADB and shows each with ✔ / ✘:

- enables its accessibility service, which receives the remote's power key;
- changes the box's `power_key_definition` from 1 (shut down) to 0 (do nothing), so a native power key from any remote can no longer shut the box down for good;
- maps the original Phicomm remote's power key to standby (the file is backed up first; one button on the screen restores it).

After every boot and every helper update it checks again and repairs the setup if something was reset (for example the accessibility service turned off). A "Restore and uninstall" button puts the box back to factory settings.

### 3. Pair the phone

1. Install `n1-remote.apk` on the phone, open it and allow the Nearby devices permission. Android 13 and later also ask for notifications: allow them, the notification buttons need it.
2. Find the box. Open the Bluetooth settings page on the box and leave it open: the box can only be found while that page is showing. Without a remote, open it with `python3 tools/adb_tcp.py <box-ip> 'am start -a android.settings.BLUETOOTH_SETTINGS'`. Then tap Find on the phone and pick "p230 (Phicomm N1)" under Nearby; p230 is the N1's default Bluetooth name. The list only shows classic Bluetooth devices that can take the remote, not BLE-only gadgets.
   - Or skip the scan: enter the box's Bluetooth address and tap Connect. It is in the box's Settings → About → Status, or run `python3 tools/adb_tcp.py <box-ip> 'settings get secure bluetooth_address'`.
3. Picking the box starts pairing. Both the phone and the box ask to confirm the pairing; confirm on both.
   - The box shows two dialogs at once (phone-style Settings and TV Settings). The one on top is the one that counts.
   - **No remote?** Run this on your computer first, then pick the box (or tap Connect) on the phone. It only confirms a dialog that names your phone:

     ```bash
     python3 tools/confirm_pairing.py <box-ip> "<phone's Bluetooth name>"
     ```
4. The status turns green ("Connected") and you're done. The app reconnects by itself next time.
   - When the link drops (box rebooted, phone out of range), the app retries every 5 seconds for 3 minutes, and starts again when you come back to the app. Tapping Disconnect is on purpose and does not reconnect.
   - **Stays connected in the background.** Once connected, the app shows a "Connected" notification (a foreground service). Switch to another app or lock the screen and the link stays up, ready the moment you come back. The notification has two buttons: Standby / wake and Disconnect.
   - After Disconnect the notification goes away. From then on the app is back to the old rule (Android only lets the foreground app act as a Bluetooth HID device): leave it and the remote disconnects, come back and it reconnects in about 2 seconds. Without the notification permission the link still stays up in the background; you just don't see the notification.
   - While connected, **the phone's own volume keys change the box volume**; hold to keep changing. When not connected they change the phone volume as usual.

### 4. Power and the other keys

- **OTT key (red)**: power. One press puts the box in standby: back to the home screen, screen blanked, HDMI signal off, CPU capped at 500 MHz, TV asked to sleep over CEC. Press again to wake.
- **TV key**: turns only the TV on or off over HDMI-CEC; the box is not affected. The TV must support CEC and have it on.
- **Play / pause key (orange)**: pauses and resumes video. It is a standard media key: the player gets it first, then the system media controls if the player ignores it. This used to be a voice key, which did nothing because the box has no voice app; 2.6.0 made it play / pause. Coming from an older version, update the key descriptor stored on the box once (next section).
- **Touchpad**: the Touchpad button between the two power keys swaps the D-pad for a swipe area. **One swipe moves one item**, however long or fast. **Swipe and keep your finger resting** to keep moving, like holding a D-pad key; lift to stop. Movement starts about 0.75 s after your finger stops, and the pad turns blue to show it. A tap is OK. Tap "D-pad" to switch back; the app remembers your choice.
- **Holding a key**: hold a D-pad or volume key and the box repeats it.

## The original Phicomm remote

The original remote (device ID `7045:1820`) sends `KEY_POWER` for its power key. Android handles that key before any app sees it, so the helper never gets it. The first time the helper opens, it maps that key to the TV key in the remote's key layout, which makes it send the same key as the phone. It also turns the remote's voice key (which does nothing, the box has no voice app) into play / pause, like the orange key in the phone app (helper 1.4 and later). The helper's screen can switch both back any time. From a computer, these two commands do the same, also backing the file up first:

```bash
scripts/box.sh <box-ip> patch-remote
```

```bash
scripts/box.sh <box-ip> unpatch-remote
```

The second command restores it. The change is a single line, see [box-config/](box-config/): `key 116 POWER` → `key 116 TV`.

Compatible remotes may use another device ID. After pairing, read its `vendor` and `product` from `dumpsys input` and write a `Vendor_XXXX_Product_YYYY.kl`. If `/system/usr/keylayout/` has no file for that ID, put it in `/data/system/devices/keylayout/` and leave the system partition alone.

## Why a "soft" standby

webpad's firmware (based on official V2.32) cannot really sleep, and cannot be powered on remotely. Details in [docs/findings.md](docs/findings.md) (Chinese):

- Phicomm removed Android sleep in official V2.32: `PowerManagerService.goToSleepNoUpdateLocked()` just returns false. webpad's firmware leaves that part untouched, which is why the power key can only be set to "shut down".
- After a real shutdown nothing can wake the box: the N1 has no IR receiver and Bluetooth loses power too. Only unplugging it helps.
- So the helper keeps the box running and only turns off the picture, the HDMI signal and the TV. Bluetooth stays connected, so it can always wake.

The helper catches the key as an accessibility service. The actual work (writing sysfs, sending CEC) needs root, and SELinux blocks a normal app from it, so the helper runs it through the box's own root adbd on `127.0.0.1:5555`. That connection never leaves the box.

## Key map

| Key | Phone sends (HID Consumer) | Box kernel key | Android key |
|---|---|---|---|
| OK | 0x41 Menu Pick | KEY_SELECT | DPAD_CENTER |
| Up / Down / Left / Right | 0x42–0x45 | KEY_UP/DOWN/LEFT/RIGHT | DPAD_* |
| Back | 0x224 AC Back | KEY_BACK | BACK |
| Home | 0x223 AC Home | KEY_HOMEPAGE | HOME |
| Menu | 0x40 Menu | KEY_MENU | MENU |
| Volume + / − | 0xE9 / 0xEA | KEY_VOLUMEUP/DOWN | VOLUME_UP/DOWN |
| Play / pause | 0xCD Play/Pause | KEY_PLAYPAUSE | MEDIA_PLAY_PAUSE (since 2.6.0; used to be a voice key that did nothing) |
| OTT power | 0x89 Media Select TV | KEY_TV | TV → helper toggles standby |
| TV power | 0x8D Media Select Program Guide | KEY_PROGRAM | GUIDE → helper turns the TV on/off over CEC |

The descriptor is a 14-bit bitmap (plus 2 padding bits, 2 bytes) with no letter keys, so the box does not treat the phone as a physical keyboard and the on-screen keyboard still shows up.

## Keys missing after an app update

The box stores the key descriptor it got at pairing time and never reads it again. If a new app version adds or changes keys (2.2.0 added the TV key, 2.6.0 turned the voice key into play / pause), the box won't know them. Either:

- unpair on both the phone and the box, then pair again; or
- swap the stored descriptor without re-pairing:

  ```bash
  scripts/box.sh <box-ip> update-descriptor <phone-bluetooth-address>
  ```

  It backs up the box's Bluetooth config, turns Bluetooth off, swaps the descriptor and turns Bluetooth back on. The phone's Bluetooth address is in its Settings → About.

## Caveats

- **The box's adbd serves only one TCP client at a time.** If `adb connect` from a computer stays connected, the helper can't run its standby commands (it retries 3 times). Run `adb disconnect` when done; `scripts/box.sh` and `tools/adb_tcp.py` disconnect on their own.
- **Security**: the N1's network ADB runs as root with no authorization, so anything on the same network can take full control of the box. That is how the firmware ships; this project only relies on it. Don't keep the box on a network you don't trust.
- **Never open TV Settings' shutdown screen on the box** (`android.intent.action.SHOW_SHUTDOWN`). It counts down and really shuts the box down after a few seconds, and only unplugging brings it back.
- In standby the box keeps running, only with the CPU capped at 500 MHz, so it still uses more power than a real shutdown. I have not measured how much.
- The box can't read CEC replies, so for the TV key the helper only remembers whether it last turned the TV on or off. If you used the TV's own remote, that guess may be wrong; press the TV key once more.
- Phone volume keys driving the box: the code is there, but it was not verified with a working hardware key (the test phone's volume-up key is broken; its interrupt count never moved).
- Turning off the HDMI signal and putting the TV to sleep over CEC need the box connected to a TV with CEC on (Anynet+, SimpLink, Bravia Sync and so on, depending on the brand). Without a TV only the commands themselves were verified.
- After a power cut the box boots normally (awake) and the helper starts by itself.

## Building

Needs a JDK and the Android SDK packages `platforms;android-35` and `build-tools;35.0.0`. No Gradle or Android Studio.

```bash
./build.sh
```

Output goes to `dist/`:

- `n1-remote.apk`: the phone app
- `n1-standby-helper.apk`: the box helper
- `n1-tools.dex`: box-side test tools

Point `ANDROID_HOME` at your SDK (default: Homebrew's `android-commandlinetools`). The signing key defaults to `n1remote.keystore`, created on the first build and ignored by git. Keep using the same key, or Android refuses to install an update over the old version.

### CI (GitHub Actions)

On every push to `main`, every `v*` tag and every pull request, [`.github/workflows/build.yml`](.github/workflows/build.yml):

1. installs the SDK packages;
2. checks the scripts' syntax;
3. builds all three outputs and uploads them as an artifact.

By default CI signs with a throwaway key. Those APKs are fine for testing but **won't install over** builds signed with your own key. To have CI sign with yours, add two repository secrets (Settings → Secrets):

- `KEYSTORE_B64`: the output of `base64 -i n1remote.keystore`;
- `KS_PASS`: the keystore password.

**Releasing**: with those two secrets set, push a `v*` tag and CI builds, signs and publishes the release. The release notes are the tag's annotation, followed by the SHA-256 checksums and a link to the build:

```bash
git tag -a v2.3.1 -F release-notes.md
```

```bash
git push origin v2.3.1
```

As a guard, a tag build without the release key fails instead of publishing APKs signed with a throwaway key that wouldn't install over earlier releases.

## Self-test

With the helper installed, one command checks the whole box side. Only Python 3 is needed, no adb:

```bash
python3 tools/selftest.py <box-ip>
```

It registers a virtual Bluetooth remote on the box, which takes exactly the same path as the phone, then checks and prints PASS / FAIL for:

- kernel key codes match the descriptor, Android picks `Generic.kl`, and the device counts as a remote (not a keyboard or gamepad);
- all 11 keys reach Android with the right key codes;
- a D-pad key can be held (that is what key repeat relies on);
- the helper gets the TV key;
- standby caps the CPU at 500 MHz and swallows other keys; wake restores it.

With `--phicomm` it also checks the original remote's mapping, by posing as device ID `7045:1820` and pressing its power key twice.

It takes about a minute. The box screen moves around meanwhile and ends on the home screen. Make sure nothing else holds the box's adb while it runs.

## Developer tools

Push `dist/n1-tools.dex` to the box and run it with `app_process`:

- `n1.tools.UhidTest`: registers a virtual HID remote on the Bluetooth bus through `/dev/uhid` and presses the keys you ask for. It takes exactly the kernel and Android path of a real Bluetooth remote, so you can check key mapping without real hardware.
  - `UHID_ID=vvvv:pppp` poses as a given device ID; `UHID_NAME=name` sets the device name.
  - Arguments: a number presses that bit, `wN` waits N ms, `hB:N` holds bit B for N ms.
- `n1.tools.BtCtl off|on|state`: turns the box's Bluetooth off or on.

```bash
python3 tools/adb_tcp.py <box-ip> --push dist/n1-tools.dex /data/local/tmp/n1-tools.dex
```

```bash
adb shell 'UHID_ID=7045:1820 CLASSPATH=/data/local/tmp/n1-tools.dex app_process /system/bin n1.tools.UhidTest 3000 0 w5000 0'
```

The example poses as the original remote and presses its power key (bit 0) twice. When testing the standby helper, don't keep an adb session open in the foreground: it holds adbd and the helper can't run its commands. Start the test with `setsid` in the background and disconnect; see [docs/findings.md](docs/findings.md).

## Third-party content

- Key icons from [Material Icons](https://github.com/google/material-design-icons), Apache License 2.0.
- The key layout files in `box-config/` come from the box firmware; their header says AOSP, Apache License 2.0.

## License

[GPL-3.0](LICENSE). The Apache License 2.0 material listed above keeps its own license.
