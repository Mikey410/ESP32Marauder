# ProS3 + Android over USB-C

This port adds a **headless Unexpected Maker ProS3** target and a native Android
USB console. The phone is the display and keyboard; Marauder runs on the board.
No Wi-Fi hotspot, Bluetooth pairing, cloud account, soldering, or external screen
is needed. Intended phone: Galaxy S25 Ultra (Android 8+ USB-host devices supported).

## Build status

Use only artifacts from a **successful ProS3 USB phone build** for the exact
source commit. Compilation is not a physical-device test. First-use checks below
must still be performed on the ProS3 and phone. This is an initial development
port, not an upstream-supported hardware release.

## Download and install

1. In this fork's Actions tab, open a successful **ProS3 USB phone build**.
   Download `pros3-usb-firmware` and `marauder-usb-android` from its artifacts.
   Forks may require enabling Actions first. The workflow runs for this port's
   pull request, or can be run manually once present on the default branch.
2. Extract the firmware ZIP on your PC. `SOURCE_COMMIT.txt` and `SHA256SUMS.txt`
   identify the exact source and images. The **full** image includes bootloader,
   partition table, OTA initialization and application.
3. Connect the ProS3 to the PC with a data cable. Install esptool:
   `py -m pip install esptool==4.8.1`.
4. Close serial monitors. Replace COM5 if Windows assigns a different port:

   ```powershell
   py -m esptool --chip esp32s3 --port COM5 write_flash 0x0 pros3-usb-full.bin
   ```

   This replaces the existing firmware and partition layout. Back up any existing
   data you need first. If automatic connection fails, hold BOOT, tap RESET,
   release BOOT, then retry. Press RESET after flashing if needed.
   **Never write `pros3-usb-app.bin` at address 0x0** (application offset: 0x10000).
5. Extract the Android ZIP on your phone and install `app-debug.apk`, allowing
   installation from your file manager if Android prompts. This development APK
   is debug-signed; different CI runs can have different debug keys. If updating
   reports a signature conflict, uninstall the earlier build first.
6. Disconnect from the PC. Connect **phone USB-C → ProS3 USB-C** with a data cable.
   The phone acts as USB host and powers the board. If USB role negotiation fails,
   use a USB-C OTG adapter on the phone and a USB-A-to-C data cable to the board.
7. Open **Marauder USB**, tap **Connect**, and approve Android USB access.
   Tap **Help**, **Wi-Fi scan**, **STOP**, then **Networks**. The console also accepts
   Marauder's existing text commands. BLE scan and Devices use `sniffbt` and `list -b`.

## What this includes

- 16 MB flash; **QSPI** PSRAM enabled (8 MB on the ProS3; do not select OPI PSRAM).
- ESP32-S3 hardware USB Serial/JTAG CDC at 115200, 8-N-1; USB CDC enabled at boot.
- Wi-Fi/BLE capabilities compiled from Marauder, CSI enabled; no fictional display,
  SD card, GPS, battery gauge, or external LED pin assignments.
- Touch shortcuts and a bounded live console with arbitrary single-line commands.
  Existing hardware-dependent commands still require their corresponding hardware.
- USB command framing handles split packets and CRLF without blocking scan updates.
  Lines over 512 bytes are discarded, rather than executing a truncated command.
- Startup waits at most 1.5 seconds for USB; the phone may connect later.
- App has no network permission and does not save transcripts or commands to disk.
  Output is capped at roughly 64K characters, so it is **not a lossless PCAP recorder**.

Marauder itself may persist its own settings. The phone app does not make all
firmware operations ephemeral. 5 GHz and Bluetooth Classic are not added by this
port. Phone GPS and PCAP export are not implemented.

## Connection behavior

Disconnect sends `stopscan` on a best-effort basis and closes the serial port.
Backgrounding an established session does the same; return and tap Connect.
Use **STOP before unplugging**: cable loss cannot deliver a stop command, and a
separately powered board may continue its current operation. The app only lists
Espressif CDC devices; with more than one connected, choose the ProS3 explicitly.

USB traffic is independent of the Wi-Fi/BLE radio, so changing radio channels or
running a BLE scan does not intentionally tear down the phone connection. Real
throughput, power draw and behavior under high output still need hardware testing.

## Reproduce builds

Firmware workflow `.github/workflows/pros3-usb.yml` installs Arduino-ESP32 **3.3.4**,
NimBLE **2.3.8**, and the same library versions as upstream's hardware workflow.
Only this board is compiled. FQBN:

```
esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=custom,PSRAM=enabled,USBMode=hwcdc,CDCOnBoot=cdc
```

Copy `installer/partitions/pros3_usb.csv` to `esp32_marauder/partitions.csv`
for this target only. It provides two 6 MiB app slots and SPIFFS within 16 MiB.
Compile `esp32_marauder` with `compiler.cpp.extra_flags=-DMARAUDER_PROS3`
and `upload.maximum_size=6291456`.
The workflow retains upstream's linker compatibility configuration.
Android: JDK 17, Android SDK 35, Gradle 8.9; run from `phone/android`:

```
gradle :app:assembleDebug :app:lintDebug
```

The USB driver is mik3y's `usb-serial-for-android` 3.9.0 (MIT), fetched from JitPack.
App and port sources use the repository's license; dependency licenses remain
with their respective authors. No release signing credentials are included.

## Physical acceptance checks

- Boot with phone disconnected; attach later and get a `help` response.
- Confirm device information identifies ProS3, flash is 16 MB, and boot logs do
  not report `PSRAM not available`.
- Start Wi-Fi scan; stop; list nearby networks. Repeat for BLE.
- Disconnect/reconnect, deny/regrant USB permission, rotate, background/resume.
- Leave a busy scan running for ten minutes: console remains responsive and STOP
  works. Inspect power/USB stability on your actual cable and phone.
- Check existing desktop USB serial operation still works at 115200.
