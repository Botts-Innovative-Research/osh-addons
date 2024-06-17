/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2024 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
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
import oshi.hardware.GlobalMemory;




import javax.mail.*;


import java.util.Objects;
import java.util.Properties;
/**
 * Configuration settings for the {@link SystemsInfoSensor} driver exposed via the OpenSensorHub Admin panel.
 * <p>
 * Configuration settings take the form of
 * <code>
 * DisplayInfo(desc="Description of configuration field to show in UI")
 * public Type configOption;
 * </code>
 * <p>
 * Containing an annotation describing the setting and if applicable its range of values
 * as well as a public access variable of the given Type
 *
 * @author Robin_White
 * @since March 1st 2024
 */


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
                .addField("cpuUsage", sweFactory.createQuantity()
                        .definition(SWEHelper.getCfUri("CPU_Percent"))
                        .label("Percent Usage of CPU")
                        .uomCode("")
                        .description("Percent Usage of the the CPU, Non-windows boxes may return above ")
                )
                .addField("ramUsage", sweFactory.createQuantity()
                        .definition(SWEHelper.getCfUri("RAM_Percent"))
                        .label("Ram Usage")
                        .uomCode("")
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
//TODO Still issues with cpuUsage accuracy, but this is the closest currently found for windows.
public double setCpuVal() throws InterruptedException {
    double cpuLoad = 0;
    long[] prevTicks = processor.getSystemCpuLoadTicks();
    Thread.sleep(2000);
    long[] ticks = processor.getSystemCpuLoadTicks();
    if ((String.valueOf(si.getOperatingSystem()).contains("Windows"))) {
        cpuLoad = ((processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100) *(processor.getPhysicalProcessorCount()))/2;
    }
    else {
        cpuLoad = (processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100); }

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


                parentSensor.getLogger().trace(String.valueOf(timestamp), cpuFormat);
                String Memory2 = String.valueOf(hal.getMemory());
                String[] memoryUBStr = Memory2.split(" ");

                float memoryVal1 = Float.parseFloat(memoryUBStr[1]);
                String[] memoryBStr2 = memoryUBStr[2].split("/");
                float memoryVal2 = Float.parseFloat(memoryBStr2[1]);
                GlobalMemory memory = hal.getMemory();
                long totalMemory = memory.getTotal();

                long availableMemory = memory.getAvailable();
                long usedMemory = (totalMemory-availableMemory);
                double percentMemory = ((double)usedMemory / totalMemory)* 100;


                float memoryValUsage = memoryVal2 - memoryVal1;
                float ramAlertVal = (memoryValUsage / memoryVal2) * 100;
                //UB and B stand for UnBroken and Broken respectively.

                String cpuAlertBStr = cpuFormat.split("\n")[1];
                double cpuAlertVal = Double.parseDouble(cpuAlertBStr);

                memory = si.getHardware().getMemory();


                dataBlock.setDoubleValue(0, timestamp);
                dataBlock.setDoubleValue(1, setCpuVal());


                dataBlock.setDoubleValue(2, percentMemory);


//                if (!CPUemailSent) {
//
//                    if (setCpuVal() >= 75.00) {
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
//                    if (percentMemory >= 75.00) {
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
