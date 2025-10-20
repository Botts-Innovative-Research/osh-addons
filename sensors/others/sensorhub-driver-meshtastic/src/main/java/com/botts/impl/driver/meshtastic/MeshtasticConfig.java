/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package com.botts.impl.driver.meshtastic;

import org.sensorhub.api.comm.CommProviderConfig;
import org.sensorhub.api.comm.ICommNetwork;
import org.sensorhub.api.comm.NetworkConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.SensorConfig;

public class MeshtasticConfig extends SensorConfig {

    /**
     * The unique identifier for the configured sensor (or sensor platform).
     */
    @DisplayInfo.Required
    @DisplayInfo(desc = "Serial number or unique identifier")
    public String serialNumber = "001";

    @DisplayInfo.AddressType(ICommNetwork.NetworkType.BLUETOOTH_LE)
    @DisplayInfo.FieldType(DisplayInfo.FieldType.Type.REMOTE_ADDRESS)
    public String bleAddress;

    public CommProviderConfig<?> commSettings;

}
