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

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.*;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.api.common.SensorHubException;

import com.pi4j.Pi4J;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * xxx
 * </p>
 *
 * @author Philip Khaisman
 * @since Nov, 2024
 */
public class Sensor extends AbstractSensorModule<Config> {
    private Context pi4j;
    private DigitalInput gpioInput;
    private DigitalOutput gpioOutput;
    private Output sensorOutput;
    private Control controlInterface;

    public Sensor() {}

    private void setGpioInput() {
        try {
            DigitalInputConfig inputConfig = DigitalInput.newConfigBuilder(pi4j)
                    .id("gpio-input")
                    .name("GPIO Input")
                    .address(Integer.valueOf(config.gpioInput))
                    .pull(PullResistance.PULL_DOWN) // This determines the default state of the pin when there is no signal. (0 volts)
                    .build();
            DigitalInputProvider digitalInputProvider = pi4j.provider("pigpio-digital-input");
            gpioInput = digitalInputProvider.create(inputConfig);
        } catch (Exception e) {
            System.out.println("ERROR SETTING INPUT");
            System.out.println(e);
        }
    }

    private void setGpioOutput() {
        try {
            DigitalOutputConfig outputConfig = DigitalOutput.newConfigBuilder(pi4j)
                    .id("gpio-output")
                    .name("GPIO Output")
                    .address(Integer.valueOf(config.gpioOutput))
                    .shutdown(DigitalState.LOW)
                    .initial(DigitalState.LOW)
                    .build();
            DigitalOutputProvider digitalOutputProvider = pi4j.provider("pigpio-digital-output");
            gpioOutput = digitalOutputProvider.create(outputConfig);
        } catch (Exception e) {
            System.out.println("ERROR SETTING OUTPUT");
            System.out.println(e);
        }
    }

    private void setSensorOutput() {
        sensorOutput = new Output(this);
        addOutput(sensorOutput, false);
    }

    // The sensor is triggered when the output pin is set to high for 10 microseconds
    public void initiateSensorReading(boolean trigger) {
        if (trigger) {
            gpioOutput.state(DigitalState.HIGH);

            try {
                TimeUnit.MICROSECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            gpioOutput.state(DigitalState.LOW);
        }
    }

    public void readDistance() {
        // continuously read the input pin if it's state is low. once it's state is not low, we'll have the timestamp of when it was switched to high
        Timestamp start = new Timestamp(System.currentTimeMillis());
        Timestamp timeoutStart = new Timestamp(System.currentTimeMillis());
        int timeout = 30; // 23 milliseconds is the longest it should take the sensor to receive the echo pulse since it's max range is 400cm
        while (gpioInput.isLow()) {
            start = new Timestamp(System.currentTimeMillis());
            if (start.getTime() - timeoutStart.getTime() >= timeout) {
                System.out.println("Echo pulse was not detected");
                return;
            }
        }

        // continuously read the input pin if it's state is high. once it's state is not high, we'll have the timestamp of when it was switched to low
        Timestamp end = new Timestamp(System.currentTimeMillis());
        while (gpioInput.isHigh()) {
            end = new Timestamp(System.currentTimeMillis());
        }

        long pulseDuration = (end.getTime() - start.getTime()); // ms
        double speedOfSound = 34.3; // cm/ms
        long distance = (long) (pulseDuration * speedOfSound) / 2; // cm
        System.out.println(distance + " cm");

        sensorOutput.setData(distance);
    }

    @Override
    protected void doInit() throws SensorHubException {
        System.out.println("Initializing sensor...");

        super.doInit();
        generateUniqueID("urn:osh:sensor:hcsr04:", config.serialNumber);
        generateXmlID("HCSR04", config.serialNumber);

        controlInterface = new Control(this);
        addControlInput(controlInterface);
        controlInterface.init();

        pi4j = Pi4J.newAutoContext();
        setGpioInput();
        setGpioOutput();
        setSensorOutput();
    }

    @Override
    protected void updateSensorDescription() {
        synchronized (sensorDescLock) {
            super.updateSensorDescription();

            if (!sensorDescription.isSetDescription()) {
                sensorDescription.setDescription("Driver for HC-SR04 Ultrasonic Sensor on a raspberry pi");
            }
        }
    }

    @Override
    protected void doStart() throws SensorHubException {
        System.out.println("Starting sensor...");

        super.doStart();
        initiateSensorReading(true);
        readDistance();
    }

    @Override
    protected void doStop() throws SensorHubException {
        pi4j.shutdown();
    }

    @Override
    public void cleanup() throws SensorHubException {}

    @Override
    public boolean isConnected() {
        return true;
    }
}