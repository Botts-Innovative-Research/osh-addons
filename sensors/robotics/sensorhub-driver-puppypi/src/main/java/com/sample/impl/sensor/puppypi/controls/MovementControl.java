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

package com.sample.impl.sensor.puppypi.controls;

import net.opengis.swe.v20.*;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.api.command.CommandException;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import com.sample.impl.sensor.puppypi.Sensor;
import org.vast.swe.helper.GeoPosHelper;


public class MovementControl extends AbstractSensorControl<Sensor> {
    private static final String SENSOR_CONTROL_NAME = "Puppy Pi Control";
    private static final String SENSOR_CONTROL_LABEL = "puppy pi control";
    private static final String SENSOR_CONTROL_DESCRIPTION = "Controls movement of the puppy pi";

    DataComponent commandStruct;

    public MovementControl(Sensor parentSensor) {
        super("MovementControl", parentSensor);
    }


    @Override
    public DataComponent getCommandDescription() {
        return commandStruct;
    }


    public void doInit() {
        GeoPosHelper sweFactory = new GeoPosHelper();

        commandStruct = sweFactory.createVelocityVector("m/s")
                .name("velocity")
                .label("Velocity")
                .definition(SWEHelper.getPropertyUri("PlatformVelocity"))
                .description("xxx")
                .refFrame(SWEConstants.REF_FRAME_ENU)
                .build();
    }

    @Override
    protected boolean execCommand(DataBlock command) {
        DataComponent commandData = commandStruct.copy();
        commandData.setData(command);

        return true;
    }
}