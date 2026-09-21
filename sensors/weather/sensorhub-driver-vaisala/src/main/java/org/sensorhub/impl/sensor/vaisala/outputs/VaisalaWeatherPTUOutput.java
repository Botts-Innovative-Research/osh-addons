package org.sensorhub.impl.sensor.vaisala.outputs;


import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;

import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherData;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherSensor;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

public class VaisalaWeatherPTUOutput extends AbstractSensorOutput<VaisalaWeatherSensor>
{
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    private static final String OUTPUT_NAME = "ptuOutput";
    private static final String OUTPUT_LABEL = "PTU Output";
    private static final String OUTPUT_DESCRIPTION = "Output for ptu observations from Vaisala Weather Station";

    public VaisalaWeatherPTUOutput(VaisalaWeatherSensor parentSensor)
    {
        super(OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        SWEHelper fac = new SWEHelper();
        GeoPosHelper geo = new GeoPosHelper();

        dataStruct = fac.createRecord()
                .name(getName())
                .label(OUTPUT_LABEL)
                .definition(SWEHelper.getPropertyUri("PTU"))
                .description(OUTPUT_DESCRIPTION)
                .addField("sampleTime", fac.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("pressure", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("BarometricPressure"))
                        .label("Barometric Pressure")
                        .uom("[in_i'Hg]"))
                .addField("temperature", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Temperature"))
                        .label("Air Temperature")
                        .uom("[degF]"))
                .addField("temperatureInternal", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Temperature"))
                        .label("Internal Temperature")
                        .uom("[degF]"))
                .addField("relativeHumidity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Humidity"))
                        .label("Relative Humidity")
                        .uom("%"))
                .build();

        dataEncoding = fac.newTextEncoding(",", "\n");
    }

    public void setData(VaisalaWeatherData weather) {
        DataBlock dataBlock = dataStruct.createDataBlock();
        dataBlock.setDoubleValue(0, weather.sampleTime / 1000d);
        dataBlock.setDoubleValue(1, weather.pressure);
        dataBlock.setDoubleValue(2, weather.temperature);
        dataBlock.setDoubleValue(3, weather.temperatureInternal);
        dataBlock.setDoubleValue(4, weather.relativeHumidity);

        String foiUID = parentSensor.getSamplingFoiUID();

        latestRecord = dataBlock;
        latestRecordTime = weather.sampleTime;
        eventHandler.publish(new DataEvent(latestRecordTime, this, foiUID, dataBlock));
    }


    @Override
    public double getAverageSamplingPeriod()
    {
    	// sample every 1 second
        return 1.0;
    }


    @Override
    public DataComponent getRecordDescription()
    {
        return dataStruct;
    }


    @Override
    public DataEncoding getRecommendedEncoding()
    {
        return dataEncoding;
    }
}
