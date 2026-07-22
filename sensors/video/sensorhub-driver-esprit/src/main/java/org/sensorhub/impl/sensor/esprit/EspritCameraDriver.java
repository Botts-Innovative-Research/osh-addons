/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
Developer are Copyright (C) 2014 the Initial Developer. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.esprit;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.opengis.sensorml.v20.IdentifierList;
import net.opengis.sensorml.v20.Term;
import org.sensorhub.impl.comm.RobustHTTPConnection;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.onvif.OnvifCameraDriver;
import org.sensorhub.impl.sensor.onvif.OnvifPtzControl;
import org.vast.sensorML.SMLFactory;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Implementation of sensor interface for generic Esprit Cameras using IP
 * protocol
 * </p>
 * 
 * @author Mike Botts
 * @since October 30, 2014
 */
public class EspritCameraDriver extends OnvifCameraDriver {

    public static final String HTTP_API_BASE = "/httpapi/SendPTZ?action=sendptz&PTZ_CHANNEL=1&";

    protected String hostUrl;


    public EspritCameraDriver() throws SensorHubException {
    }

    protected String getHostUrl() {
        setAuth();
        return hostUrl;
    }

    protected void setAuth() {
        ClientAuth.getInstance().setUser(config.networkConfig.user);
        if (config.networkConfig.password != null)
            ClientAuth.getInstance().setPassword(config.networkConfig.password.toCharArray());
    }

    protected void doInit() throws SensorHubException {
        super.doInit();
        ptzControlInterface = new EspritPtzControl(this);
        hostUrl = "http://" + config.networkConfig.remoteHost + ":" + config.networkConfig.remotePort + HTTP_API_BASE;
    }
}