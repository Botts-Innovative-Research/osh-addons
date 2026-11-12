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
import org.sensorhub.impl.sensor.rtlsdr.dsp.AisDecoder;
import org.sensorhub.impl.sensor.rtlsdr.dsp.AisDecoder.VesselState;
import org.vast.swe.SWEBuilders;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

/**
 * OpenSensorHub sensor output providing structured SWE DataRecords for Marine AIS vessel tracks.
 */
public class RtlSdrOutputAis extends AbstractSensorOutput<RtlSdrDriver> {
    public static final String OUTPUT_NAME = "aisOutput";
    public static final String OUTPUT_LABEL = "AIS Maritime Vessels";
    public static final String OUTPUT_DESCRIPTION = "Marine vessel position, velocity, and voyage data decoded from VHF AIS transmissions (161.975 & 162.025 MHz)";

    private final AisDecoder decoder = new AisDecoder();
    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    public RtlSdrOutputAis(RtlSdrDriver parentSensor) {
        super(OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        GeoPosHelper geoHelper = new GeoPosHelper();
        SWEHelper sweHelper = new SWEHelper();

        SWEBuilders.DataRecordBuilder recordBuilder = sweHelper.createRecord()
                .name(OUTPUT_NAME)
                .label(OUTPUT_LABEL)
                .description(OUTPUT_DESCRIPTION)
                .definition(SWEHelper.getPropertyUri("AisVesselOutput"))
                .addField("samplingTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time data was received")
                        .definition(SWEConstants.DEF_SAMPLING_TIME))
                .addField("messageId", sweHelper.createCount()
                        .label("Message ID")
                        .description("AIS Message Type ID (1, 2, 3, 5, 18, 19)")
                        .definition(SWEHelper.getPropertyUri("MessageId")))
                .addField("mmsi", sweHelper.createCount()
                        .label("MMSI")
                        .description("Maritime Mobile Service Identity uniquely identifying the vessel")
                        .definition(SWEHelper.getPropertyUri("Mmsi")))
                .addField("vesselName", sweHelper.createText()
                        .label("Vessel Name")
                        .description("Name of the ship or station")
                        .definition(SWEHelper.getPropertyUri("VesselName")))
                .addField("callsign", sweHelper.createText()
                        .label("Callsign")
                        .description("Vessel radio callsign")
                        .definition(SWEHelper.getPropertyUri("Callsign")))
                .addField("sog", sweHelper.createQuantity()
                        .uomCode("[kn_i]")
                        .label("Speed Over Ground")
                        .description("Vessel speed over ground in knots")
                        .definition("http://sweetontology.net/propSpeed/GroundSpeed"))
                .addField("positionAccuracy", sweHelper.createBoolean()
                        .label("Position Accuracy")
                        .description("DGPS accuracy flag (true = high < 10m, false = low > 10m)")
                        .definition(SWEHelper.getPropertyUri("PositionAccuracy")))
                .addField("location", geoHelper.createLocationVectorLatLon()
                        .label("Location")
                        .description("Geographic position (latitude, longitude)"))
                .addField("cog", sweHelper.createQuantity()
                        .uomCode("deg")
                        .label("Course Over Ground")
                        .description("Course over ground in degrees")
                        .definition(SWEHelper.getPropertyUri("CourseOverGround")))
                .addField("heading", sweHelper.createQuantity()
                        .uomCode("deg")
                        .label("True Heading")
                        .description("True ship heading in degrees")
                        .definition(SWEHelper.getPropertyUri("TrueHeading")))
                .addField("navStatus", sweHelper.createText()
                        .label("Navigation Status")
                        .description("Current navigational status description")
                        .definition(SWEHelper.getPropertyUri("NavigationStatus")))
                .addField("shipType", sweHelper.createText()
                        .label("Ship Type")
                        .description("Vessel or cargo type")
                        .definition(SWEHelper.getPropertyUri("ShipType")));

        dataStruct = recordBuilder.build();
        dataEncoding = sweHelper.newTextEncoding(",", "\n");

        decoder.addListener(this::publishVesselState);
    }

    public AisDecoder getDecoder() {
        return decoder;
    }

    public void processSamples(byte[] buffer, int len, int inRateHz) {
        decoder.processSamples(buffer, len, inRateHz);
    }

    public synchronized void publishVesselState(VesselState state) {
        try {
            String foiUID = parentSensor.addOrUpdateVesselFoi(state.mmsi, state.vesselName);

            DataBlock dataBlock;
            if (latestRecord == null) {
                dataBlock = dataStruct.createDataBlock();
            } else {
                dataBlock = latestRecord.renew();
            }

            int idx = 0;
            dataBlock.setDoubleValue(idx++, System.currentTimeMillis() / 1000.0);
            dataBlock.setIntValue(idx++, state.messageId);
            dataBlock.setLongValue(idx++, state.mmsi);
            dataBlock.setStringValue(idx++, state.vesselName != null ? state.vesselName : "");
            dataBlock.setStringValue(idx++, state.callsign != null ? state.callsign : "");
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.sog) ? state.sog : 0.0);
            dataBlock.setBooleanValue(idx++, state.positionAccuracy);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.lat) ? state.lat : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.lon) ? state.lon : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.cog) ? state.cog : 0.0);
            dataBlock.setDoubleValue(idx++, !Double.isNaN(state.heading) ? state.heading : 0.0);
            dataBlock.setStringValue(idx++, state.navStatus != null ? state.navStatus : "");
            dataBlock.setStringValue(idx++, state.shipType != null ? state.shipType : "");

            latestRecord = dataBlock;
            latestRecordTime = System.currentTimeMillis();

            eventHandler.publish(new DataEvent(latestRecordTime, this, foiUID, dataBlock));
        } catch (Exception e) {
            getLogger().error("Error publishing AIS vessel record: {}", e.getMessage());
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
