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
import org.simplejavable.Peripheral;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;


/**
 * Generic BLE connection manager using SimpleBLE.
 * <p>
 * Scans for and connects to a BLE peripheral based on device address or name filter.
 * Exposes the connected {@link Peripheral} via {@link #getPeripheral()} so that
 * drivers can perform their own GATT service/characteristic operations directly.
 * <p>
 * This module is device-agnostic — it only manages the BLE connection lifecycle.
 * All protocol-specific GATT logic belongs in the driver.
 * <p>
 * Note: {@link #getInputStream()} and {@link #getOutputStream()} return {@code null}
 * because BLE is message-oriented. Drivers should use {@link #getPeripheral()} instead.
 */
public class BleCommProvider extends AbstractModule<BleCommProviderConfig> implements ICommProvider<BleCommProviderConfig> {

    private Peripheral peripheral;


    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    protected void doStart() throws SensorHubException {
        BleProtocolConfig proto = config.protocol;

        try {
            List<Adapter> adapters = Adapter.getAdapters();
            if (adapters.isEmpty()) {
                throw new SensorHubException("No Bluetooth adapters found on this system");
            }
            Adapter adapter = adapters.get(0);
            getLogger().info("Using BLE adapter: {} [{}]", adapter.getIdentifier(), adapter.getAddress());

            peripheral = findDevice(adapter, proto);
            if (peripheral == null) {
                throw new SensorHubException(
                        "No BLE device found (timeout=" + proto.scanTimeoutMs + "ms, " +
                        "address='" + proto.deviceAddress + "', nameFilter='" + proto.deviceNameFilter + "')");
            }

            getLogger().info("Connecting to BLE device: {} [{}]",
                    peripheral.getIdentifier(), peripheral.getAddress());
            peripheral.connect();
            getLogger().info("Connected to BLE device: {} [{}], MTU: {}",
                    peripheral.getIdentifier(), peripheral.getAddress(), peripheral.getMtu());

        } catch (SensorHubException e) {
            throw e;
        } catch (Exception e) {
            throw new SensorHubException("Failed to start BLE connection", e);
        }
    }

    /**
     * Returns the connected BLE peripheral for direct GATT operations.
     *
     * @return the connected Peripheral, or null if not connected
     */
    public Peripheral getPeripheral() {
        return peripheral;
    }

    /**
     * Scan for a BLE device matching the configured address, name filter,
     * or any connectable device.
     */
    private Peripheral findDevice(Adapter adapter, BleProtocolConfig proto) throws Exception {
        getLogger().info("Scanning for BLE devices (timeout={}ms)...", proto.scanTimeoutMs);

        adapter.scanFor(proto.scanTimeoutMs);
        List<Peripheral> results = adapter.scanGetResults();

        getLogger().debug("Scan found {} devices", results.size());

        for (Peripheral p : results) {
            if (!p.isConnectable()) continue;

            // Match by specific device address
            if (!isBlank(proto.deviceAddress)) {
                if (p.getAddress().toString().equalsIgnoreCase(proto.deviceAddress)) {
                    getLogger().debug("Matched by address: {} [{}]", p.getIdentifier(), p.getAddress());
                    return p;
                }
                continue;
            }

            // Match by name substring
            if (!isBlank(proto.deviceNameFilter)) {
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
     * Not supported for BLE — drivers should use {@link #getPeripheral()} instead.
     * @return null
     */
    @Override
    public InputStream getInputStream() {
        return null;
    }

    /**
     * Not supported for BLE — drivers should use {@link #getPeripheral()} instead.
     * @return null
     */
    @Override
    public OutputStream getOutputStream() {
        return null;
    }

    @Override
    protected void doStop() throws SensorHubException {
        if (peripheral != null) {
            try {
                peripheral.disconnect();
                getLogger().info("BLE device disconnected");
            } catch (Exception e) {
                getLogger().debug("Error disconnecting BLE: {}", e.getMessage());
            }
        }
        peripheral = null;
    }
}
