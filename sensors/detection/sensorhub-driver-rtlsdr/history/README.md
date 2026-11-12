# RTL-SDR Driver Development Summary & Handoff

## 1. Overview & Current State
This driver connects OpenSensorHub to an RTL2832-based USB SDR receiver (such as RTL-SDR v3/v4) via direct JNA bindings to `librtlsdr.dll` and `libusb-1.0.dll` (located in `src/main/resources/win32-x86-64/`).

The driver operates in **2 MHz stare mode** (the wideband sweep functionality has been completely removed to maintain simplicity and stability).

- **Spectrum & Waterfall Visualization**: Working cleanly in the Admin UI at 2 MHz bandwidth.
- **Axis Styling**: Frequency and dB axis numbers and labels have been brightened for readability against the dark grid.
- **Tuning via Command Inputs**: Works reliably without interrupting streaming.
- **"Apply Changes" (General Tab Reconfiguration)**:
  - Previously, clicking "Apply Changes" invoked `AbstractModule.updateConfig()` which triggered `stop()` -> `init()` -> `start()`. Tearing down the USB stream and attempting to close and reopen the WinUSB handle in rapid succession provoked a Windows WinUSB kernel / IOCP cancel race condition inside `librtlsdr`, causing `(code: -3)` (`LIBUSB_ERROR_ACCESS` / device already claimed).
  - **Fix Applied**: `RtlSdrDriver.java` now overrides `updateConfig(RtlSdrConfig newConfig)`. When `isStarted() && newConfig.deviceIndex == config.deviceIndex`, configuration parameters (center frequency, tuner gain, auto gain, AGC, direct sampling, sample rate, audio mode, audio volume, squelch) are updated dynamically **in-place** directly on the active, open device handle. This works seamlessly just like Command Inputs without any USB teardown or device re-opening.
- **Current Issue to Address on Next Session**:
  - Stopping and then restarting the module from the Admin UI errors out.
  - If the dongle is physically unplugged and plugged back in, then initialized and started from the UI, it works again.
  - This confirms that during an explicit `stop()` from the UI, the WinUSB interface / endpoint or kernel driver state is not completely freed or gets into an unrecoverable state when reopened without a USB power-cycle/reset.

---

## 2. Key Architecture & File Locations
- **Module Directory**: `sensors/detection/sensorhub-driver-rtlsdr/`
- **Main Driver**: [`RtlSdrDriver.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/RtlSdrDriver.java)
- **Device Controller**: [`RtlSdrDevice.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/lib/RtlSdrDevice.java)
- **Native JNA Interface**: [`RtlSdrNative.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/lib/RtlSdrNative.java)
- **Configuration**: [`RtlSdrConfig.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/RtlSdrConfig.java)
- **DSP / Demodulation**: [`RtlSdrAudioDemodulator.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/dsp/RtlSdrAudioDemodulator.java)
- **Spectrum Output**: [`RtlSdrOutputSpectrum.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputSpectrum.java)
- **UI Component & Frontend**:
  - Java: [`RtlSdrSpectrumComponent.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/ui/RtlSdrSpectrumComponent.java)
  - JS: [`rtlsdr_spectrum.js`](src/main/resources/org/sensorhub/impl/sensor/rtlsdr/ui/rtlsdr_spectrum.js)
  - CSS: [`rtlsdr_spectrum.css`](src/main/resources/org/sensorhub/impl/sensor/rtlsdr/ui/rtlsdr_spectrum.css)
- **Test Application**: `test/sensorhub-test` running with module data in `test/sensorhub-test/.moduledata/`.

---

## 3. Detailed Root Cause Analysis for Stop/Start Issue
When the driver starts streaming, `RtlSdrDevice.startStreaming()` spawns a daemon thread `RtlSdr-AsyncReader` that calls the native blocking function:
```c
int rtlsdr_read_async(rtlsdr_dev_t *dev, rtlsdr_read_async_cb_t cb, void *ctx, uint32_t buf_num, uint32_t buf_len);
```
Under Windows WinUSB:
1. `rtlsdr_read_async` allocates multiple WinUSB asynchronous read requests (URBs) queued to the WinUSB handle.
2. When `rtlsdr_cancel_async` is called, WinUSB cancels the pending requests.
3. However, the OS kernel completes URBs asynchronously. Calling `rtlsdr_close()` before all URBs are reaped causes memory access violations (`0xC0000005`) or leaves the WinUSB interface claimed by the OS driver.
4. On the other hand, if `doStop()` only stops streaming (`rtlsdr_cancel_async`) while leaving `devPointer` open, attempting `startStreaming()` again without reinitializing the native endpoint buffers fails or times out because `librtlsdr`'s internal async transfer buffers are destroyed upon cancel and cannot be re-used without reallocating.

### Recommended Next Steps for Investigation:
1. In `RtlSdrDevice.java`, test performing a full, clean native teardown on `close()`:
   - Call `rtlsdr_cancel_async(devPointer)`.
   - Wait for `asyncThread` to terminate (`join()`).
   - Allow a 500ms delay.
   - Call `rtlsdr_close(devPointer)` inside a proper try-catch.
   - Also consider calling `libusb_reset_device` (if exposed or reachable) or ensuring all WinUSB handles are closed so the device can be reopened cleanly without unplugging.
2. In `RtlSdrDriver.java`:
   - Verify `doStop()` vs `doStart()` lifecycle: `doStop()` should call `device.close()` and null out `device`, ensuring `doStart()` creates a fresh `new RtlSdrDevice()` with a clean open.

---

## 4. Analog Demodulators and ADS-B / AIS Decoders Added
### A. Analog Demodulators (AM, NFM, WFM, USB, LSB, CW)
- **USB / LSB**: Phasing method using a 31-tap linear-phase Hilbert Transform FIR filter ($I[n-15] \mp \hat{Q}[n]$), voice bandpass filter (300-2800 Hz), and syllabic speech AGC.
- **CW**: Beat Frequency Oscillator (BFO tone, default 700 Hz) heterodyne mixer paired with a 2nd-order IIR biquad bandpass filter ($Q=5$) and tone AGC for Morse code reception.
- Added UI options for USB, LSB, and CW to [`rtlsdr_spectrum.js`](src/main/resources/org/sensorhub/impl/sensor/rtlsdr/ui/rtlsdr_spectrum.js).

### B. Operating Mode Selector
- Added `OperatingMode` to [`RtlSdrConfig.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/RtlSdrConfig.java):
  - `SPECTRUM_AUDIO`: Standard RF spectrum monitor and audio demodulation.
  - `ADSB_RECEIVER`: Auto-tunes to 1090.0 MHz at 2.0 MSPS; activates ADS-B Mode S decoder.
  - `AIS_RECEIVER`: Auto-tunes to 162.0 MHz at 2.048 MSPS; activates Marine AIS GMSK dual-channel decoder.
- Exposed operating mode and audio mode selection to OSH Command Inputs via [`RtlSdrControlReceiver.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/controls/RtlSdrControlReceiver.java).

### C. 1090 MHz ADS-B Decoder & Output
- **DSP Decoder**: [`AdsbDecoder.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/dsp/AdsbDecoder.java) detects 8 µs Mode S preambles, slices 112-bit Extended Squitter PPM data, computes 24-bit Mode S CRC parity, decodes callsign (TC 1-4), airborne position w/ barometric altitude & CPR odd/even lat/lon (TC 9-18), velocity & true track (TC 19), and emergency/squawk (TC 28).
- **Sensor Output**: [`RtlSdrOutputAdsb.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAdsb.java) publishes structured SWE DataRecords (`sampleTime`, `icaoAddress`, `callsign`, `pos`, `alt_baro`, `gs`, `track`, `alt_rate`, `squawk`, `alert`, `emergency`, `isOnGround`) and registers moving aircraft features of interest (`MovingFeature`).

### D. Marine VHF AIS Decoder & Output
- **DSP Decoder**: [`AisDecoder.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/dsp/AisDecoder.java) downconverts Channel A (161.975 MHz, -25 kHz IF) and Channel B (162.025 MHz, +25 kHz IF), runs GMSK discriminator demodulation, recovers symbol clock via DPLL at 5 samples/symbol (48 kSPS), performs NRZI decoding, HDLC flag detection, bit unstuffing, CRC-16 (CCITT) check, and parses AIS Types 1, 2, 3 (Class A position), Type 5 (ship name/callsign), and Types 18 & 19 (Class B).
- **Sensor Output**: [`RtlSdrOutputAis.java`](src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAis.java) publishes structured SWE DataRecords (`samplingTime`, `messageId`, `mmsi`, `vesselName`, `callsign`, `sog`, `positionAccuracy`, `location`, `cog`, `heading`, `navStatus`, `shipType`) and registers vessel moving features.

---

## 5. Updates & Fixes (November 8 – November 12, 2026)

### A. OGC/SWE UCUM Unit Standard Compliance
- Fixed invalid UCUM unit atoms in SWE DataRecord definitions that previously threw `IllegalArgumentException: Unknown UCUM unit atom`:
  - **Knots**: Changed from `"kn"` to standard UCUM atom `"[kn_i]"` in both [`RtlSdrOutputAdsb.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAdsb.java) and [`RtlSdrOutputAis.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAis.java).
  - **Feet**: Changed from `"ft"` to standard UCUM atom `"[ft_i]"` in [`RtlSdrOutputAdsb.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAdsb.java).
  - **Feet per minute**: Changed from `"ft/min"` to `"[ft_i]/min"` in [`RtlSdrOutputAdsb.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/outputs/RtlSdrOutputAdsb.java).

### B. Mode S CRC-24 Parity & Syndrome Resolution
- In [`AdsbDecoder.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/dsp/AdsbDecoder.java), replaced dynamic bitwise polynomial division with the authoritative `dump1090` 112-element table-driven lookup (`MODES_CHECKSUM_TABLE`).
- Returns `0` (zero syndrome) when computed across 112 bits for error-free frames.
- Verified in [`TestRtlSdrDriver.java`](../src/test/java/TestRtlSdrDriver.java) with verified standard Mode S test vector `8D40621D58C382D690C8AC2863A7` (DF=17 Extended Squitter).

### C. Headless Test Suite Resilience
- In [`TestRtlSdrDriver.java`](../src/test/java/TestRtlSdrDriver.java), guarded hardware-dependent tests (`testDeviceOnly` and `testStartStopRestart`) with `isDeviceAvailable()` inside a `try/catch (Throwable)`.
- Prevents test failures from `UnsatisfiedLinkError` when running `./gradlew.bat test` on build machines or environments without an attached RTL-SDR dongle.
- All 9 unit tests pass with `BUILD SUCCESSFUL`.

### D. Connected Systems API Service & Database Stream Viewer Fix
- When inspecting datastreams in the Federated DB, the Admin UI's `DatabaseStreamPanel` threw `NullPointerException: Cannot invoke getPublicEndpointUrl() because getHttpServer() is null`.
- Root cause: `DatabaseStreamPanel` queries `ConSysApiService` to generate public REST URLs, but `ConSysApiService` had `"autoStart": false` in [`config_rtlsdr.json`](../../../../test/sensorhub-test/src/main/resources/config_rtlsdr.json).
- **Fix**: Changed `"autoStart": true` for `ConSysApiService` in [`config_rtlsdr.json`](../../../../test/sensorhub-test/src/main/resources/config_rtlsdr.json), resolving the NPE and enabling full inspection of live ADS-B and AIS records.

### E. Live Over-the-Air Hardware Verification
- **ADS-B 1090 MHz**: Verified live with physical RTL-SDR dongle. Successfully decoded live Mode S Extended Squitter packets, parsed aircraft callsigns, barometric altitudes, velocities, resolved odd/even CPR positions, and rendered live aircraft moving features in the OSH Federated DB stream viewer.
- **Marine AIS 162 MHz**: Verified live over the air in Orlando, FL. Successfully locked onto dual-channel 161.975 / 162.025 MHz transmissions, demodulated GMSK symbols via DPLL, unstuffed HDLC frames, and emitted valid live vessel records.
- **Linux Portability**: Verified that JNA dynamically loads `librtlsdr.so` on Linux from system library paths without modification.

### F. UI Waterfall & Spectrum Sizing Adjustments
- **Height Extension**:
  - The waterfall canvas was originally 180px and the Vaadin component was locked to 500px, resulting in bottom clipping.
  - In [`RtlSdrAdminPanel.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/ui/RtlSdrAdminPanel.java), increased `spectrumComponent` height from `500px` to `780px`, and set layout to `layout.setWidth("100%"); layout.setHeightUndefined();`.
  - In [`rtlsdr_spectrum.js`](../src/main/resources/org/sensorhub/impl/sensor/rtlsdr/ui/rtlsdr_spectrum.js), increased `waterfallCanvas` height from `180px` to `280px` to display 55% more history.

---

## 6. Current Issues to Address Next

1. **Spectrum & Waterfall Blank Display (Reported 11/9)**:
   - On recent launch, the spectrum and waterfall canvases appeared blank.
   - Items to check upon resuming:
     - Check browser DevTools Console (`F12`) for any JavaScript exceptions in `rtlsdr_spectrum.js`.
     - Verify if `pushTimer` in [`RtlSdrSpectrumComponent.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/ui/RtlSdrSpectrumComponent.java) is receiving valid `SpectrumFrame` objects from `outputSpectrum.getLatestSpectrumFrame()`.
     - Verify that sample dispatching in [`RtlSdrDriver.java`](../src/main/java/org/sensorhub/impl/sensor/rtlsdr/RtlSdrDriver.java) is actively feeding `outputSpectrum.processSamples()` during the active operating mode.
     - Check if the canvas element sizing or auto-dB range scaling encountered zero/NaN bounds.
2. **USB Start / Stop Lifecycle Bug**:
   - During an explicit `stop()` from the Admin UI, the WinUSB handle is not cleanly released, requiring a USB unplug/replug before restarting.
   - Implement clean teardown sequence: `rtlsdr_cancel_async` -> `join()` worker thread -> `rtlsdr_reset_buffer` -> `rtlsdr_close` with post-close settling delay, and create fresh `RtlSdrDevice` instances upon `doStart()`.

---

## 7. Linking Workspaces Between Laptop and Desktop

To transfer and keep the project synchronized between your laptop and desktop, choose one of the following methods:

### Method 1: Git Remote (Recommended - GitHub / GitLab / Gitea)
If using GitHub or a Git server:
1. Commit local changes on your laptop:
   ```bash
   git add -A
   git commit -m "Add ADS-B, AIS, analog demodulators and UI enhancements"
   git push origin <your-branch-name>
   ```
2. On your desktop machine, fetch and check out the branch:
   ```bash
   git fetch origin
   git checkout <your-branch-name>
   git pull origin <your-branch-name>
   ```

### Method 2: Direct Git Remote over Local Network (LAN / File Share)
You can directly link the two machines without pushing to the internet:
1. Share the `IdeaProjects` folder on your laptop over the local network (Windows File Sharing / SMB), e.g. `\\LAPTOP-NAME\IdeaProjects\osh-addons`.
2. On your desktop machine, open PowerShell in your `osh-addons` repository and add the laptop as a Git remote:
   ```bash
   git remote add laptop "\\LAPTOP-NAME\IdeaProjects\osh-addons"
   git fetch laptop
   git merge laptop/master  # or checkout laptop/branch
   ```
3. Whenever you make changes on one machine, you can simply pull directly from the other over your local Wi-Fi / LAN.

### Method 3: Git Bundle (Flash Drive / Single File Transfer)
If transferring via USB drive or without direct network connectivity:
1. On your laptop, create a single self-contained Git bundle file:
   ```bash
   git bundle create rtlsdr-update.bundle HEAD master
   ```
2. Copy `rtlsdr-update.bundle` to your desktop via USB drive.
3. On your desktop, pull directly from the bundle:
   ```bash
   git pull path/to/rtlsdr-update.bundle master
   ```
   *(All commit history, branches, and diffs are preserved exactly as if pulled from a server.)*

