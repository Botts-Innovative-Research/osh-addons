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

import javax.mail.*;


import java.util.Properties;


public class Alerts extends AbstractSensorOutput<SystemsInfoSensor> implements Runnable {


    OSProcess cpuVal;
    SystemInfo si = new SystemInfo();
    OperatingSystem os = si.getOperatingSystem();
    CentralProcessor processor = si.getHardware().getProcessor();
    HardwareAbstractionLayer hal = si.getHardware();
    Properties prop = new Properties();


    private static final String SENSOR_OUTPUT_NAME = "Systems info alerts";
    private static final String SENSOR_OUTPUT_LABEL = "Systems info alerts";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Alerts derived from computer system info metrics";
    private static final Logger logger = LoggerFactory.getLogger(SystemsInfoOutput.class);
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
    String alertTarget = SystemsInfoConfig.alertTarget;
    String alertSender = SystemsInfoConfig.alertSender;
    //TODO Set these values to passkey for google mail as described in readme document
    Session session = Session.getInstance(properties, new javax.mail.Authenticator() {
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(alertSender, "google mail pass key here");
        }
    });
    Boolean CPUemailSent = false;
    Boolean userEmailSent = false;
    Boolean RAMemailSent = false;


    Alerts(SystemsInfoSensor parentSystemsInfoSensor) {

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
                .addField("cpuUsage", sweFactory.createText()
                        .label("Percent Usage of CPU")
                        .description("Percent Usage of the the CPU, Non-windows boxes may return above ")
                )
                .addField("ramUsage", sweFactory.createText()

                        .label("Ram Usage")

                        .description("OSHI HardwareLayer RAM use physical/available")

                )


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

    //TODO This section is commented out so the driver does not attempt to send an alert while the email target and sender is not set up. check the read me document for details.
    // lines 299-306 and 322-328 are commented out for the same reason.
//    public void sendCPUEmail() {
//        try {
//            properties.setProperty("mail.smtp.host", "smtp.gmail.com");
//            properties.setProperty("mail.smtp.port", "587");
//            properties.setProperty("mail.smtp.auth", "true");
//            properties.setProperty("mail.smtp.starttls.enable", "true");
//
//            MimeMessage message = new MimeMessage(session);
//            message.setFrom(alertTarget);
//            message.addRecipient(Message.RecipientType.TO, new InternetAddress(alertTarget));
//
//            message.setSubject("The CPU Usage value for your box is above the threshold limit.");
//            message.setText("The node associated with this Alert Target Email Address has reached a value about the set threshold.");
//            Transport.send(message);
//        } catch (MessagingException mex) {
//            mex.printStackTrace();
//        }
//
//    }
//
//    public void sendRAMEmail() {
//        try {
//            properties.setProperty("mail.smtp.host", "smtp.gmail.com");
//            properties.setProperty("mail.smtp.port", "587");
//            properties.setProperty("mail.smtp.auth", "true");
//            properties.setProperty("mail.smtp.starttls.enable", "true");
//
//            MimeMessage message = new MimeMessage(session);
//            message.setFrom(alertTarget);
//            message.addRecipient(Message.RecipientType.TO, new InternetAddress(alertTarget));
//
//            message.setSubject("The RAM Usage value for your box is above the threshold limit.");
//            message.setText("The node associated with this Alert Target Email Address has reached a value about the set threshold.");
//            Transport.send(message);
//        } catch (MessagingException mex) {
//            mex.printStackTrace();
//        }
//
//    }
//
//    public void sendUserEmail() {
//        try {
//            properties.setProperty("mail.smtp.host", "smtp.gmail.com");
//            properties.setProperty("mail.smtp.port", "587");
//            properties.setProperty("mail.smtp.auth", "true");
//            properties.setProperty("mail.smtp.starttls.enable", "true");
//
//            MimeMessage message = new MimeMessage(session);
//            message.setFrom(alertTarget);
//            message.addRecipient(Message.RecipientType.TO, new InternetAddress(alertTarget));
//
//            message.setSubject("The User login time for your box is above the threshold limit.");
//            message.setText("The node associated with this Alert Target Email Address has reached a value about the set threshold.");
//            Transport.send(message);
//        } catch (MessagingException mex) {
//            mex.printStackTrace();
//        }
//
//    }


    public double setCpuVal() {
        double cpuLoad = processor.getSystemCpuLoad(1500) * 100;

        return cpuLoad;

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
                String cpuFormat = "System CPU Load: %.2f%%\n" + setCpuVal();

                double timestamp = System.currentTimeMillis() / 1000d;


                parentSensor.getLogger().trace(String.format(String.valueOf(timestamp), cpuFormat));
                String Memory2 = String.valueOf(hal.getMemory());
                String[] memoryUBStr = Memory2.split(" ");

                float memoryVal1 = Float.parseFloat(memoryUBStr[1]);
                String[] memoryBStr2 = memoryUBStr[2].split("/");
                float memoryVal2 = Float.parseFloat(memoryBStr2[1]);


                float memoryValUsage = memoryVal2 - memoryVal1;
                float RAMAlertVal = (memoryValUsage / memoryVal2) * 100;


                dataBlock.setDoubleValue(0, timestamp);
                dataBlock.setStringValue(1, cpuFormat);
                dataBlock.setStringValue(2, Memory2);
//UB and B stand for UnBroken and Broken respectively.
                String cpuAlertUBStr = dataBlock.getStringValue(1);
                String cpuAlertBStr = cpuAlertUBStr.split("\n")[1];
                double cpuAlertVal = Double.parseDouble(cpuAlertBStr);


//                if (!CPUemailSent) {
//
//                    if (cpuAlertVal >= 75.00) {
////                        sendCPUEmail();
//                        CPUemailSent = true;
//
//                    }
//                }

//              This is currently commented out b/c it's threshold value is not yet implemented

//                if (userEmailSent =! true){
//                    if (userAlertVal >= 75.00) {
//                        sendCPUEmail();
//                        userEmailSent = true;
//
//                    }
//                }





//                if (!RAMemailSent){
//                    if (RAMAlertVal >= 75.00) {
//                        sendRAMEmail();
//                        RAMemailSent = true;
//
//                    }
//                }

                latestRecord = dataBlock;

                latestRecordTime = System.currentTimeMillis();

                eventHandler.publish(new DataEvent(latestRecordTime, Alerts.this, dataBlock));
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
