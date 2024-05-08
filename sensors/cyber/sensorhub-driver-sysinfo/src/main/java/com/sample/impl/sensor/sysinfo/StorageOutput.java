package com.sample.impl.sensor.sysinfo;

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.DataArrayImpl;
import org.vast.swe.SWEHelper;
import oshi.hardware.HWDiskStore;

import java.util.List;
import java.util.TimerTask;
import java.util.Timer;


public class StorageOutput extends AbstractSensorOutput<SystemsInfoSensor> {
    private static final String DISK_SENSOR_OUTPUT_NAME = "Storage Systems info";
    private static final String SENSOR_OUTPUT_LABEL = "Storage Systems info";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Disk Storage Metrics returned from computer system info";
    private static final Logger logger = LoggerFactory.getLogger(SystemsInfoOutput.class);
    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private final long[] timingHistogram = new long[MAX_NUM_TIMING_SAMPLES];
    private final Object histogramLock = new Object();
    Timer timer = new Timer();
    TimerTask timerTask;


    oshi.SystemInfo si = new oshi.SystemInfo();


    private final Object processingLock = new Object();

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;


    StorageOutput(SystemsInfoSensor parentSystemsInfoSensor) {

        super(DISK_SENSOR_OUTPUT_NAME, parentSystemsInfoSensor, LoggerFactory.getLogger(SystemsInfoOutput.class));

        logger.debug("Output created");
    }


    public void doStart() {

        // Instantiate a new worker thread
//        Runnable task =  this::run;

        completeTask();
//        timer.scheduleAtFixedRate(timerTask, 0, 10000);
        System.out.println("Timertask started");

    }

    public boolean isAlive() {

        return true;
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


    public void doStop() {

        if (timer != null) {
            timer.cancel();
            timer.purge();
//            timerTask.cancel();


            System.out.println("Timer task stopped");
        }


    }

    private void initSamplingTime() {
    }


    protected void defineRecordStructure() {
        SWEHelper sweFactory = new SWEHelper();


        dataStruct = sweFactory.createRecord()
                .name(DISK_SENSOR_OUTPUT_NAME)
                .updatable(true)

                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .addField("SampleTime", sweFactory.createTime().asSamplingTimeIsoUTC().build())
                .addField("diskStorageCount", sweFactory.createCount()
                        .label("Disk Storage Count")
                        .description("Number of returned disk drives on device")
                        .definition(SWEHelper.getPropertyUri("disk_storage_count"))
                        .id("diskStorageCount")
                )
                .addField("diskStorageArray", sweFactory.createArray()
                        .label("Disk Storage Entries")
                        .description("OSHI call for DiskStores- all storage devices on box that executes build")
                        .withVariableSize("diskStorageCount")
                        .withElement("diskStorage", sweFactory.createRecord()
                                .label("Disk Storage")
                                .description("Disk Storage Device found on box")
                                .definition(SWEHelper.getPropertyUri("disk_storage"))

                                .addField("storageDeviceNumber", sweFactory.createText()
                                        .label("Device Number")
                                        .description("Number assigned to disk device.")
                                        .definition(SWEHelper.getPropertyUri("storage_dev_number"))
                                )
                                .addField("modelName", sweFactory.createText()
                                        .label("Device Model")
                                        .description("Model of Disk Device.")
                                        .definition(SWEHelper.getPropertyUri("device_model"))
                                )
                                .addField("diskSize", sweFactory.createQuantity()
                                        .label("Device Size")
                                        .uomCode("B")
                                        .description("Size of Disk Device.")
                                        .definition(SWEHelper.getPropertyUri("device_size"))

                                )
                                .addField("diskRead", sweFactory.createQuantity()
                                        .label("Device Read Size")
                                        .description("Size of Disk Read GIB")
                                        .uomCode("GB")
                                        .definition(SWEHelper.getPropertyUri("device_read_size"))
                                )
                                .addField("diskWrite", sweFactory.createQuantity()
                                        .label("Device Write Size")
                                        .uomCode("GB")
                                        .description("Size of Disk Write GIB")
                                        .definition(SWEHelper.getPropertyUri("device_write_size"))
                                )
                        ))


                .build();
        dataEncoding = sweFactory.newTextEncoding(",", "\n");
    }

    public void doInit() {

        getLogger().debug("Initializing Output");

        defineRecordStructure();


        initSamplingTime();

        getLogger().debug("Initializing Output Complete");

    }

    private void completeTask() {
        timerTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (processingLock) {
                    try {
                        defineRecordStructure();
                        executeDataStruct();
                    } catch (Exception e) {
                        logger.error("Error executing", e);
                    }
                }
            }
        };
        timer.scheduleAtFixedRate(timerTask, 0, 10000);
    }

    public void executeDataStruct() {
        DataBlock dataBlock;
        dataBlock = dataStruct.createDataBlock();
        double timestamp = System.currentTimeMillis() / 1000d;

        defineRecordStructure();

        int index = 0;


        List<HWDiskStore> diskList = si.getHardware().getDiskStores();


        defineRecordStructure();


        int storageCount = si.getHardware().getDiskStores().size();

        dataStruct.setData(dataBlock);

        dataBlock.setDoubleValue(index++, timestamp);
        dataBlock.setIntValue(index++, storageCount);

        var diskStorageArray = ((DataArrayImpl) dataStruct.getComponent("diskStorageArray"));
        diskStorageArray.updateSize();
        dataBlock.updateAtomCount();


        for (HWDiskStore disk : diskList) {


            String modelName = disk.getModel();
            String diskName = disk.getName();
            long diskSize = disk.getSize();
            long readSize = disk.getReads();
            long writeSize = disk.getWrites();

            dataBlock.setStringValue(index++, modelName);
            dataBlock.setStringValue(index++, diskName);
            dataBlock.setLongValue(index++, diskSize);
            dataBlock.setLongValue(index++, readSize);
            dataBlock.setLongValue(index++, writeSize);
        }

        latestRecord = dataBlock;

        latestRecordTime = System.currentTimeMillis();

        eventHandler.publish(new DataEvent(latestRecordTime, StorageOutput.this, dataBlock));
    }


}


