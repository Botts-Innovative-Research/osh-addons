/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
Developer are Copyright (C) 2014 the Initial Developer. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.hcsr04;

import net.opengis.swe.v20.*;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.data.DataEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.TimeZone;

import net.opengis.gml.v32.AbstractFeature;

import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;


public class Output extends AbstractSensorOutput<Sensor> {
    private static final String SENSOR_OUTPUT_NAME = "DistanceOutput";
    private static final String SENSOR_OUTPUT_LABEL = "Distance Output";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Output data from the HC-SR04 Sensor";

    DataRecord dataRecord;
    DataEncoding dataEncoding;

    public Output(Sensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);

        initializeDataRecord();
        initializeDataEncoding();
    }

    /**
     * Sets the data for the sensor output.
     *
     * @param distance long indicating the distance in cm to the nearest object detected
     */
    public void setData(long distance) {
        long timestamp = System.currentTimeMillis();
        DataBlock dataBlock = latestRecord == null ? dataRecord.createDataBlock() : latestRecord.renew();

        dataBlock.setDoubleValue(0, timestamp / 1000d);
        dataBlock.setLongValue(1, distance);

        latestRecord = dataBlock;
        eventHandler.publish(new DataEvent(timestamp, Output.this, dataBlock));
    }

    /**
     * Initializes the data record for the sensor output.
     */
    private void initializeDataRecord() {
        SWEHelper sweHelper = new SWEHelper();

        dataRecord = sweHelper.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .addField("sampleTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("distance", sweHelper.createQuantity()
                        .label("Distance (cm)")
                        .description("Distance to nearest object detected"))
                .build();
    }

    /**
     * Initializes the data encoding for the sensor output.
     */
    private void initializeDataEncoding() {
        dataEncoding = new SWEHelper().newTextEncoding(",", "\n");
    }

    @Override
    public double getAverageSamplingPeriod() {
        return Double.NaN;
    }

    @Override
    public DataComponent getRecordDescription() {
        return dataRecord;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }
}
