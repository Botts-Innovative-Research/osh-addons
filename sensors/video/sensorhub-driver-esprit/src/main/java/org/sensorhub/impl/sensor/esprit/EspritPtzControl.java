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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.net.MalformedURLException;
import java.util.List;

import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.command.CommandException;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.impl.sensor.onvif.OnvifCameraDriver;
import org.sensorhub.impl.sensor.onvif.OnvifPtzControl;
import org.sensorhub.impl.sensor.videocam.VideoCamHelper;
import org.sensorhub.impl.sensor.videocam.ptz.PtzPresetsHandler;
import org.slf4j.Logger;
import org.vast.data.DataChoiceImpl;


/**
 * <p>
 * Implementation of sensor interface for generic Esprit Cameras using IP
 * protocol. This particular class provides control of the Pan-Tilt-Zoom
 * (PTZ) capabilities.
 * </p>
 * 
 * @author Mike Botts
 * @since October 30, 2014
 */
public class EspritPtzControl extends OnvifPtzControl
{

    Logger logger = org.slf4j.LoggerFactory.getLogger(EspritPtzControl.class);
    VideoCamHelper videoHelper = new VideoCamHelper();

    private static final String PTZ_PRESETGOTO = "PTZ_PRESETGOTO";

    protected EspritPtzControl(OnvifCameraDriver driver) {
        super(driver);
    }

    protected void init() {
        super.init();
        List<String> presets = new ArrayList<>();
        for (Presets preset : Presets.values()) {
            presets.add(preset.name());
        }
        Category aux = videoHelper.createCategory().addAllowedValues(presets).build();
        commandData.addItem("auxCommand", aux);
    }

    @Override
    protected boolean execCommand(DataBlock command) throws CommandException {
        // associate command data to msg structure definition

        DataChoice commandMsg = (DataChoice) commandData.copy();

        if (command == null)
            return false;

        commandMsg.setData(command);

        DataComponent component = ((DataChoiceImpl) commandMsg).getSelectedItem();
        String itemID = component.getName();
        DataBlock data = component.getData();

        if (itemID.equals("auxCommand")) {
            try {
                Presets preset = Presets.valueOf(data.getStringValue());
                int presetVal = preset.getPresetNum();

                URL url = new URL(((EspritCameraDriver)parentSensor).getHostUrl() + "PTZ_PRESETGOTO=" + presetVal);
                InputStream is = url.openStream();
                is.close();
                return true;
            } catch (IllegalArgumentException e) {
                return super.execCommand(command);
            } catch (MalformedURLException e) {
                logger.error("Malformed URL", e);
                return false;
            } catch (IOException e) {
                logger.error("Error reading URL", e);
                return false;
            }
        } else {
            return super.execCommand(command);
        }
    }
}
