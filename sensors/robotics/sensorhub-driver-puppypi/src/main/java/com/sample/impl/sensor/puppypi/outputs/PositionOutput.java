/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2021 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
package com.sample.impl.sensor.puppypi.outputs;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;
import com.sample.impl.sensor.puppypi.Sensor;

/**
 * Output specification and provider for {@link Sensor}.
 *
 * @author your_name
 * @since date
 */
public class PositionOutput extends AbstractSensorOutput<Sensor> {

    private static final String SENSOR_OUTPUT_NAME = "Position";
    private static final String SENSOR_OUTPUT_LABEL = "Position";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Coordinates of the puppy pi";

    private static final Logger logger = LoggerFactory.getLogger(PositionOutput.class);

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    /**
     * Constructor
     *
     * @param parentSensor Sensor driver providing this output
     */
    public PositionOutput(Sensor parentSensor) {

        super(SENSOR_OUTPUT_NAME, parentSensor);

        logger.debug("Output created");
    }

    /**
     * Initializes the data structure for the output, defining the fields, their ordering,
     * and data types.
     */
    public void doInit() {
        GeoPosHelper geoFac = new GeoPosHelper();
        SWEHelper sweHelper = new SWEHelper();
        dataEncoding = new TextEncodingImpl(",","/n");

        dataStruct = geoFac.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .definition(sweHelper.getPropertyUri(SENSOR_OUTPUT_NAME))
                .addField("SampleTime",
                        sweHelper.createTime()
                                .asSamplingTimeIsoUTC()
                                .build())
                .addField("Position",
                        geoFac.newLocationVectorLLA(SWEHelper.getPropertyUri("location")))
                .build();
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
        return Double.NaN;
    }
}
