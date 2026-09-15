# GoPro Driver

## Overview

This module provides a sensor driver for GoPro cameras and GoPro recordings.
It extends the [FFMPEG driver](../sensorhub-driver-ffmpeg) and accepts the same connection strings,
but publishes an output for **every** stream the source carries rather than just the first video and audio track:

- One video output per video stream, named `video`, `video2`, `video3`, ...
  Dual-lens models (MAX, Fusion) record two video tracks.
- One audio output per audio stream, named `audio`, `audio2`, ...
- One telemetry output per GPMF stream, named `gpmf`, `gpmf2`, ...
  Every model since the HERO5 records a GPMF track alongside the video.
- One position output per GPMF stream, named `gps`, `gps2`, ...
  A location vector carrying the GPS fixes, so the rest of OpenSensorHub can treat them as a track.

Other stream kinds (subtitle, attachment, and data streams that are not GPMF, such as the `tmcd` timecode
and `fdsc` camera-info tracks in GoPro MP4s) are ignored.

### GPMF telemetry output

The GPMF output decodes the telemetry and publishes **one record per sensor stream per packet**, with the
camera's own scale divisors already applied. Each GPMF packet covers roughly a second of capture and
describes every stream the camera recorded over that interval — accelerometer, gyroscope, GPS, gravity
vector, ISO, shutter, temperature, and so on, at their individual sample rates (gyro at ~400 Hz,
accelerometer at ~200 Hz, GPS at ~18 Hz on a HERO5-era camera).

Which streams appear depends on the camera model, its firmware, and the capture settings, so the record is
self-describing rather than shaped around a fixed set of sensors:

| Field | Meaning |
|---|---|
| `sampleTime` | Time the packet was received |
| `streamTime` | Offset of these samples from the start of the recording, as the camera reported it (`STMP`); NaN if absent |
| `deviceName` | Recording device (`DVNM`), e.g. `Hero11 Black` |
| `streamKey` | Four-character GPMF key, e.g. `ACCL`, `GYRO`, `GPS5`, `GPS9` |
| `streamName` | Human-readable name as the camera reported it (`STNM`), e.g. `Accelerometer (z,x,y)` |
| `units` | Units as the camera reported them (`SIUN`/`UNIT`), e.g. `m/s2` |
| `elementCount` | Values per sample, e.g. 3 for a three-axis sensor |
| `sampleCount` | Samples in this record |
| `textValue` | Content of a text-valued stream such as the GPS UTC timestamp (`GPSU`); empty for numeric streams |
| `valueCount` | Total values, equal to `sampleCount` × `elementCount` |
| `values` | The samples in order, scale divisors applied |

The parser is a from-scratch implementation of the GPMF specification (there is no Java GPMF library
published), covering the full key-length-value tree, nested containers, 32-bit payload alignment, all
scalar sample types including the Q15.16/Q31.32 fixed-point and unsigned forms, per-element scale
divisors, and the `TYPE`-defined complex sample layouts that newer models use for combined streams such
as `GPS9`. A stream it cannot decode is logged and skipped without costing the rest of the payload, and a
malformed packet is dropped without stopping the telemetry that follows.
See [gopro/gpmf-parser](https://github.com/gopro/gpmf-parser/blob/main/docs/README.md) for the format.

A data stream is treated as GPMF when it carries the `gpmd` codec tag or a `GoPro MET` handler name, which is
how GoPro MP4 recordings mark the track. Sources that drop those markers can still be published with the
**Assume Untagged Data Streams Are GPMF** setting.

### GPS position output

The `gps` output publishes one record per GPS fix, so a payload carrying a second of `GPS5` samples yields
around eighteen records. `location` is a standard latitude/longitude/altitude vector, alongside `gpsTime`,
`speed2D`, `speed3D`, `fixType` (0 none, 2 = 2D, 3 = 3D, -1 unreported), and `dop`.

Both GPS layouts are read, and the field order in each is fixed by the GPMF specification:

- **`GPS9`** (HERO11 and newer) packs coordinates, speeds, fix time, precision, and fix quality into every
  sample, so each fix carries its own time and quality.
- **`GPS5`** (HERO5 through HERO10) carries only coordinates and speeds. Fix quality (`GPSF`), precision
  (`GPSP`), and time (`GPSU`) come from sibling streams that describe the whole run, so every fix from one
  payload reports the same three.

When a camera records both, `GPS9` wins.

#### Fix quality

**A GoPro reports coordinates even with no GPS lock, and those coordinates are meaningless** — they land
hundreds of kilometres away. In a MAX 2 test recording, 756 of 1888 fixes (40%) came back with
`fixType=0` and `dop=99.99`, placing the camera in the Atlantic Ocean, interleaved with the good fixes.

So **Require GPS Fix** is on by default, and drops any record the receiver had no lock for. Turning it off
will scatter the published track with bad positions. Either way the telemetry output still reports the raw
`GPS5`/`GPS9` streams as recorded, so nothing is lost for archival or debugging.

#### Altitude datum

GoPro cameras disagree on what their altitude is measured from — GoPro's own
[issue #4](https://github.com/gopro/gpmf-parser/issues/4) never resolved it, and the split is established
by [gopro-telemetry](https://github.com/JuanIrache/gopro-telemetry), which applies an EGM96 correction for
older models only:

| Camera | Altitude is |
|---|---|
| HERO8, MAX, and newer | height above mean sea level |
| HERO7 and earlier | height above the WGS84 ellipsoid |

Newer cameras settle it themselves: they declare the reference in a `GPSA` entry (the MAX 2 reports
`MSLV`). The driver reads that and **warns once** if it disagrees with the configured datum, naming the
setting to change. Cameras that declare nothing fall back to the **GPS Altitude Datum** setting.

The driver publishes the camera's readings **unchanged** either way, and uses the datum only to declare the
right coordinate reference system on the location vector — EPSG:5714 (MSL) or EPSG:4979 (WGS84
ellipsoidal). It does not convert between them; that would need a geoid grid.

## Configuration

When added to an OpenSensorHub node, the driver has the same **General**, **Connection**, and **Position**
properties as the [FFMPEG driver](../sensorhub-driver-ffmpeg/README.md), plus:

- **Publish GPMF Telemetry:**
  When checked, create an output for the GoPro GPMF telemetry track if the source carries one.
- **Assume Untagged Data Streams Are GPMF:**
  When checked, data streams with no GoPro marker (no `gpmd` codec tag and no `GoPro MET` handler name) are
  published as GPMF telemetry. Needed for some live streams and re-muxed files that drop the marker.
  Note that this also causes the `tmcd` and `fdsc` data tracks of a GoPro MP4 to be published as GPMF.
- **Publish GPS Position:**
  When checked, create the location output described above, in addition to reporting the GPS streams in the
  telemetry output.
- **Require GPS Fix:**
  When checked (the default), only publish position records the receiver had a lock for. See above — leaving
  this unchecked will scatter the track with meaningless positions.
- **GPS Altitude Datum:**
  Which reference surface the camera measures altitude from, used **only when the camera does not declare
  it** — **mean sea level** (default) or **WGS84 ellipsoid** (for the HERO7 and earlier). See above.

## Testing

`GpmfParserTest` and `GpmfGpsTest` build GPMF payloads byte-by-byte, so each structural feature of the
format is exercised in isolation and they run in the normal build.

`GpmfFileDumpTest` is a diagnostic that reads a real recording from `src/test/resources` and writes the
camera's full GPMF layout — stream tree, decoded streams, GPS fixes, and a fix-quality histogram over the
whole file — to `build/gpmf-dump.txt`. It is excluded from the normal build because it needs a large
recording and takes minutes. Run it when investigating a specific camera:

```
gradlew :sensorhub-driver-gopro:test --tests "*GpmfFileDumpTest"
```

It is what established the MAX 2's `SCAL` divisors, its `GPSA` value, the undocumented `#` type, and that
the Atlantic positions were unlocked fixes rather than a decode error.
