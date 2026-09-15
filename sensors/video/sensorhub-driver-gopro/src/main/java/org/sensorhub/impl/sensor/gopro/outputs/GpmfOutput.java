/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.gopro.outputs;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataType;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfPayload;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfPayloadListener;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.DataArrayImpl;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Output for the decoded telemetry of a GoPro GPMF stream.
 * <p>
 * Each GPMF packet describes a set of sensor streams for the interval it covers -- accelerometer,
 * gyroscope, GPS, camera state, and whatever else the model records -- and this output publishes one
 * record per stream per packet, with the camera's own scale divisors already applied.
 * <p>
 * Which streams appear depends on the camera model, its firmware, and the capture settings, so the
 * record is self-describing rather than shaped around a fixed set of sensors: every record names its
 * stream and units as the camera reported them, and carries that stream's samples.
 *
 * @see GpmfPayload
 */
public class GpmfOutput<T extends ISensorModule<?>> extends AbstractSensorOutput<T> implements GpmfPayloadListener {
    private static final String VALUE_COUNT_ID = "valueCountID";
    private static final Logger logger = LoggerFactory.getLogger(GpmfOutput.class.getSimpleName());
    private static final int MAX_NUM_TIMING_SAMPLES = 10;

    /**
     * Flat index of the variable-size value array within a record's data block. The nine preceding atoms
     * are the scalar fields, the last of which is the array's own size component.
     */
    private static final int VALUES_INDEX = 10;

    private final String outputLabel;
    private final String outputDescription;
    private final ArrayList<Double> intervalHistogram = new ArrayList<>(MAX_NUM_TIMING_SAMPLES);
    private final Object histogramLock = new Object();

    private DataComponent dataStruct;
    private DataEncoding dataEncoding;
    private DataArrayImpl valueArray;

    /**
     * Creates a new GPMF telemetry output.
     *
     * @param parentSensor Sensor driver providing this output.
     */
    public GpmfOutput(T parentSensor) {
        this(parentSensor, "gpmf", "GPMF Telemetry", "GoPro GPMF telemetry");
    }

    /**
     * Creates a new GPMF telemetry output.
     *
     * @param parentSensor      Sensor driver providing this output.
     * @param name              The name of the output.
     * @param outputLabel       The label of the output.
     * @param outputDescription The description of the output.
     */
    public GpmfOutput(T parentSensor, String name, String outputLabel, String outputDescription) {
        super(name, parentSensor);

        this.outputLabel = outputLabel;
        this.outputDescription = outputDescription;

        logger.debug("GPMF output created.");
    }

    /**
     * Initializes the data structure for the output, defining the fields, their ordering, and data types.
     */
    public void doInit() {
        logger.debug("Initializing GPMF output.");

        SWEHelper sweHelper = new SWEHelper();
        dataStruct = sweHelper.createRecord()
                .name(getName())
                .label(outputLabel)
                .description(outputDescription)
                .definition(SWEHelper.getPropertyUri("TelemetryStream"))
                .addField("sampleTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("streamTime", sweHelper.createQuantity()
                        .label("Stream Time")
                        .description("Offset of these samples from the start of the recording, as reported by the camera. NaN if the camera did not report one.")
                        .uomCode("s")
                        .dataType(DataType.DOUBLE))
                .addField("deviceName", sweHelper.createText()
                        .label("Device Name")
                        .description("Name of the device that recorded this stream, for example Hero11 Black"))
                .addField("streamKey", sweHelper.createText()
                        .label("Stream Key")
                        .description("Four-character GPMF key identifying the stream, for example ACCL, GYRO, or GPS5"))
                .addField("streamName", sweHelper.createText()
                        .label("Stream Name")
                        .description("Human-readable stream name as the camera reported it, for example Accelerometer (z,x,y)"))
                .addField("units", sweHelper.createText()
                        .label("Units")
                        .description("Units of the values, as the camera reported them, for example m/s2"))
                .addField("elementCount", sweHelper.createCount()
                        .label("Element Count")
                        .description("Number of values in one sample, for example 3 for a three-axis sensor")
                        .dataType(DataType.INT))
                .addField("sampleCount", sweHelper.createCount()
                        .label("Sample Count")
                        .description("Number of samples in this record")
                        .dataType(DataType.INT))
                .addField("textValue", sweHelper.createText()
                        .label("Text Value")
                        .description("Content of a text-valued stream, such as the GPS UTC timestamp. Empty for numeric streams."))
                .addField("valueCount", sweHelper.createCount()
                        .id(VALUE_COUNT_ID)
                        .label("Value Count")
                        .description("Total number of values in this record, equal to the sample count times the element count")
                        .dataType(DataType.INT))
                .addField("values", sweHelper.createArray()
                        .withVariableSize(VALUE_COUNT_ID)
                        .label("Values")
                        .description("Samples of this stream in order, each holding element count values, with the camera's scale divisors applied")
                        .withElement("value", sweHelper.createQuantity()
                                .label("Value")
                                .definition(SWEConstants.DEF_DN)
                                .dataType(DataType.DOUBLE)))
                .build();

        valueArray = (DataArrayImpl) dataStruct.getComponent("values");

        // Text encoding keeps the self-describing string fields readable and avoids having to frame
        // variable-length text in a binary stream. Payload rates here are a few records per second.
        this.dataEncoding = sweHelper.newTextEncoding();
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
        double sum = 0;

        synchronized (histogramLock) {
            for (double sample : intervalHistogram) {
                sum += sample;
            }
        }

        return sum / intervalHistogram.size();
    }

    @Override
    public void onGpmfPayload(GpmfPayload payload, long timestampMillis) {
        List<DataBlock> dataBlocks = new ArrayList<>(payload.getStreams().size());
        for (GpmfStream stream : payload.getStreams()) {
            dataBlocks.add(toDataBlock(stream, timestampMillis));
        }

        if (dataBlocks.isEmpty()) {
            return;
        }

        updateIntervalHistogram();

        latestRecord = dataBlocks.get(dataBlocks.size() - 1);
        latestRecordTime = timestampMillis;

        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlocks.toArray(new DataBlock[0])));
    }

    /**
     * Builds the record for one decoded telemetry stream.
     *
     * @param stream    The stream to publish.
     * @param timestamp Collection time of the packet the stream came from, in milliseconds.
     * @return The populated data block.
     */
    private DataBlock toDataBlock(GpmfStream stream, long timestamp) {
        double[] values = stream.getValues();

        // The value array is variable-size, so it has to be resized before the block is allocated
        valueArray.updateSize(values.length);
        DataBlock dataBlock = dataStruct.createDataBlock();

        int index = 0;
        dataBlock.setDoubleValue(index++, timestamp / 1000d);
        dataBlock.setDoubleValue(index++, stream.getTimestamp());
        dataBlock.setStringValue(index++, orEmpty(stream.getDeviceName()));
        dataBlock.setStringValue(index++, orEmpty(stream.getKey()));
        dataBlock.setStringValue(index++, orEmpty(stream.getName()));
        dataBlock.setStringValue(index++, orEmpty(stream.getUnits()));
        dataBlock.setIntValue(index++, stream.getElementCount());
        dataBlock.setIntValue(index++, stream.getSampleCount());
        dataBlock.setStringValue(index++, orEmpty(stream.getText()));
        dataBlock.setIntValue(index, values.length);

        for (int i = 0; i < values.length; i++) {
            dataBlock.setDoubleValue(VALUES_INDEX + i, values[i]);
        }

        return dataBlock;
    }

    /**
     * Substitutes an empty string for a value the payload did not carry, since the record's text fields
     * are always written.
     */
    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    /**
     * Updates the interval histogram with the time between the latest record and the current time
     * for calculating the average sampling period.
     */
    private void updateIntervalHistogram() {
        synchronized (histogramLock) {
            if (latestRecord != null && latestRecordTime != Long.MIN_VALUE) {
                long interval = System.currentTimeMillis() - latestRecordTime;
                intervalHistogram.add(interval / 1000d);

                if (intervalHistogram.size() > MAX_NUM_TIMING_SAMPLES) {
                    intervalHistogram.remove(0);
                }
            }
        }
    }
}