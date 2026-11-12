# RTL-SDR Receiver Driver

This driver integrates the **RTL-SDR** (RTL2832U-based software-defined radio) receiver into OpenSensorHub. It provides direct native control, high-performance in-line spectral processing, integrated software demodulators (AM, NFM, WFM) with browser-based Web Audio streaming, and an interactive real-time RF Spectrum Analyzer & Waterfall Spectrogram inside the OpenSensorHub Admin UI.

Unlike multi-channel direction-finding systems, this driver focuses on controlling, tuning, listening to, and streaming from a **single receiver dongle**.

---

## Supported Hardware

Compatible with any USB receiver based on the **Realtek RTL2832U** controller chip combined with common tuners:
- **RTL-SDR Blog V3 and V4**
- **Nooelec NESDR** (SMArt, SMArTee, Nano)
- **Generic RTL2832U dongles** (with Rafael Micro R820T, R820T2, R828D, Elonics E4000, or Fitipower FC0012/FC0013 tuners)

Typical tuning range: **24 MHz – 1766 MHz** (or 0.5 MHz – 24 MHz in Direct Sampling HF mode).

---

## Architecture

- **JNA Native Bindings**: Direct C library bindings to `librtlsdr` via Java Native Access (JNA), with no external daemon (e.g. `rtl_tcp`) or WebSocket proxy needed.
- **Self-Contained Binaries**: Bundles pre-compiled 64-bit Windows native binaries (`rtlsdr.dll` and `libusb-1.0.dll`) directly within the JAR, automatically extracted and preloaded at runtime.
- **In-Line DSP & Demodulation**:
  - **FFT Spectrum Engine**: IQ sample streams are windowed using Hann weighting and transformed to frequency-domain power spectral density in **dBFS** in real time.
  - **Panoramic Wideband Sweep Engine**: High-speed stepped frequency hopping (5 MHz, 10 MHz, 20 MHz) with FIFO transient flush, DC spike suppression, and seamless passband stitching.
  - **Software Audio Demodulator**: In-line real-time DSP supporting **AM** (Airband/HF), **NFM** (Ham/VHF/UHF/Marine), and **WFM** (Broadcast FM 88-108 MHz) with 75 µs de-emphasis and adjustable squelch.
- **In-Browser Web Audio & Admin UI**:
  - HTML5 Canvas RF Spectrum Analyzer and Waterfall Spectrogram embedded directly into the OpenSensorHub Admin Console.
  - Native HTML5 **Web Audio API** streaming for zero-install, in-browser radio listening.

---

## Installation & Prerequisites

Because the driver communicates directly with USB hardware via native C libraries, two components are required on the host system:
1. **USB Driver**: The generic WinUSB driver (Windows) or `udev` rules (Linux).
2. **Native Shared Library**: Bundled in the JAR for Windows 64-bit; or `librtlsdr.so` (Linux).

---

### Windows Setup

#### Step 1: Install WinUSB Driver using Zadig
By default, Windows installs digital TV (DVB-T) drivers for RTL2832U dongles. You must replace this with the generic **WinUSB** driver:
1. Plug your RTL-SDR dongle into a USB port.
2. Download and run **Zadig** (free, open source) from [https://zadig.akeo.ie/](https://zadig.akeo.ie/).
3. In Zadig, click **Options** → check **List All Devices**.
4. In the dropdown, select your RTL-SDR device. It will typically be named:
   - `Bulk-In, Interface (Interface 0)`
   - `RTL2838UHIDIR (Interface 0)`
   - `NESDR SMArt v5` / `Blog V4`
   > **Note:** If two interfaces appear, always pick **Interface 0**. Do not modify Interface 1 (the IR remote sensor).
5. For the target driver, select **WinUSB** (usually the default recommendation with a green arrow).
6. Click **Install Driver** (or **Replace Driver**). Wait for the confirmation prompt.

---

#### Step 2: Native Libraries (`rtlsdr.dll` and `libusb-1.0.dll`)

- **Bundled in the JAR (Zero-Dependency Default):**
  Pre-compiled 64-bit Windows DLLs (`rtlsdr.dll` and `libusb-1.0.dll`) are packaged directly inside the driver JAR under `win32-x86-64/`. The driver automatically extracts and preloads them at startup. **No external DLL installation or PATH configuration is required on 64-bit Windows systems!**

- **External Fallbacks (Optional):**
  If custom or external binaries are desired, the driver also scans:
  - `C:\Program Files\CubicSDR`
  - `C:\Program Files\PothosSDR\bin`
  - `C:\rtl-sdr`
  - System `PATH` or `-Djava.library.path`

---

### Windows PowerShell Verification & Diagnostics

Open a **PowerShell** prompt to verify the installation before launching OpenSensorHub:

#### 1. Verify USB Device Detection & Hardware ID
Checks that Windows detects the connected RTL2832U dongle (`VID 0BDA`, `PID 2838`):
```powershell
Get-PnpDevice -PresentOnly | Where-Object { $_.FriendlyName -match "RTL|Bulk-In|NESDR|Blog" -or ($_.HardwareID -match "VID_0BDA&PID_2838") } | Select-Object FriendlyName, Status, Class, InstanceId
```
*Expected output: `Status` should report `OK`.*

#### 2. Verify WinUSB Driver Binding
Ensures the WinUSB driver service is correctly bound to Interface 0:
```powershell
Get-PnpDevice -PresentOnly | Where-Object { $_.HardwareID -match "VID_0BDA&PID_2838" } | Get-PnpDeviceProperty -KeyName "DEVPKEY_Device_Service" | Select-Object InstanceId, Data
```
*Expected output: `Data` should report `WinUSB`.*

#### 3. Hardware Health Check (`rtl_test`)
If you have `rtl_test` installed from CubicSDR or Osmocom tools:
```powershell
rtl_test -t
```
*Expected output: Lists the tuner chip (e.g. `Found Rafael Micro R820T tuner`), supported gains, and reports 0 dropped samples.*

---

### Linux / Raspberry Pi Setup

#### 1. Install Packages
```bash
sudo apt update
sudo apt install -y rtl-sdr librtlsdr-dev
```

#### 2. Prevent Kernel DVB Driver Conflict (Blacklist)
Linux may load the default DVB TV kernel driver (`dvb_usb_rtl28xxu`). Create a blacklist file:
```bash
echo -e "blacklist dvb_usb_rtl28xxu\nblacklist rtl2832\nblacklist rtl2830" | sudo tee /etc/modprobe.d/nortlsdr.conf
```

#### 3. Grant USB Permissions (`udev` rules)
Allow non-root users to access the USB dongle:
```bash
sudo cp /lib/udev/rules.d/60-librtlsdr.rules /etc/udev/rules.d/ 2>/dev/null || \
echo 'SUBSYSTEM=="usb", ATTRS{idVendor}=="0bda", ATTRS{idProduct}=="2838", MODE="0666"' | sudo tee /etc/udev/rules.d/20-rtlsdr.rules

sudo udevadm control --reload-rules && sudo udevadm trigger
```

#### 4. Verification
```bash
lsusb -d 0bda:2838
rtl_test -t
```

---

## Admin UI Features

When viewing the RTL-SDR driver in the OpenSensorHub Admin UI (`http://localhost:8181/admin`), the driver provides an interactive spectrum and audio console:

### 1. In-Browser Audio Demodulator & Web Audio Player
- **Demodulation Modes**:
  - **WFM**: Wideband FM for broadcast radio (88 – 108 MHz).
  - **NFM**: Narrowband FM for amateur radio (2m/70cm), marine VHF, weather radio (NOAA ~162.4–162.55 MHz), and public safety.
  - **AM**: Amplitude Modulation with carrier envelope detection and AGC for civil aviation airband (118 – 137 MHz) and shortwave HF.
- **Audio Controls**:
  - `[ 🔈 Listen ]` / `[ 🔊 Playing ]` toggle button with instant browser Web Audio API playback.
  - **Volume Slider**: 0% to 100% audio gain.
  - **Squelch Slider**: -100 dBFS to -30 dBFS threshold to mute static when no carrier is present.

### 2. Panoramic Wideband Sweep Mode & Bandwidth Selector
- **Span Options**: **2 MHz (Stare)**, **5 MHz**, **10 MHz**, or **20 MHz** (Max).
  - **2 MHz (Stare Mode)**: High-speed continuous spectrum (~15–20 fps) with live in-browser Web Audio playback active.
  - **5, 10, 20 MHz (Sweep Modes)**: Stepped fast-sweeping engine that rapidly steps the R820T tuner across the span, flushes FIFO transients, suppresses center DC spikes, and stitches passbands into a panoramic composite spectrum.
  - **Refresh Rate**: Delivers **~2.4 full updates/second** across the entire 20 MHz FM broadcast band (88–108 MHz) or VHF airband (118–137 MHz), exceeding the 2 Hz target.
  - **Click-to-Listen Integration**: Clicking on any signal peak in a wide sweep and pressing `[ Listen ]` smoothly transitions back to 2 MHz stare mode on that exact frequency and begins playing the station audio.

### 3. Upper View (RF Spectrum Analyzer)
- Real-time power spectral density curve (dBFS vs. Frequency).
- Center frequency dashed marker and frequency grid axes.
- Peak-hold orange trace with live peak readout (frequency and dBFS).
- Interactive crosshairs and hovering frequency/power readout tooltips.

### 4. Lower View (Waterfall Spectrogram)
- Scrolling color-mapped time history showing frequency activity over time.
- High-visibility SDR heat colormap (Navy → Cyan → Green → Yellow → Red → White).

### 5. Interactive Tuning & Display Controls
- **Click-to-Tune**: Click anywhere along the spectrum or waterfall frequency axis to re-center the tuner.
- **Step Buttons**: Quick stepping via `-1M`, `-100k`, `+100k`, `+1M`, or direct numeric entry in MHz.
- **Auto dB**: Automatically tracks noise floor and peak signal dynamics, with manual floor (`Min dB`) and ceiling (`Max dB`) override sliders.
- **Averaging & Speed**: Configurable exponential moving average (Off, 2x, 4x, 8x) and waterfall scroll rate (1x, 2x, 4x).
- **Pause / Resume**: Freeze display for inspection without interrupting background sensor hub capture.

---

## Module Configuration

Below are the configuration properties for the RTL-SDR driver:

| Property | Type | Description | Default |
|---|---|---|---|
| `serialNumber` | String | Unique OpenSensorHub identifier | `"rtlsdr001"` |
| `deviceIndex` | int | USB dongle index (0 for first dongle) | `0` |
| `centerFrequency` | double | Initial center frequency in MHz | `100.0` |
| `sampleRate` | int | Sampling rate in SPS (e.g., 2048000, 2400000) | `2048000` |
| `sweepSpan` | double | Spectrum analyzer bandwidth in MHz (`2.0`, `5.0`, `10.0`, `20.0`) | `2.0` |
| `tunerGain` | double | Manual tuner gain in dB | `20.0` |
| `autoGain` | boolean | Enable automatic tuner gain control | `true` |
| `agcMode` | boolean | Enable RTL2832 digital AGC | `false` |
| `ppmCorrection` | int | Frequency oscillator offset in PPM | `0` |
| `directSampling` | int | Direct HF sampling mode (`0`=Off, `1`=I-branch, `2`=Q-branch) | `0` |
| `fftSize` | int | FFT bin size for spectrum output (e.g. 512, 1024, 2048) | `1024` |
| `audioMode` | Enum | Demodulator mode (`OFF`, `AM`, `NFM`, `WFM`) | `OFF` |
| `audioVolume` | int | Audio volume percentage (`0` to `100`) | `80` |
| `squelchThreshold`| double | Squelch cutoff threshold in dBFS | `-65.0` |

### Sample `config.json` Snippet

```json
{
  "sensors": [
    {
      "moduleClass": "org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver",
      "config": {
        "name": "RTL-SDR Receiver",
        "description": "RTL2832U Software-Defined Radio Receiver",
        "serialNumber": "rtlsdr001",
        "deviceIndex": 0,
        "centerFrequency": 100.0,
        "sampleRate": 2048000,
        "sweepSpan": 2.0,
        "autoGain": true,
        "tunerGain": 20.0,
        "agcMode": false,
        "ppmCorrection": 0,
        "directSampling": 0,
        "fftSize": 1024,
        "audioMode": "OFF",
        "audioVolume": 80,
        "squelchThreshold": -65.0,
        "enableSpectrumOutput": true,
        "enableSettingsOutput": true,
        "adminPanel": {
          "panelClass": "org.sensorhub.impl.sensor.rtlsdr.ui.RtlSdrAdminPanel"
        }
      }
    }
  ]
}
```

---

## Troubleshooting

- **`Unable to load library 'rtlsdr': The specified module could not be found.`**:
  Windows found `rtlsdr.dll`, but its required dependency `libusb-1.0.dll` was missing from the search path, or the DLL architecture (32-bit vs. 64-bit) did not match your 64-bit JVM. With the bundled DLLs in the driver JAR, this is resolved automatically.
- **`Failed to open rtlsdr device (error -1)`**:
  The USB device is not recognized or the WinUSB driver has not been assigned. Run Zadig, ensure **List All Devices** is checked, and assign **WinUSB** to **Interface 0**.
- **`usb_claim_interface error -6` (Linux)**:
  The default Linux kernel DVB-T driver has claimed the device. Ensure the blacklist in `/etc/modprobe.d/nortlsdr.conf` is in place, unplug the dongle, and plug it back in.
