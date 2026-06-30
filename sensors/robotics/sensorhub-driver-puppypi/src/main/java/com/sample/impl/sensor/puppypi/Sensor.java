/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2021 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
package com.sample.impl.sensor.puppypi;

import com.sample.impl.sensor.puppypi.controls.MovementControl;
import com.sample.impl.sensor.puppypi.outputs.BatteryOutput;
import com.sample.impl.sensor.puppypi.outputs.PositionOutput;
import com.sample.impl.sensor.puppypi.outputs.VideoOutput;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sensor driver providing sensor description, output registration, initialization and shutdown of driver and outputs.
 *
 * @author your_name
 * @since date
 */
public class Sensor extends AbstractSensorModule<Config> {

    private static final Logger logger = LoggerFactory.getLogger(Sensor.class);

    BatteryOutput batteryOutput;
    VideoOutput videoOutput;
    PositionOutput positionOutput;
    MovementControl movementControl;

    @Override
    public void doInit() throws SensorHubException {

        super.doInit();

        // Generate identifiers
        // generateUniqueID("[URN]", config.serialNumber);
        this.uniqueID = "urn:osh:sensor:puppypi"; // todo change to include serial number
        generateXmlID("[XML-PREFIX]", config.serialNumber);

        // Outputs
        batteryOutput = new BatteryOutput(this);
        videoOutput = new VideoOutput(this);
        positionOutput = new PositionOutput(this);

        addOutput(batteryOutput, false);
        addOutput(videoOutput, false);
        addOutput(positionOutput, false);

        batteryOutput.doInit();
        videoOutput.doInit();
        positionOutput.doInit();

        // Control streams
//        movementControl = new MovementControl(this);
//        addControlInput(movementControl);
//        movementControl.doInit();
    }

    @Override
    public void doStart() throws SensorHubException {

        if (null != batteryOutput) {

            // Allocate necessary resources and start outputs
//            batteryOutput.doStart();
        }

        // TODO: Perform other startup procedures
    }

    @Override
    public void doStop() throws SensorHubException {

        if (null != batteryOutput) {

//            batteryOutput.doStop();
        }

        // TODO: Perform other shutdown procedures
    }

    @Override
    public boolean isConnected() {

        // Determine if sensor is connected
//        return batteryOutput.isAlive();
        return true;
    }
}
