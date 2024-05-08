package com.sample.impl.sensor.sysinfo;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.swe.*;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;


import java.util.Properties;


public class SystemsPhysicalSensor extends AbstractSensorOutput<SystemsInfoSensor> implements Runnable {


    OSProcess cpuVal;
    SystemInfo si = new SystemInfo();
    OperatingSystem os = si.getOperatingSystem();
    CentralProcessor processor = si.getHardware().getProcessor();
    HardwareAbstractionLayer hal = si.getHardware();
    Properties prop = new Properties();


    private static final String SENSOR_OUTPUT_NAME = "Systems Physical Sensors";
    private static final String SENSOR_OUTPUT_LABEL = "Systems Physical Sensors";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Computer system info on physical sensors";
    private static final Logger logger = LoggerFactory.getLogger(SystemsPhysicalSensor.class);
    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private final long[] timingHistogram = new long[MAX_NUM_TIMING_SAMPLES];
    private DataRecord dataStruct;
    private final Object histogramLock = new Object();
    private DataEncoding dataEncoding;
    private Boolean stopProcessing = false;
    private Thread worker;
    private final Object processingLock = new Object();
    private int setCount = 0;
    Properties properties = System.getProperties();


    SystemsPhysicalSensor(SystemsInfoSensor parentSystemsInfoSensor) {

        super(SENSOR_OUTPUT_NAME, parentSystemsInfoSensor);

        logger.debug("Output created");


    }

    public void doInit() {
        logger.debug("Initializing Output");

        // Get an instance of SWE Factory suitable to build components
        SWEHelper sweFactory = new SWEHelper();

        dataStruct = sweFactory.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .addField("SampleTime", sweFactory.createTime().asSamplingTimeIsoUTC().build())
                .addField("cpuTemp", sweFactory.createQuantity()
                        .label("Temperature of CPU")
                        .description("CPU Temperature in degrees Celsius if available, 0 or Double.NaN otherwise. On Windows, may" +
                                "return Thermal Zone temp rather than CPU temp.")
                )

                .addField("cpuVoltage", sweFactory.createQuantity()
                        .label("CPU Voltage")
                        .description("CPU Voltage in Volts if available, 0 otherwise.")
                )
                .addField("fanCount", sweFactory.createCount()
                        .label("Fan Count")
                        .description("number of fans on box")
                        .id("fanCount")
                )
                .addField("FansArray", sweFactory.createText()
                        .label("Fan Speed")
                        .description("\n" +
                                "    Speed in rpm for all fans. May return empty array if no fans detected or" +
                                " 0 fan speed if unable to measure fan speed")
                )
//                .addField("FansArray", sweFactory.createArray()
//                        .label("Fan Speed")
//                        .description("\n" +
//                                "    Speed in rpm for all fans. May return empty array if no fans detected or" +
//                                " 0 fan speed if unable to measure fan speed")
//                        .withVariableSize("fanCount")
//                        .withElement("fans", sweFactory.createRecord()
//                                .label("fans")
//                                .description("fans found on box")
//                                .definition(SWEHelper.getPropertyUri("fans"))
//                                .addField("fanSpeeds", sweFactory.createText()
//                                        .label("Fan Speeds")
//                                        .description("Speed in rpm of all fans.")
//                                        .definition(SWEHelper.getPropertyUri("fan_speed"))
//                                )


                .build();
        dataEncoding = sweFactory.newTextEncoding(",", "\n");
        logger.debug("Initializing Output Complete");

    }


    public void doStart() {

        // Instantiate a new worker thread
        worker = new Thread(this, this.name);

        // TODO: Perform other startup

        logger.info("Starting worker thread: {}", worker.getName());

        // Start the worker thread
        worker.start();


    }

    public void doStop() {

        synchronized (processingLock) {

            stopProcessing = true;
        }


    }

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


    public double setCPUTemp() {

        return hal.getSensors().getCpuTemperature();

    }

    public int[] setFanSpeed() {

        return hal.getSensors().getFanSpeeds();

    }

    public double setCPUVolt() {

        return hal.getSensors().getCpuVoltage();

    }

    public int setFanCount() {

        return setFanSpeed().length;

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
                Double cpuTemp = setCPUTemp();

                double timestamp = System.currentTimeMillis() / 1000d;


                parentSensor.getLogger().trace(String.format(String.valueOf(timestamp), cpuTemp));
                Double cpuVolt = setCPUVolt();
                int fanCount = setFanCount();
                int[] fanSpeed = setFanSpeed();
                int fan1 = fanSpeed[0];
                int fan2 = fanSpeed[1];
                int fan3 = fanSpeed[2];


                int fansCount = setFanSpeed().length;
                dataBlock.setDoubleValue(0, timestamp);
                dataBlock.setDoubleValue(1, cpuTemp);
                dataBlock.setDoubleValue(2, cpuVolt);
                dataBlock.setIntValue(3, fanCount);
                dataBlock.setIntValue(4, fan1);
//                var FansArray = ((DataArrayImpl) dataStruct.getComponent("FansArray"));
//                FansArray.updateSize();
//                dataBlock.updateAtomCount();

                int index = 3;
//
//                for (int i = 0; i < fansCount; i++) {
//                    int fanVal = setFanSpeed()[i];
//                    dataBlock.setDoubleValue(index++, fanVal);
//                }


                latestRecord = dataBlock;

                latestRecordTime = System.currentTimeMillis();

                eventHandler.publish(new DataEvent(latestRecordTime, SystemsPhysicalSensor.this, dataBlock));
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
