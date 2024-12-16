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

package org.sensorhub.impl.sensor.hcsr04;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.api.command.CommandException;
import net.opengis.swe.v20.DataComponent;
import org.vast.swe.SWEHelper;


public class Control extends AbstractSensorControl<Sensor> {
    DataChoice commandData;
    DataRecord commandStruct;

    public Control(Sensor parentSensor) {
        super("control", parentSensor);
    }


    @Override
    public DataComponent getCommandDescription() {
        // TODO Auto-generated method stub
        return commandStruct;
    }


    @Override
    protected boolean execCommand(DataBlock command) throws CommandException {
        try {
            DataRecord commandData = commandStruct.copy();
            commandData.setData(command);
            DataComponent triggerComponent = commandData.getField("Trigger");
            boolean trigger = triggerComponent.getData().getBooleanValue();
            parentSensor.initiateSensorReading(trigger);
            parentSensor.readDistance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return true;
    }


    public void init() {
        var swe = new SWEHelper();

        commandStruct = swe.createRecord()
                .name(name)
                .updatable(true)
                .definition(SWEHelper.getPropertyUri("Sensor"))
                .label("HC-SR04 Sensor")
                .description("Triggers range reading")
                .addField("Trigger",
                        swe.createBoolean()
                                .name("Trigger reading")
                                .label("Triggers reading")
                                .definition(SWEHelper.getPropertyUri("TriggerControl"))
                                .description("Triggers the sensor to calculate distance to nearest observed object"))
                .build();
    }


    public void stop() {
        // TODO Auto-generated method stub

    }

}
