/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
import org.sensorhub.impl.SensorHub;

import java.io.File;

/**
 * Test runner that launches an OpenSensorHub instance with the RTL-SDR driver configured.
 */
public class TestSensorHubRtlSdr {
    public static void main(String[] args) throws Exception {
        File f = new File("src/main/resources/config_rtlsdr.json");
        if (!f.exists()) {
            f = new File("test/sensorhub-test/src/main/resources/config_rtlsdr.json");
        }
        if (!f.exists()) {
            java.net.URL res = TestSensorHubRtlSdr.class.getClassLoader().getResource("config_rtlsdr.json");
            if (res != null) {
                f = new File(res.toURI());
            }
        }

        String configFile = f.exists() ? f.getAbsolutePath() : "src/main/resources/config_rtlsdr.json";
        System.out.println("Starting OpenSensorHub with config: " + configFile);
        SensorHub.main(new String[] {configFile, "storage"});
    }
}
