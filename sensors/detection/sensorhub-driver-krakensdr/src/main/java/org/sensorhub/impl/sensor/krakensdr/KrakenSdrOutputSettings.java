/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2025 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.krakensdr;

import com.google.gson.JsonObject;
import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.vast.swe.SWEBuilders;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;


/**
 * Output specification and provider for {@link KrakenSdrSensor}.
 */
public class KrakenSdrOutputSettings extends AbstractSensorOutput<KrakenSdrSensor> {
    static final String SENSOR_OUTPUT_NAME = "kraken_settings";
    static final String SENSOR_OUTPUT_LABEL = "Current Applicable Settings";
    static final String SENSOR_OUTPUT_DESCRIPTION = "This is the output for the krakenSDR's current settings";

    // myNote:
    // Added Variables because there were in other templates
    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private int setCount = 0;
    private final long[] timingHistogram = new long[MAX_NUM_TIMING_SAMPLES];
    private final Object histogramLock = new Object();
    private long lastSetTimeMillis = System.currentTimeMillis();
    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    /**
     * Creates a new output for the sensor driver.
     *
     * @param parentSensor Sensor driver providing this output.
     */
    public KrakenSdrOutputSettings(KrakenSdrSensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    /**
     * Initializes the data structure for the output, defining the fields, their ordering, and data types.
     */
    public void doInit() {
        // Get an instance of SWE Factory suitable to build components
        SWEHelper sweFactory = new SWEHelper();
        GeoPosHelper geoFac = new GeoPosHelper();

        // Create the data record description
        SWEBuilders.DataRecordBuilder recordBuilder = sweFactory.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .definition(SWEHelper.getPropertyUri("krakenSettingsOutput"))
                .addField("time", sweFactory.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("OSH Collection Time")
                        .description("Timestamp of when OSH took a reading of the current settings")
                        .definition(SWEHelper.getPropertyUri("Time")))
                .addField("receiverConfigSettings", sweFactory.createRecord()
                        .label("RF Receiver Configuration")
                        .description("Data Record for the RF Receiver Configuration")
                        .definition(SWEHelper.getPropertyUri("ReceiverConfigSettings"))
                        .addField("centerFreq", sweFactory.createQuantity()
                                .uomCode("MHz")
                                .label("Center Frequency")
                                .description("The transmission frequency of the event in MegaHertz")
                                .definition(SWEHelper.getPropertyUri("Frequency")))
                        .addField("uniformGain", sweFactory.createQuantity()
                                .uomCode("dB")
                                .label("Receiver Gain")
                                .description("Current reciever gain settings in dB for the KrakenSDR")
                                .definition(SWEHelper.getPropertyUri("UniformGain")))
                )
                .addField("doaConfig", sweFactory.createRecord()
                        .label("DoA Configuration")
                        .description("Data Record for the DoA Configuration Settings")
                        .definition(SWEHelper.getPropertyUri("DoaConfig"))
                        .addField("antennaArrangement", sweFactory.createText()
                                        .label("Antenna Arrangement")
                                        .description("The Arrangement must be UCA or ULA")
                                        .definition(SWEHelper.getPropertyUri("AntennaArrangement")))
                        .addField("antennaSpacingMeter", sweFactory.createQuantity()
                                .uom("m")
                                .label("Antenna Array Radius")
                                .description("Current spacing of the Antenna Array")
                                .definition(SWEHelper.getPropertyUri("AntennaSpacingMeter")))
                        .addField("doaEnabled", sweFactory.createBoolean()
                                .label("DoA Estimation Enabled")
                                .description("Boolean on if DoA Estimation is enabled on kraken software")
                                .definition(SWEHelper.getPropertyUri("DoaEnabled")))
                        .addField("doaMethod", sweFactory.createText()
                                .label("DoA Algorithm")
                                .description("Algorithm used for DoA calculation")
                                .definition(SWEHelper.getPropertyUri("DoaMethod")))
                        .addField("doaDecorrelationMethod", sweFactory.createText()
                                .label("DoA Decorrelation Method")
                                .description("Decorrelation method used for DoA calculation")
                                .definition(SWEHelper.getPropertyUri("DoaDecorrelationMethod")))
                        .addField("ulaDirection", sweFactory.createText()
                                .label("ULA Output Direction")
                                .description("ULA Output Direction")
                                .definition(SWEHelper.getPropertyUri("UlaDirection")))
                        .addField("arrayOffset", sweFactory.createQuantity()
                                .uom("deg")
                                .label("Antenna Array Offset")
                                .description("Current Offset in degrees")
                                .definition(SWEHelper.getPropertyUri("ArrayOffset")))
                        .addField("expectedNumOfSources", sweFactory.createQuantity()
                                .label("Expected Number of Sources")
                                .description("Expected Number of Sources (1-4)")
                                .definition(SWEHelper.getPropertyUri("ExpectedNumberOfSources")))
                )
                .addField("vfoConfigSettings", sweFactory.createRecord()
                        .label("VFO Configuration Settings")
                        .description("Data Record for the Variable Frequency Oscillator Configuration Settings")
                        .definition(SWEHelper.getPropertyUri("VfoConfigSettings"))
                        .addField("spectrumCalculation", sweFactory.createText()
                                .label("Spectrum Calculation")
                                .description("Spectrum Calculation")
                                .definition(SWEHelper.getPropertyUri("SpectrumCalculation")))
                        .addField("vfoMode", sweFactory.createText()
                                .label("VFO Mode")
                                .description("VFO Mode")
                                .definition(SWEHelper.getPropertyUri("VfoMode")))
                        .addField("vfoDefaultSquelchMode", sweFactory.createText()
                                .label("VFO Default Squelch Mode")
                                .description("VFO Default Squelch Mode (Auto, Manual, Auto Channel")
                                .definition(SWEHelper.getPropertyUri("VfoDefaultSquelchMode")))
                        .addField("activeVfos", sweFactory.createQuantity()
                                .label("Active VFO's")
                                .description("Active VFO's(1-16)")
                                .definition(SWEHelper.getPropertyUri("ActiveVfos")))
                        .addField("outputVfos", sweFactory.createQuantity()
                                .label("Output VFO's")
                                .description("Output VFO's(-1 for all, 0-15)")
                                .definition(SWEHelper.getPropertyUri("OutputVfos")))
                        .addField("dspDecimation", sweFactory.createQuantity()
                                .label("DSP Decimation")
                                .description("DSP Decimation (≥1)")
                                .definition(SWEHelper.getPropertyUri("DspDecimation")))
                        .addField("optimizeShortBursts", sweFactory.createBoolean()
                                .label("Optimize Short Bursts")
                                .description("Optimize Short Bursts")
                                .definition(SWEHelper.getPropertyUri("optimizeShortBursts")))
                )
                .addField("stationConfig", sweFactory.createRecord()
                        .label("Station Configuration")
                        .description("Data Record for the Station Configuration")
                        .definition(SWEHelper.getPropertyUri("StationConfig"))
                        .addField("stationId", sweFactory.createText()
                                .label("Station ID")
                                .description("ID provided for the physical KrakenSDR")
                                .definition(SWEHelper.getPropertyUri("StationId")))
                        .addField("locationSource", sweFactory.createText()
                                .label("Location Source")
                                .description("Current Location Source for the Kraken Station")
                                .definition(SWEHelper.getPropertyUri("locationSource")))
                        .addField("location", geoFac.createLocationVectorLatLon())
                        .addField("heading", sweFactory.createQuantity()
                                .label("Heading")
                                .uom("deg")
                                .description("Heading in degrees")
                                .definition(SWEHelper.getPropertyUri("Heading")))
                );


        dataStruct = recordBuilder.build();

        dataEncoding = sweFactory.newTextEncoding(",", "\n");
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

    public void setData() {
        DataBlock dataBlock;
        try {
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

            // RETRIEVE CURRENT JSON SETTINGS AS A JSON OBJECT
            JsonObject currentSettings = util.getSettings();

            if(currentSettings == null || currentSettings.entrySet().isEmpty()){
                return;
            }

            dataBlock.setDoubleValue(0, System.currentTimeMillis() / 1000d);                                  // time
            dataBlock.setBooleanValue(1, currentSettings.get("en_remote_control").getAsBoolean());
            dataBlock.setDoubleValue(2, currentSettings.get("center_freq").getAsDouble());
            dataBlock.setDoubleValue(3, currentSettings.get("uniform_gain").getAsDouble());
            dataBlock.setStringValue(4, currentSettings.get("ant_arrangement").getAsString());
            dataBlock.setDoubleValue(5, currentSettings.get("ant_spacing_meters").getAsDouble());
            dataBlock.setStringValue(6, currentSettings.get("doa_method").getAsString());
            dataBlock.setStringValue(7, (currentSettings.get("station_id") != null) ? currentSettings.get("station_id").getAsString() : "NO Name");
            dataBlock.setStringValue(8, currentSettings.get("location_source").getAsString());
            dataBlock.setStringValue(9, currentSettings.get("latitude").getAsString());
            dataBlock.setStringValue(10, currentSettings.get("longitude").getAsString());

            latestRecord = dataBlock;
            latestRecordTime = System.currentTimeMillis();

            eventHandler.publish(new DataEvent(latestRecordTime, KrakenSdrOutputSettings.this, dataBlock));

        } catch (Exception e) {
            getLogger().error("Error reading from Kraken Device: {}", e.getMessage());
        }
    }

}
