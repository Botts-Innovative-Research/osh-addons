package org.sensorhub.impl.sensor.vaisala;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;

/**
 * Descriptor classes provide access to informative data on the OpenSensorHub driver.
 */
public class VaisalaWeatherDescriptor extends JarModuleProvider implements IModuleProvider {
    /**
     * Retrieves the class implementing the OpenSensorHub interface necessary to perform SOS/SPS/SOS-T operations.
     *
     * @return The class used to interact with the sensor/sensor platform.
     */
    @Override
    public Class<? extends IModule<?>> getModuleClass() {
        return VaisalaWeatherSensor.class;
    }

    /**
     * Identifies the class used to configure this driver.
     *
     * @return The java class used to exposing configuration settings for the driver.
     */
    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass() {
        return VaisalaWeatherConfig.class;
    }
}