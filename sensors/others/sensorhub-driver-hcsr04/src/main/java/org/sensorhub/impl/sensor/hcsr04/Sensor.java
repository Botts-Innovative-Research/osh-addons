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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import org.sensorhub.api.comm.ICommProvider;
import org.vast.sensorML.SMLHelper;
import org.vast.swe.SWEHelper;


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
    private DigitalInput input;
    private DigitalOutput output;

    public Sensor() {}

    private void setInput() {
        try {
            DigitalInputConfig inputConfig = DigitalInput.newConfigBuilder(pi4j)
                    .id("gpio-input")
                    .name("GPIO Input")
                    .address(Integer.valueOf(config.gpioInput))
                    .pull(PullResistance.PULL_DOWN) // This determines the default state of the pin when there is no signal. (0 volts)
                    .build();
            DigitalInputProvider digitalInputProvider = pi4j.provider("pigpio-digital-input");
            input = digitalInputProvider.create(inputConfig);
        } catch (Exception e) {
            System.out.println("ERROR SETTING INPUT");
            System.out.println(e);
        }
    }

    private void setOutput() {
        try {
            DigitalOutputConfig outputConfig = DigitalOutput.newConfigBuilder(pi4j)
                    .id("gpio-output")
                    .name("GPIO Output")
                    .address(Integer.valueOf(config.gpioOutput))
                    .shutdown(DigitalState.LOW)
                    .initial(DigitalState.LOW)
                    .build();
            DigitalOutputProvider digitalOutputProvider = pi4j.provider("pigpio-digital-output");
            output = digitalOutputProvider.create(outputConfig);
        } catch (Exception e) {
            System.out.println("ERROR SETTING OUTPUT");
            System.out.println(e);
        }
    }

    // The sensor is triggered when the output pin is set to high for 10 microseconds
    private void initiateSensorReading() {
        output.state(DigitalState.HIGH);

        try {
            TimeUnit.MICROSECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        output.state(DigitalState.LOW);
    }

    @Override
    protected void doInit() throws SensorHubException {
        System.out.println("Initializing sensor...");

        pi4j = Pi4J.newAutoContext();
        setInput();
        setOutput();
    }

    @Override
    protected void updateSensorDescription() {}
    
    @Override
    protected void doStart() throws SensorHubException {
        System.out.println("Starting sensor...");

        initiateSensorReading();

        // Read result
        // TODO Is it possible to miss some time? What if the input pin is set to high before this code executes? I think it's possible to miss sometime but maybe it doesn't matter
        //  Or is the hardware pretty much guaranteed to be slower that the hardware?
        //  Can we do this with a listener instead?

        // continuously read the input pin if it's state is low. once it's state is not low, we'll have the timestamp of when it was switched to high
        Timestamp start = new Timestamp(System.currentTimeMillis());
        while (input.state().equals(DigitalState.LOW)) {
            start = new Timestamp(System.currentTimeMillis());
        }

        // continuously read the input pin if it's state is high. once it's state is not high, we'll have the timestamp of when it was switched to low
        Timestamp end = new Timestamp(System.currentTimeMillis());
        while (input.state().equals(DigitalState.HIGH)) {
            end = new Timestamp(System.currentTimeMillis());
        }

        long duration = end.getTime() - start.getTime();
        System.out.println("DURATION: " + duration);

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