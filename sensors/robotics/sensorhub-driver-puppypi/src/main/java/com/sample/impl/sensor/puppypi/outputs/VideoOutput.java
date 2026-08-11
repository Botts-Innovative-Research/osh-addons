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

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.videocam.VideoCamHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.cdm.common.CDMException;
import org.vast.data.JSONEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;
import com.sample.impl.sensor.puppypi.Sensor;
import org.vast.swe.helper.RasterHelper;

import javax.xml.crypto.Data;

/**
 * Output specification and provider for {@link Sensor}.
 *
 * @author your_name
 * @since date
 */
public class VideoOutput extends AbstractSensorOutput<Sensor> {

    private static final String SENSOR_OUTPUT_NAME = "Video";
    private static final String SENSOR_OUTPUT_LABEL = "Puppy Pi Video";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "Video stream from puppy pi camera";
    private static final Integer VIDEO_FRAME_WIDTH = 640;
    private static final Integer VIDEO_FRAME_HEIGHT = 480;
    private static final String CODEC_MJPEG = "JPEG";

    private static final Logger logger = LoggerFactory.getLogger(VideoOutput.class);

    private DataComponent dataStruct;
    private DataEncoding dataEncoding;

    /**
     * Constructor
     *
     * @param parentSensor Sensor driver providing this output
     */
    public VideoOutput(Sensor parentSensor) {

        super(SENSOR_OUTPUT_NAME, parentSensor);

        logger.debug("Output created");
    }

    /**
     * Initializes the data structure for the output, defining the fields, their ordering,
     * and data types.
     */
    public void doInit() {

        logger.debug("Initializing video output.");

        VideoCamHelper sweHelper = new VideoCamHelper();

        // build output structure
        DataStream videoStream = sweHelper.newVideoOutputMJPEG(getName(), VIDEO_FRAME_WIDTH, VIDEO_FRAME_HEIGHT);
        // todo why did i add this?
        // videoStream.setEncoding(new JSONEncodingImpl());
        dataStruct = videoStream.getElementType();
        dataEncoding = videoStream.getEncoding();

        System.out.println("***");
        System.out.println(dataStruct.toString());

//        RasterHelper sweHelper = new RasterHelper();
//        dataStruct = sweHelper.createRecord()
//                .name(SENSOR_OUTPUT_NAME)
//                .label(SENSOR_OUTPUT_LABEL)
//                .description(SENSOR_OUTPUT_DESCRIPTION)
//                .definition(SWEHelper.getPropertyUri("VideoFrame"))
//                .addField("sampleTime", sweHelper.createTime()
//                        .asSamplingTimeIsoUTC()
//                        .label("Sample Time")
//                        .description("Time of data collection"))
//                .addField("phenomenonTime", sweHelper.createTime()
//                        .asPhenomenonTimeIsoUTC()
//                        .label("Phenomenon Time")
//                        .description("Time reported by sensor"))
//                .addField("img", sweHelper.newRgbImage(VIDEO_FRAME_WIDTH, VIDEO_FRAME_HEIGHT, DataType.BYTE))
//                .build();
//
//        BinaryEncoding dataEnc = sweHelper.newBinaryEncoding(ByteOrder.BIG_ENDIAN, ByteEncoding.RAW);
//
//        BinaryComponent sampleTimeEnc = sweHelper.newBinaryComponent();
//        sampleTimeEnc.setRef("/" + dataStruct.getComponent(0).getName());
//        sampleTimeEnc.setCdmDataType(DataType.DOUBLE);
//        dataEnc.addMemberAsComponent(sampleTimeEnc);
//
//        BinaryComponent phenomenonTimeEnc = sweHelper.newBinaryComponent();
//        phenomenonTimeEnc.setRef("/" + dataStruct.getComponent(1).getName());
//        phenomenonTimeEnc.setCdmDataType(DataType.DOUBLE);
//        dataEnc.addMemberAsComponent(phenomenonTimeEnc);
//
//        BinaryBlock compressedBlock = sweHelper.newBinaryBlock();
//        compressedBlock.setRef("/" + dataStruct.getComponent(2).getName());
//        compressedBlock.setCompression(CODEC_MJPEG);
//        dataEnc.addMemberAsBlock(compressedBlock);
//
//        try {
//            SWEHelper.assignBinaryEncoding(dataStruct, dataEnc);
//        } catch (CDMException e) {
//            throw new RuntimeException("Invalid binary encoding configuration", e);
//        }
//
//        this.dataEncoding = dataEnc;
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
