/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.outputs;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder;
import org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder.AircraftState;
import org.vast.swe.SWEBuilders;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

/**
 * OpenSensorHub sensor output providing structured SWE DataRecords for 1090 MHz ADS-B aircraft tracks.
 */
public class RtlSdrOutputAdsb extends AbstractSensorOutput<RtlSdrDriver> {
    public static final String OUTPUT_NAME = "adsbOutput";
    public static final String OUTPUT_LABEL = "ADS-B Aircraft";
    public static final String OUTPUT_DESCRIPTION = "Aircraft position, speed, and identification data decoded from 1090 MHz ADS-B Mode S transmissions";

    private final AdsbDecoder decoder = new AdsbDecoder();
    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    public RtlSdrOutputAdsb(RtlSdrDriver parentSensor) {
        super(OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        GeoPosHelper geoHelper = new GeoPosHelper();
        SWEHelper sweHelper = new SWEHelper();

        SWEBuilders.DataRecordBuilder recordBuilder = sweHelper.createRecord()
                .name(OUTPUT_NAME)
                .label(OUTPUT_LABEL)
                .description(OUTPUT_DESCRIPTION)
                .definition(SWEHelper.getPropertyUri("AdsbAircraftOutput"))
                .addField("sampleTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time data was received")
                        .definition(SWEConstants.DEF_SAMPLING_TIME))
                .addField("icaoAddress", sweHelper.createText()
                        .label("ICAO Address")
                        .definition(SWEHelper.getPropertyUri("IcaoAddress"))
                        .description("24-bit ICAO transponder address uniquely identifying the aircraft"))
                .addField("callsign", sweHelper.createText()
                        .label("Callsign")
                        .definition("urn:osh:def:aero:Callsign")
                        .description("Assigned flight number or aircraft callsign"))
                .addField("pos", geoHelper.createLocationVectorLLA()
                        .label("Location")
                        .description("Aircraft geographic position (latitude, longitude, altitude)"))
                .addField("alt_baro", sweHelper.createQuantity()
                        .uomCode("[ft_i]")
                        .label("Barometric Altitude")
                        .definition("http://sweetontology.net/propSpaceDistance/BarometricAltitude")
                        .description("Barometric pressure altitude in feet"))
                .addField("gs", sweHelper.createQuantity()
                        .uomCode("[kn_i]")
                        .label("Ground Speed")
                        .definition("http://sweetontology.net/propSpeed/GroundSpeed")
                        .description("Aircraft ground speed in knots"))
                .addField("track", sweHelper.createQuantity()
                        .uomCode("deg")
                        .label("True Track")
                        .definition("urn:osh:def:aero:TrueTrack")
                        .description("Aircraft true track angle in degrees"))
                .addField("alt_rate", sweHelper.createQuantity()
                        .uomCode("[ft_i]/min")
                        .label("Vertical Rate")
                        .definition("http://sweetontology.net/propSpeed/VerticalVelocity")
                        .description("Climb or descent rate in feet per minute"))
                .addField("squawk", sweHelper.createText()
                        .label("Squawk Code")
                        .definition(SWEHelper.getPropertyUri("SquawkCode"))
                        .description("4-digit octal transponder code"))
                .addField("alert", sweHelper.createBoolean()
                        .label("Alert Flag")
                        .definition(SWEHelper.getPropertyUri("AlertFlag"))
                        .description("Transponder alert flag"))
                .addField("emergency", sweHelper.createBoolean()
                        .label("Emergency Flag")
                        .definition(SWEHelper.getPropertyUri("EmergencyFlag"))
                        .description("Aircraft emergency status"))
                .addField("isOnGround", sweHelper.createBoolean()
                        .label("Is On Ground")
                        .definition(SWEHelper.getPropertyUri("IsOnGround"))
                        .description("Indicates whether aircraft is on the ground"));

        dataStruct = recordBuilder.build();
        dataEncoding = sweHelper.newTextEncoding(",", "\n");

        decoder.addListener(this::publishAircraftState);
    }

    public AdsbDecoder getDecoder() {
        return decoder;
    }

    public void processSamples(byte[] buffer, int len, int sampleRateHz) {
        decoder.processSamples(buffer, len, sampleRateHz);
    }

    public synchronized void publishAircraftState(AircraftState state) {
        try {
            String foiUID = parentSensor.addOrUpdateAircraftFoi(state.icao, state.callsign);

            DataBlock dataBlock;
            if (latestRecord == null) {
                dataBlock = dataStruct.createDataBlock();
            } else {
                dataBlock = latestRecord.renew();
            }

            int idx = 0;
            dataBlock.setDoubleValue(idx++, System.currentTimeMillis() / 1000.0);
            dataBlock.setStringValue(idx++, state.icao);
            dataBlock.setStringValue(idx++, state.callsign != null ? state.callsign : "");
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.lat) ? state.lat : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.lon) ? state.lon : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.altBaroFt) ? state.altBaroFt : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.altBaroFt) ? state.altBaroFt : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.groundSpeed) ? state.groundSpeed : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.track) ? state.track : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.verticalRate) ? state.verticalRate : 0.0);
            dataBlock.setStringValue(idx++, state.squawk != null ? state.squawk : "");
            dataBlock.setBooleanValue(idx++, state.alert);
            dataBlock.setBooleanValue(idx++, state.emergency);
            dataBlock.setBooleanValue(idx++, state.isOnGround);

            latestRecord = dataBlock;
            latestRecordTime = System.currentTimeMillis();

            eventHandler.publish(new DataEvent(latestRecordTime, this, foiUID, dataBlock));
        } catch (Exception e) {
            getLogger().error("Error publishing ADS-B aircraft record: {}", e.getMessage());
        }
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
        return 1.0;
    }
}
