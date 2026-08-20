package org.sensorhub.impl.process.universalcontroller;

import net.opengis.swe.v20.Boolean;
import org.sensorhub.impl.sensor.universalcontroller.helpers.UniversalControllerComponent;
import net.opengis.swe.v20.*;
import org.sensorhub.api.processing.OSHProcessInfo;
import org.sensorhub.impl.process.universalcontroller.helpers.AbstractControllerTaskingProcess;
import org.vast.process.ProcessException;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

public class ControllerMAVLinkProcess extends AbstractControllerTaskingProcess {

    DataRecord bodyVelocity;

//    DataRecord landing;
//    DataRecord rtl;
//    DataRecord takeoff;
//    DataComponent takeoffAltitudeParam;


    public static final OSHProcessInfo INFO = new OSHProcessInfo(
            "controllerMavlinkProcess",
            "Process to send MAVLink commands",
            null,
            ControllerMAVLinkProcess.class);

    public ControllerMAVLinkProcess() {
        super(INFO);

        GeoPosHelper geo = new GeoPosHelper();

        outputData.add("bodyVelocity", bodyVelocity = geo.createRecord()
                .addField("velocity", geo.newVelocityVectorNED(
                        SWEHelper.getPropertyUri("PlatformVelocity"),
                        "m/s"))
                .addField("yawRate", fac.createQuantity()
                        .label("Yaw Rate")
                        .definition(SWEHelper.getPropertyUri("YawRate"))
                        .uomCode("deg/s")
                        .dataType(DataType.FLOAT))
                .build());

        paramData.getComponent(0).getData().setIntValue(0);
    }

    /**
     * x: right joystick up
     * y: right joystick
     * z: left joystick
     * yaw: left joystick
     */

    /**
     *  UP/DOWN are flipped and on left joystick
     *  LEFT/RIGHT are flipped on left joystick
     *  right joy stick doesnt work
     *
     */
    @Override
    public void updateOutputs() throws ProcessException {
        // x-y velocity
        // rx-ry heading
        // dpad up and down
        // a takeoff
        // b land

        // Controller reference
        // x = left stick up (1.0), down (-1.0)
        // y = left stick right (1.0), down (-1.0)
        // rx = right stick ""
        // ry = right stick ""

        // Velocity reference
        // X +forward -backward
        // Y +right -left
        // Z +down -up
        // YawRate turns +right -left

        // TODO: Might have error of continuous landing command if 0,0,0 is sent repeatedly. May need to switch all outputs to just be a single DataChoice output

        float currentX = fac.getComponentValueInput(UniversalControllerComponent.X_AXIS);
        float currentY = fac.getComponentValueInput(UniversalControllerComponent.Y_AXIS);
        float currentZ = fac.getComponentValueInput(UniversalControllerComponent.Z_AXIS);
        float currentRZ = fac.getComponentValueInput(UniversalControllerComponent.RZ_AXIS);

        float sensitivity = 5.0f;
        float yawSensitivity = 100.0f;

        // Velocity
        // TODO: Add sensitivity modifiers
        bodyVelocity.getData().setFloatValue(0, -currentRZ  * sensitivity);
        bodyVelocity.getData().setFloatValue(1, currentZ * sensitivity);

        bodyVelocity.getData().setFloatValue(2, currentY * sensitivity);
        // Yaw rate
        bodyVelocity.getData().setFloatValue(3, currentX * yawSensitivity);
    }
}

