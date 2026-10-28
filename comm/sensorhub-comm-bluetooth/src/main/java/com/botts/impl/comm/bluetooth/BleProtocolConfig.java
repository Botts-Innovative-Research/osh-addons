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

import org.sensorhub.api.comm.ICommConfig;
import org.sensorhub.api.config.DisplayInfo;


/**
 * Protocol configuration for a BLE GATT connection.
 * All fields are generic — no device-specific logic belongs here.
 */
public class BleProtocolConfig implements ICommConfig {

    @DisplayInfo(label = "Device Address",
            desc = "BLE device address (MAC on Linux/Windows, UUID on macOS). Leave empty to auto-discover.")
    public String deviceAddress = "";

    @DisplayInfo(label = "Device Name Filter",
            desc = "Filter discovered devices by name (substring match). Used when Device Address is empty.")
    public String deviceNameFilter = "";

    @DisplayInfo(label = "Service UUID",
            desc = "GATT service UUID to connect to")
    public String serviceUUID = "";

    @DisplayInfo(label = "Read Characteristic UUID",
            desc = "GATT characteristic UUID for reading data from the device")
    public String readCharUUID = "";

    @DisplayInfo(label = "Write Characteristic UUID",
            desc = "GATT characteristic UUID for writing data to the device")
    public String writeCharUUID = "";

    @DisplayInfo(label = "Notify Characteristic UUID",
            desc = "GATT characteristic UUID for notifications (signals new data is available to read). Leave empty if not used.")
    public String notifyCharUUID = "";

    @DisplayInfo(label = "Requested MTU",
            desc = "Requested BLE Maximum Transmission Unit size in bytes")
    public int requestedMtu = 512;

    @DisplayInfo(label = "Scan Timeout (ms)",
            desc = "Maximum time in milliseconds to scan for BLE devices before giving up")
    public int scanTimeoutMs = 10000;
}
