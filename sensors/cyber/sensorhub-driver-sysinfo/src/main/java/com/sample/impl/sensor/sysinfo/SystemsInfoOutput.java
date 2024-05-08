/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2021 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package com.sample.impl.sensor.sysinfo;

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.swe.SWEHelper;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSSession;
import oshi.software.os.OperatingSystem;
import oshi.util.FormatUtil;

import java.lang.Boolean;
import java.time.Instant;
import java.util.ArrayList;

/**
 * @author Robin_White
 * @since 2/4/2024
 */
public class SystemsInfoOutput extends AbstractSensorOutput<SystemsInfoSensor> implements Runnable {

    private static final String SENSOR_OUTPUT_NAME = "Systems info";
    private static final String SENSOR_OUTPUT_LABEL = "Systems info";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Metrics returned from computer system info";

    private static final Logger logger = LoggerFactory.getLogger(SystemsInfoOutput.class);



    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    private Boolean stopProcessing = false;
    private final Object processingLock = new Object();

    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private int setCount = 0;
    private final long[] timingHistogram = new long[MAX_NUM_TIMING_SAMPLES];
    private final Object histogramLock = new Object();

    private Thread worker;


    SystemsInfoOutput(SystemsInfoSensor parentSystemsInfoSensor) {

        super(SENSOR_OUTPUT_NAME, parentSystemsInfoSensor);

        logger.debug("Output created");
    }


    void doInit() {

        logger.debug("Initializing Output");

        // Get an instance of SWE Factory suitable to build components
        SWEHelper sweFactory = new SWEHelper();

        // TODO: Create data record description
        dataStruct = sweFactory.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .addField("sampleTime", sweFactory.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("Processors", sweFactory.createQuantity()
                        .definition(SWEHelper.getCfUri("Processor_#"))
                        .label("# of Processors"))
                .addField("freeMemory", sweFactory.createQuantity()
                        .label("JVM Free Memory")
                        .uomCode("GB")
                        .description("Amount of Free Memory allocated to the the JVM")
                )
                .addField("totalMemory", sweFactory.createQuantity()
                        .label("JVM Total Memory")
                        .uomCode("GB")
                        .description("Total Amount of Memory available to the the JVM at time of call")
                )
                .addField("ramUsage", sweFactory.createText()

                        .label("Ram Usage")

                        .description("OSHI HardwareLayer RAM use physical/available")

                )
                .addField("sysOS", sweFactory.createText()
                        .label("Operating System")
                        .description("Runtime info on machines operating system")
                )
                .addField("manufacturer", sweFactory.createText()
                        .label("Manufacturer")
                        .description("OSHI getM info for computer manufacturer")

                )
                .addField("versionInfo", sweFactory.createText()
                        .label("OS Version Info")
                        .description("OSHI OperatingSystems version info")

                )

                .addField("POSi1", sweFactory.createText()
                        .label("Time Booted")
                        .description("OSHI print OS info on last boot time for computer")

                )
                .addField("POSi2", sweFactory.createText()
                        .label("Uptime")
                        .description("OSHI print OS info for Time Running ")

                )
                .addField("POSi3", sweFactory.createText()
                        .label("Elevation Status")
                        .description("OSHI print OS info for Elevated Status of Thread ")

                )

                .addField("bitness", sweFactory.createQuantity()
                        .label("Bitness")
                        .uomCode("bit")
                        .description("OSHI OperatingSystems bit support of current OS")

                )


                .build();


        dataEncoding = sweFactory.newTextEncoding(",", "\n");

        logger.debug("Initializing Output Complete");
    }

    /**
     * Begins processing data for output
     */
    public void doStart() {

        // Instantiate a new worker thread
        worker = new Thread(this, this.name);

        // TODO: Perform other startup

        logger.info("Starting worker thread: {}", worker.getName());

        // Start the worker thread
        worker.start();

    }

    /**
     * Terminates processing data for output
     */
    public void doStop() {

        synchronized (processingLock) {

            stopProcessing = true;
        }

        // TODO: Perform other shutdown procedures
    }

    /**
     * Check to validate data processing is still running
     *
     * @return true if worker thread is active, false otherwise
     */
    public boolean isAlive() {

        return worker.isAlive();
    }

    @Override
    public DataComponent getRecordDescription() {

        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {

        return dataEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {

        long accumulator = 0;

        synchronized (histogramLock) {

            for (int idx = 0; idx < MAX_NUM_TIMING_SAMPLES; ++idx) {

                accumulator += timingHistogram[idx];
            }
        }

        return accumulator / (double) MAX_NUM_TIMING_SAMPLES;
    }

    // Initalized variables for Observation outputs.
    double processor = 0;
    double memory = 0;

    double totalMem = 0;

    String Environ;
    String sysOs;
    oshi.SystemInfo si = new oshi.SystemInfo();
    ArrayList<String> oshi = new ArrayList<String>();
    String finalDiskString;
    HardwareAbstractionLayer hal = si.getHardware();
    OperatingSystem os = si.getOperatingSystem();


    // Functions to call for populating Observation outputs.

    //    public abstract GraphicsConfiguration[] getConfigurations()








    private String getOS() {
        String l = System.getProperty("os.name").toLowerCase();
        return l;
    }

    private double getTotalMem() {
        double l = Runtime.getRuntime().totalMemory();
        return l;
    }

    private double getProcessors() {
        double l = Runtime.getRuntime().availableProcessors();
        return l;
    }

    private double getMemory() {
        double l = Runtime.getRuntime().freeMemory();
        return l;
    }

    private Object printOperatingSystem(final OperatingSystem os) {
        oshi.add(String.valueOf(os));
        oshi.add("Booted: " + Instant.ofEpochSecond(os.getSystemBootTime()));
        oshi.add("Uptime: " + FormatUtil.formatElapsedSecs(os.getSystemUptime()));
        oshi.add("Running with" + (os.isElevated() ? "" : "out") + " elevated permissions.");
        oshi.add("Sessions:");
        for (OSSession s : os.getSessions()) {
            oshi.add(" " + s.toString());
        }
        return null;
    }


    @Override
    public void run() {

        boolean processSets = true;

        long lastSetTimeMillis = System.currentTimeMillis();

        try {

            while (processSets) {

                DataBlock dataBlock;
                if (latestRecord == null) {

                    dataBlock = dataStruct.createDataBlock();

                } else {

                    dataBlock = latestRecord.renew();
                }

                synchronized (histogramLock) {

                    int setIndex = setCount % MAX_NUM_TIMING_SAMPLES;

                    // Get a sampling time for latest set based on previous set sampling time
                    timingHistogram[setIndex] = System.currentTimeMillis() - lastSetTimeMillis;

                    // Set latest sampling time to now
                    lastSetTimeMillis = timingHistogram[setIndex];
                }

                ++setCount;

                double timestamp = System.currentTimeMillis() / 1000d;


//                calling the os system to update oshi arraylist

                printOperatingSystem(os);


                processor = getProcessors();
                memory = getMemory();
                totalMem = getTotalMem();
                sysOs = getOS();
                Environ = String.valueOf(hal.getDisplays());


                String Timebooted = oshi.get(1);
                String Uptime = oshi.get(2);
                String Elevation = oshi.get(3);
                String Manufact = os.getManufacturer();
                int bit = os.getBitness();
                String User = String.valueOf(os.getSessions());
                String Version = String.valueOf(os.getVersionInfo());
                String Disk = String.valueOf(hal.getDiskStores());
                String Memory2 = String.valueOf(hal.getMemory());


                parentSensor.getLogger().trace(String.format("processor=%4.2f, freeMem=%5.2f, totalMem=%3.1f, sysOs, Environ, osysOS",
                        processor, memory, totalMem, sysOs, Environ, Timebooted, Uptime, Elevation, Manufact, bit, Version));

                dataBlock.setDoubleValue(0, timestamp);
                dataBlock.setDoubleValue(1, processor);
                dataBlock.setDoubleValue(2, memory);
                dataBlock.setDoubleValue(3, totalMem);
                dataBlock.setStringValue(4, Memory2);
                dataBlock.setStringValue(5, sysOs);
                dataBlock.setStringValue(6, Manufact);
                dataBlock.setStringValue(7, Version);
                dataBlock.setStringValue(8, Timebooted);
                dataBlock.setStringValue(9, Uptime);
                dataBlock.setStringValue(10, Elevation);
                dataBlock.setIntValue(11, bit);


                latestRecord = dataBlock;

                latestRecordTime = System.currentTimeMillis();

                eventHandler.publish(new DataEvent(latestRecordTime, SystemsInfoOutput.this, dataBlock));

                synchronized (processingLock) {

                    processSets = !stopProcessing;
                }
            }

        } catch (Exception e) {

            logger.error("Error in worker thread: {}", Thread.currentThread().getName(), e);

        } finally {

            // Reset the flag so that when driver is restarted loop thread continues
            // until doStop called on the output again
            stopProcessing = false;

            logger.debug("Terminating worker thread: {}", this.name);
        }
    }
}


