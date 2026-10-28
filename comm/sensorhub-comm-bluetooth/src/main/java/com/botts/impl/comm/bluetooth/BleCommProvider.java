/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package com.botts.impl.comm.bluetooth;

import org.simplejavable.Adapter;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;


/**
 * Generic BLE GATT communication provider using SimpleBLE.
 * <p>
 * Connects to a BLE peripheral, discovers the configured GATT service, and bridges
 * characteristic read/write/notify operations into standard Java InputStream/OutputStream.
 * <p>
 * The InputStream prepends a 2-byte big-endian length prefix to each GATT message so that
 * consumers can detect message boundaries in the byte stream. The OutputStream buffers
 * bytes until flush() and sends the complete buffer as a single GATT write.
 * <p>
 * This module is device-agnostic — configure the service and characteristic UUIDs
 * for any BLE peripheral.
 */
public class BleCommProvider extends AbstractModule<BleCommProviderConfig> implements ICommProvider<BleCommProviderConfig> {

    private Peripheral peripheral;
    private BleInputStream bleInputStream;
    private BleOutputStream bleOutputStream;

    private BluetoothUUID serviceUuid;
    private BluetoothUUID readCharUuid;
    private BluetoothUUID writeCharUuid;
    private BluetoothUUID notifyCharUuid;


    @Override
    protected void doStart() throws SensorHubException {
        BleProtocolConfig proto = config.protocol;

        if (proto.serviceUUID.isBlank())
            throw new SensorHubException("Service UUID must be configured");
        if (proto.readCharUUID.isBlank())
            throw new SensorHubException("Read Characteristic UUID must be configured");
        if (proto.writeCharUUID.isBlank())
            throw new SensorHubException("Write Characteristic UUID must be configured");

        serviceUuid = new BluetoothUUID(proto.serviceUUID);
        readCharUuid = new BluetoothUUID(proto.readCharUUID);
        writeCharUuid = new BluetoothUUID(proto.writeCharUUID);
        notifyCharUuid = proto.notifyCharUUID.isBlank() ? null : new BluetoothUUID(proto.notifyCharUUID);

        try {
            // Get the first available BLE adapter
            List<Adapter> adapters = Adapter.getAdapters();
            if (adapters.isEmpty()) {
                throw new SensorHubException("No Bluetooth adapters found on this system");
            }
            Adapter adapter = adapters.get(0);
            getLogger().info("Using BLE adapter: {} [{}]", adapter.getIdentifier(), adapter.getAddress());

            // Scan for the target device
            peripheral = findDevice(adapter, proto);
            if (peripheral == null) {
                throw new SensorHubException(
                        "No BLE device found (timeout=" + proto.scanTimeoutMs + "ms, " +
                        "address='" + proto.deviceAddress + "', nameFilter='" + proto.deviceNameFilter + "')");
            }

            // Connect
            getLogger().info("Connecting to BLE device: {} [{}]",
                    peripheral.getIdentifier(), peripheral.getAddress());
            peripheral.connect();
            getLogger().info("Connected to BLE device, MTU: {}", peripheral.getMtu());

            // Create the bridged streams
            bleInputStream = new BleInputStream();
            bleOutputStream = new BleOutputStream(peripheral, serviceUuid, writeCharUuid);

            // Subscribe to notifications if a notify characteristic is configured
            if (notifyCharUuid != null) {
                peripheral.notify(serviceUuid, notifyCharUuid, data -> {
                    // Notification received — read all available messages from the read characteristic
                    readAllAvailable();
                });
                getLogger().debug("Subscribed to notifications on {}", proto.notifyCharUUID);
            }

            // Initial read sweep — pick up any data already queued on the device
            readAllAvailable();

        } catch (SensorHubException e) {
            throw e;
        } catch (Exception e) {
            throw new SensorHubException("Failed to start BLE connection", e);
        }

        getLogger().info("BLE communication provider started");
    }

    /**
     * Scan for a BLE device matching the configured address, name filter, or any connectable device.
     */
    private Peripheral findDevice(Adapter adapter, BleProtocolConfig proto) {
        getLogger().info("Scanning for BLE devices (timeout={}ms)...", proto.scanTimeoutMs);

        adapter.scanFor(proto.scanTimeoutMs);
        List<Peripheral> results = adapter.scanGetResults();

        getLogger().debug("Scan found {} devices", results.size());

        for (Peripheral p : results) {
            if (!p.isConnectable()) continue;

            // Match by specific device address
            if (!proto.deviceAddress.isBlank()) {
                if (p.getAddress().equalsIgnoreCase(proto.deviceAddress)) {
                    getLogger().debug("Matched by address: {} [{}]", p.getIdentifier(), p.getAddress());
                    return p;
                }
                continue;
            }

            // Match by name substring
            if (!proto.deviceNameFilter.isBlank()) {
                String name = p.getIdentifier();
                if (name != null && name.toLowerCase().contains(proto.deviceNameFilter.toLowerCase())) {
                    getLogger().debug("Matched by name: {} [{}]", p.getIdentifier(), p.getAddress());
                    return p;
                }
                continue;
            }

            // No filter — accept first connectable device
            getLogger().debug("Accepting first connectable device: {} [{}]", p.getIdentifier(), p.getAddress());
            return p;
        }

        return null;
    }

    /**
     * Read all available messages from the read characteristic and enqueue them
     * into the input stream. Stops when a read returns empty data.
     */
    private void readAllAvailable() {
        try {
            while (true) {
                byte[] data = peripheral.read(serviceUuid, readCharUuid);
                if (data == null || data.length == 0) break;
                bleInputStream.enqueueMessage(data);
            }
        } catch (Exception e) {
            getLogger().debug("BLE read sweep ended: {}", e.getMessage());
        }
    }


    @Override
    public InputStream getInputStream() {
        return bleInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return bleOutputStream;
    }


    @Override
    protected void doStop() throws SensorHubException {
        if (bleInputStream != null) {
            bleInputStream.closeStream();
        }
        if (bleOutputStream != null) {
            bleOutputStream.closeStream();
        }

        if (peripheral != null) {
            try {
                if (notifyCharUuid != null) {
                    peripheral.unsubscribe(serviceUuid, notifyCharUuid);
                }
                peripheral.disconnect();
            } catch (Exception e) {
                getLogger().debug("Error disconnecting BLE: {}", e.getMessage());
            }
        }

        peripheral = null;
        bleInputStream = null;
        bleOutputStream = null;

        getLogger().info("BLE communication provider stopped");
    }
}
