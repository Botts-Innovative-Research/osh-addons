package com.sample.impl.sensor.sysinfo;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.DataArrayImpl;
import org.vast.swe.SWEHelper;
import oshi.SystemInfo;
import oshi.software.os.OperatingSystem;

import java.util.Timer;
import java.util.TimerTask;


public class UserOutput extends AbstractSensorOutput<SystemsInfoSensor> {
    private static final String USER_SENSOR_OUTPUT_NAME = "User Systems info";
    private static final String SENSOR_OUTPUT_LABEL = "User Systems info";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "User Account Metrics returned from computer system info";
    private static final Logger logger = LoggerFactory.getLogger(SystemsInfoOutput.class);
    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private final long[] timingHistogram = new long[MAX_NUM_TIMING_SAMPLES];
    private final Object histogramLock = new Object();
    Timer timer = new Timer();
    TimerTask timerTask;
    boolean userAboveTimeThreshold = false;


    SystemInfo si = new SystemInfo();
    OperatingSystem os = si.getOperatingSystem();


    private final Object processingLock = new Object();

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;


    UserOutput(SystemsInfoSensor parentSystemsInfoSensor) {

        super(USER_SENSOR_OUTPUT_NAME, parentSystemsInfoSensor);

        logger.debug("Output created");
    }

    public void doInit() {

        getLogger().debug("Initializing Output");

        defineRecordStructure();


        initSamplingTime();

        getLogger().debug("Initializing Output Complete");

    }

    public void doStart() {

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

    //As it stands, I'm pretty sure this is causing the driver to be unable to restart from the admin panel if the sensor is stopped for any reason
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

    private void determineTimeThreshold() {
        int userCount = os.getSessions().size();
        int j = 0;
        for (int i = 0; i < userCount; i++) {

            String[] currentUser = String.valueOf(os.getSessions()).split(",");


            String userName = String.valueOf(currentUser[0 + j]);
            String loginStatus = String.valueOf(currentUser[2 + j]);

        }


    }


    protected void defineRecordStructure() {
        SWEHelper sweFactory = new SWEHelper();


        dataStruct = sweFactory.createRecord()
                .name(USER_SENSOR_OUTPUT_NAME)
                .updatable(true)

                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .addField("SampleTime", sweFactory.createTime().asSamplingTimeIsoUTC().build())
                .addField("userCount", sweFactory.createCount()
                        .label("User Count")
                        .description("Number of Users on device")
                        .definition(SWEHelper.getPropertyUri("user_count"))
                        .id("userCount")
                )
                .addField("userArray", sweFactory.createArray()
                        .label("User Account Entries")
                        .description("OSHI call for Sessions- all User info on box")
                        .withVariableSize("userCount")
                        .withElement("users", sweFactory.createRecord()
                                .label("users")
                                .description("User accounts found on box")
                                .definition(SWEHelper.getPropertyUri("users"))

                                .addField("userName", sweFactory.createText()
                                        .label("User Name")
                                        .description("Name of the user account")
                                        .definition(SWEHelper.getPropertyUri("user_name"))
                                )
                                .addField("loginStatus", sweFactory.createText()
                                        .label("Login Status")
                                        .description("Null value if the user is not logged in, and the time of login if the user is.")
                                        .definition(SWEHelper.getPropertyUri("login_status"))
                                )
                                .addField("userRole", sweFactory.createText()
                                        .label("User Role")
                                        .description("What account the user is under. ex.(NT AUTHORITY, BUILTIN/Administrators, ect.")
                                        .definition(SWEHelper.getPropertyUri("user_role"))

                                )

                        ))


                .build();
        dataEncoding = sweFactory.newTextEncoding(",", "\n");
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

        int userCount = os.getSessions().size();

        dataStruct.setData(dataBlock);

        dataBlock.setDoubleValue(index++, timestamp);
        dataBlock.setIntValue(index++, userCount);

        var userArray = ((DataArrayImpl) dataStruct.getComponent("userArray"));
        userArray.updateSize();
        dataBlock.updateAtomCount();
        int j = 0;

        for (int i = 0; i < userCount; i++) {

            String[] currentUser = String.valueOf(os.getSessions()).split(",");


            String userName = String.valueOf(currentUser[0 + j]);
            String loginStatus = String.valueOf(currentUser[2 + j]);
            String userRole = String.valueOf(currentUser[3 + j]);

            dataBlock.setStringValue(index++, userName);
            dataBlock.setStringValue(index++, loginStatus);
            dataBlock.setStringValue(index++, userRole);
            j = j + 4;


        }

        latestRecord = dataBlock;

        latestRecordTime = System.currentTimeMillis();

        eventHandler.publish(new DataEvent(latestRecordTime, UserOutput.this, dataBlock));


    }
}





