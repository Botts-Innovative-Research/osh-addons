/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConfig;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.impl.sensor.rtlsdr.lib.RtlSdrDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct programmatic test for the RTL-SDR driver.
 */
public class TestRtlSdrDriver implements IEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TestRtlSdrDriver.class);

    public static void main(String[] args) {
        TestRtlSdrDriver test = new TestRtlSdrDriver();
        test.run();
    }

    public void run() {
        try {
            // Check connected RTL-SDR devices via native library
            int deviceCount = 0;
            try {
                deviceCount = RtlSdrDevice.getDeviceCount();
                logger.info("Found {} connected RTL-SDR device(s)", deviceCount);
                for (int i = 0; i < deviceCount; i++) {
                    logger.info("  Device [{}]: {}", i, RtlSdrDevice.getDeviceName(i));
                }
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Native librtlsdr is not found on java.library.path or system PATH: {}", e.getMessage());
            }

            // Configure driver
            RtlSdrConfig config = new RtlSdrConfig();
            config.deviceIndex = 0;
            config.centerFrequency = 100.0; // 100 MHz (FM broadcast)
            config.sampleRate = 2048000;
            config.autoGain = true;
            config.fftSize = 1024;

            RtlSdrDriver driver = new RtlSdrDriver();
            driver.setConfiguration(config);

            logger.info("Initializing RTL-SDR driver...");
            driver.init();

            logger.info("Driver initialized successfully!");
            logger.info("Registered outputs:");
            for (IStreamingDataInterface output : driver.getObservationOutputs().values()) {
                logger.info("  - Output: {} ({})", output.getName(), output.getRecordDescription().getName());
                output.registerListener(this);
            }

            if (deviceCount > 0) {
                logger.info("Starting RTL-SDR driver with physical device...");
                driver.start();

                // Stream for 10 seconds
                Thread.sleep(10_000);

                logger.info("Stopping RTL-SDR driver...");
                driver.stop();
            } else {
                logger.info("No physical RTL-SDR device available for live streaming test, driver init verified.");
            }

        } catch (Exception e) {
            logger.error("Error running RTL-SDR test", e);
        }
    }

    @Override
    public void handleEvent(Event e) {
        if (e instanceof DataEvent) {
            DataEvent dataEvent = (DataEvent) e;
            logger.info("Received DataEvent from output: {}", dataEvent.getSource());
        }
    }
}
