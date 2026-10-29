# Meshtastic Driver

This driver enables an OSH Node to communicate with a Meshtastic radio using Protobuf messages. It supports two connection modes: **Serial (USB)** and **Bluetooth Low Energy (BLE)**.

## Connection Options

### Option 1: Serial (USB)

Uses the **JSSC Serial Comm Provider** (`sensorhub-comm-jssc`).

**Required serial settings:**
| Setting | Value |
|---------|-------|
| Port Name | Your device port (e.g., `/dev/tty.usbserial-0001` on macOS, `/dev/ttyUSB0` on Linux) |
| Baud Rate | **115200** |
| Data Bits | 8 |
| Stop Bits | 1 |
| Parity | NONE |

**Radio configuration:** In the Meshtastic app, enable serial with Protobuf mode:
- Settings → Serial → Enabled: **ON**, Mode: **Protobufs**

> **Note:** Close any other application using the serial port (Meshtastic app, CLI, etc.) before starting the driver.

### Option 2: Bluetooth Low Energy (BLE)

Uses the **BLE Communication Provider** (`sensorhub-comm-bluetooth`).

**Required settings:**
| Setting | Value |
|---------|-------|
| Device Name Filter | Your radio's BLE name (e.g., `b562` — the last 4 hex digits of the node ID) |
| BLE Connection (in driver config) | **Enabled** |

The driver handles all Meshtastic-specific BLE GATT operations (service/characteristic UUIDs) automatically — no UUID configuration is needed in the comm provider.

> **Note:** Only one BLE client can connect to a Meshtastic radio at a time. Disconnect the Meshtastic desktop/phone app before starting the driver.

## OSH Node Configuration

1. Launch your OSH Node.
2. Add a new **Meshtastic Driver** module.
3. Select and configure the **Communication Provider**:
   - For Serial: choose **JSSC Serial Comm Provider** and set baud rate to **115200**.
   - For BLE: choose **BLE Communication Provider**, enter the device name filter, and enable **BLE Connection** in the driver config.
4. Apply the configuration.
5. Start the driver.

As Protobuf messages are received from the Meshtastic radio, sensor outputs will appear in the driver's output stream.

## Sending Text Messages

This driver includes support for sending text messages through the Meshtastic network.

### To send a message:

- To message a specific node: enter the Node ID of the destination.
- To broadcast a message: enter `broadcast` (case-insensitive).

Then type your message and select **Send Command**.

## Meshtastic Radio Setup

1. Follow the [Meshtastic Getting Started](https://meshtastic.org/docs/getting-started/) guide for initial configuration.
2. Flash your radio with the official [Meshtastic Firmware](https://flasher.meshtastic.org/) if not already done.
3. For serial connections, configure the radio's serial settings in the [Meshtastic app](https://meshtastic.org/docs/getting-started/initial-config/):
   - Settings → Serial → Enabled: **ON**, Mode: **Protobufs**
4. For BLE connections, no additional radio configuration is needed — BLE is enabled by default on most Meshtastic devices.

## Developer Notes

The Meshtastic Java classes (`MeshProtos`) are automatically generated from the Meshtastic protobuf definitions at build time using the [Meshtastic protobufs repo](https://github.com/meshtastic/protobufs).

When you first clone this repository, the generated classes do not exist in the source tree. To compile and access them in your code, run:

```
./gradlew build -x test -x osgi
```

This will:
1. Generate Java classes from the protobuf definitions into `sensorhub-driver-meshtastic/build/generated`.
2. Compile them into the project's classpath so your IDE can recognize imports like `org.meshtastic.proto.MeshProtos`.

These generated sources are not committed to Git, so you always need to build the project before using or editing code that depends on them.
