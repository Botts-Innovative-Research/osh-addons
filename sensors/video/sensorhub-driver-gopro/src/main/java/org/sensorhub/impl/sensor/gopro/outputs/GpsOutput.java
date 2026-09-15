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
import org.sensorhub.impl.sensor.gopro.config.AltitudeDatum;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfGps;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfGpsFix;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfPayload;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfPayloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Output for the GPS fixes of a GoPro GPMF stream, published as a location vector.
 * <p>
 * One record is published per GPS fix, so a payload carrying a second of {@code GPS5} samples yields
 * around eighteen records. The location is a standard latitude/longitude/altitude vector, which is what
 * lets OpenSensorHub treat these records as a track rather than as loose numbers.
 * <p>
 * The altitude datum comes from the driver configuration rather than the payload, because GoPro cameras
 * differ on it and do not say which they used. See {@link AltitudeDatum}.
 */
public class GpsOutput<T extends ISensorModule<?>> extends AbstractSensorOutput<T> implements GpmfPayloadListener {
    private static final Logger logger = LoggerFactory.getLogger(GpsOutput.class.getSimpleName());
    private static final int MAX_NUM_TIMING_SAMPLES = 10;

    private final String outputLabel;
    private final String outputDescription;
    private final AltitudeDatum altitudeDatum;
    private final boolean requireFix;
    private final ArrayList<Double> intervalHistogram = new ArrayList<>(MAX_NUM_TIMING_SAMPLES);
    private final Object histogramLock = new Object();

    private DataComponent dataStruct;
    private DataEncoding dataEncoding;

    /**
     * Set once the camera's declared altitude system has been checked against the configured datum, so
     * that a mismatch is reported once rather than for every payload.
     */
    private boolean altitudeSystemChecked;

    /**
     * Creates a new GPS output.
     *
     * @param parentSensor  Sensor driver providing this output.
     * @param altitudeDatum The datum the camera's altitude readings are on, used when the camera does
     *                      not declare one itself.
     * @param requireFix    Whether to drop fixes the receiver had no lock for.
     */
    public GpsOutput(T parentSensor, AltitudeDatum altitudeDatum, boolean requireFix) {
        this(parentSensor, altitudeDatum, requireFix, "gps", "GPS", "GoPro GPS fixes from GPMF telemetry");
    }

    /**
     * Creates a new GPS output.
     *
     * @param parentSensor      Sensor driver providing this output.
     * @param altitudeDatum     The datum the camera's altitude readings are on, used when the camera does
     *                          not declare one itself.
     * @param requireFix        Whether to drop fixes the receiver had no lock for.
     * @param name              The name of the output.
     * @param outputLabel       The label of the output.
     * @param outputDescription The description of the output.
     */
    public GpsOutput(T parentSensor, AltitudeDatum altitudeDatum, boolean requireFix,
                     String name, String outputLabel, String outputDescription) {
        super(name, parentSensor);

        this.altitudeDatum = altitudeDatum;
        this.requireFix = requireFix;
        this.outputLabel = outputLabel;
        this.outputDescription = outputDescription;

        logger.debug("GPS output created.");
    }

    /**
     * Initializes the data structure for the output, defining the fields, their ordering, and data types.
     */
    public void doInit() {
        logger.debug("Initializing GPS output with {} altitude.", altitudeDatum);

        GeoPosHelper sweHelper = new GeoPosHelper();

        // The camera's altitude datum decides which coordinate reference system the vector declares
        var locationVector = altitudeDatum == AltitudeDatum.MEAN_SEA_LEVEL
                ? sweHelper.createLocationVectorLLA_MSL()
                : sweHelper.createLocationVectorLLA();

        dataStruct = sweHelper.createRecord()
                .name(getName())
                .label(outputLabel)
                .description(outputDescription)
                .definition(SWEHelper.getPropertyUri("GeoPosition"))
                .addField("sampleTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("gpsTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("GPS Time")
                        .description("Time of the fix as reported by the GPS receiver. NaN if the camera did not report one."))
                .addField("location", locationVector
                        .label("Location")
                        .description("Position of the camera, with altitude on the " + altitudeDatum.getLabel() + " datum"))
                .addField("speed2D", sweHelper.createQuantity()
                        .label("Ground Speed")
                        .description("Speed over the ground. NaN if the camera did not report one.")
                        .definition(SWEHelper.getPropertyUri("GroundSpeed"))
                        .uomCode("m/s")
                        .dataType(DataType.DOUBLE))
                .addField("speed3D", sweHelper.createQuantity()
                        .label("3D Speed")
                        .description("Speed including the vertical component. NaN if the camera did not report one.")
                        .definition(SWEHelper.getPropertyUri("Speed"))
                        .uomCode("m/s")
                        .dataType(DataType.DOUBLE))
                .addField("fixType", sweHelper.createCount()
                        .label("Fix Type")
                        .description("GPS fix quality: 0 for no fix, 2 for a 2D fix, 3 for a 3D fix, -1 if the camera did not report one")
                        .dataType(DataType.INT))
                .addField("dop", sweHelper.createQuantity()
                        .label("Dilution of Precision")
                        .description("Dilution of precision of the fix, lower being better. NaN if the camera did not report one.")
                        .uomCode("1")
                        .dataType(DataType.DOUBLE))
                .build();

        this.dataEncoding = sweHelper.newTextEncoding();
    }

    @Override
    public void onGpmfPayload(GpmfPayload payload, long timestampMillis) {
        List<GpmfGpsFix> fixes = GpmfGps.extract(payload);

        if (fixes.isEmpty()) {
            return;
        }

        checkAltitudeSystem(fixes.get(0));

        List<DataBlock> dataBlocks = new ArrayList<>(fixes.size());
        int droppedCount = 0;

        for (GpmfGpsFix fix : fixes) {
            // A GoPro keeps reporting coordinates with no lock, and those coordinates are meaningless:
            // they land far from the camera and would scatter the published track with bad positions.
            if (requireFix && !fix.hasLock()) {
                droppedCount++;
                continue;
            }

            dataBlocks.add(toDataBlock(fix, timestampMillis));
        }

        if (droppedCount > 0) {
            logger.debug("Dropped {} of {} GPS fix(es) with no lock.", droppedCount, fixes.size());
        }

        if (dataBlocks.isEmpty()) {
            return;
        }

        updateIntervalHistogram();

        latestRecord = dataBlocks.get(dataBlocks.size() - 1);
        latestRecordTime = timestampMillis;

        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlocks.toArray(new DataBlock[0])));
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

    /**
     * Compares the altitude system the camera declared against the datum this output was built for.
     * <p>
     * The record structure declares its coordinate reference system once, at initialization, before any
     * telemetry has arrived, so a camera that turns out to disagree cannot be accommodated after the
     * fact. Reporting the mismatch tells the operator exactly what to change, rather than leaving the
     * altitudes quietly mislabelled by the local geoid offset.
     */
    private void checkAltitudeSystem(GpmfGpsFix fix) {
        if (altitudeSystemChecked) {
            return;
        }

        AltitudeDatum declared = AltitudeDatum.fromGpsAltitudeSystem(fix.altitudeSystem());
        if (declared == null) {
            // Older cameras declare nothing, which is what the configured datum is for
            altitudeSystemChecked = fix.altitudeSystem() != null;
            return;
        }

        altitudeSystemChecked = true;

        if (declared != altitudeDatum) {
            logger.warn("Camera reports altitude as '{}' ({}) but this output is configured for {}. " +
                            "Altitudes will be off by the local geoid offset until the GPS Altitude Datum " +
                            "setting is changed to {}.",
                    fix.altitudeSystem(), declared.getLabel(), altitudeDatum.getLabel(), declared);
        } else {
            logger.info("Camera reports altitude as '{}', matching the configured {} datum.",
                    fix.altitudeSystem(), altitudeDatum.getLabel());
        }
    }

    /**
     * Builds the record for one GPS fix.
     *
     * @param fix       The fix to publish.
     * @param timestamp Collection time of the packet the fix came from, in milliseconds.
     * @return The populated data block.
     */
    private DataBlock toDataBlock(GpmfGpsFix fix, long timestamp) {
        // A fresh block per fix, since a payload yields many and they are published together
        DataBlock dataBlock = dataStruct.createDataBlock();

        int index = 0;
        dataBlock.setDoubleValue(index++, timestamp / 1000d);
        dataBlock.setDoubleValue(index++, fix.utcTime());
        dataBlock.setDoubleValue(index++, fix.latitude());
        dataBlock.setDoubleValue(index++, fix.longitude());
        dataBlock.setDoubleValue(index++, fix.altitude());
        dataBlock.setDoubleValue(index++, fix.speed2D());
        dataBlock.setDoubleValue(index++, fix.speed3D());
        dataBlock.setIntValue(index++, fix.fixType());
        dataBlock.setDoubleValue(index, fix.dop());

        return dataBlock;
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