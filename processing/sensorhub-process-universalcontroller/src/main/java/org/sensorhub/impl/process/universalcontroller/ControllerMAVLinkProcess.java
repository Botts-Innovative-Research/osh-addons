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

    DataRecord landing;
    DataRecord rtl;
    DataRecord takeoff;
    DataComponent takeoffAltitudeParam;


    public static final OSHProcessInfo INFO = new OSHProcessInfo(
            "controllerMavlinkProcess",
            "Process to send MAVLink commands",
            null,
            ControllerMAVLinkProcess.class);

    public ControllerMAVLinkProcess() {
        super(INFO);

        GeoPosHelper geo = new GeoPosHelper();

        paramData.add("altitude", takeoffAltitudeParam = fac.createQuantity().dataType(DataType.DOUBLE).uom("m").definition(SWEHelper.getPropertyUri("AltitudeAGL")).build());

        outputData.add("landing", landing = geo.createRecord()
                .definition(SWEHelper.getPropertyUri("Control"))
                .addField("disarm", geo.createBoolean()
                        .definition(SWEHelper.getPropertyUri("Disarm")))
                .build());

        outputData.add("takeoff", takeoff = geo.createRecord()
                .definition(SWEHelper.getPropertyUri("Control"))
                .addField("TakeoffAltitudeAGL", geo.createQuantity()
                        .definition(GeoPosHelper.DEF_ALTITUDE_GROUND))
                .build());

        outputData.add("rtl", rtl = geo.createRecord()
                .definition(SWEHelper.getPropertyUri("Control"))
                .addField("rtl", geo.createBoolean()
                        .definition(SWEHelper.getPropertyUri("rtl")))
                .build());

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
        float currentRX = fac.getComponentValueInput(UniversalControllerComponent.RX_AXIS);
        float currentRY = fac.getComponentValueInput(UniversalControllerComponent.RY_AXIS);

        float sensitivity = 10.0f;

        boolean isAPressed = fac.getComponentValueInput(UniversalControllerComponent.A_BUTTON) == 1.0f;
        boolean isBPressed = fac.getComponentValueInput(UniversalControllerComponent.B_BUTTON) == 1.0f;
        boolean isXPressed = fac.getComponentValueInput(UniversalControllerComponent.X_BUTTON) == 1.0f;


        // Takeoff
        if(isAPressed) {
            takeoffAltitudeParam.getData().setFloatValue(5.0f);
        } else {
            takeoffAltitudeParam.getData().setFloatValue(0.0f);
        }


        // Land
        if(isBPressed) {
            landing.getData().setBooleanValue(true);
        } else {
            landing.getData().setBooleanValue(false);
        }

        // RTL
        if(isXPressed) {
            rtl.getData().setBooleanValue(true);
        } else {
            rtl.getData().setBooleanValue(false);
        }

        // Velocity
        // TODO: Add sensitivity modifiers
        bodyVelocity.getData().setFloatValue(0, currentY  * sensitivity);
        bodyVelocity.getData().setFloatValue(1, currentX * sensitivity);
        bodyVelocity.getData().setFloatValue(2, currentRY * sensitivity);
        // Yaw rate
        bodyVelocity.getData().setFloatValue(3, currentRX * sensitivity);
    }
}
