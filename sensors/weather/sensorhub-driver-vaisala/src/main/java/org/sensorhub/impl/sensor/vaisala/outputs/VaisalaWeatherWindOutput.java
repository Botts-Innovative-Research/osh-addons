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

public class VaisalaWeatherWindOutput extends AbstractSensorOutput<VaisalaWeatherSensor> {
    private static final String OUTPUT_NAME = "windOutput";
    private static final String OUTPUT_LABEL = "Wind Output";
    private static final String OUTPUT_DESCRIPTION = "Output for wind observations from Vaisala Weather Station";
    DataRecord dataStruct;
    DataEncoding dataEncoding;


    public VaisalaWeatherWindOutput(VaisalaWeatherSensor parentSensor) {
        super(OUTPUT_NAME, parentSensor);
    }


    public void doInit() {
        SWEHelper fac = new SWEHelper();

        dataStruct = fac.createRecord()
                .name(getName())
                .label(OUTPUT_LABEL)
                .definition(SWEHelper.getPropertyUri("Wind"))
                .description(OUTPUT_DESCRIPTION)
                .addField("sampleTime", fac.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("windDirectionMinimum", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindDirection"))
                        .label("Minimum Wind Direction")
                        .uom("deg")
                        .refFrame("http://sensorml.com/ont/swe/property/NED")
                        .axisId("z"))
                .addField("windDirectionAverage", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindDirection"))
                        .label("Average Wind Direction")
                        .uom("deg")
                        .refFrame("http://sensorml.com/ont/swe/property/NED")
                        .axisId("z"))
                .addField("windDirectionMaximum", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindDirection"))
                        .label("Maximum Wind Direction")
                        .uom("deg")
                        .refFrame("http://sensorml.com/ont/swe/property/NED")
                        .axisId("z"))
                .addField("windSpeedMinimum", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindSpeed"))
                        .label("Minimum Wind Speed")
                        .uom("[mi_i]/h"))
                .addField("windSpeedAverage", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindSpeed"))
                        .label("Average Wind Speed")
                        .uom("[mi_i]/h"))
                .addField("windSpeedMaximum", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("WindSpeed"))
                        .label("Maximum Wind Speed")
                        .uom("[mi_i]/h"))
                .build();

        dataEncoding = fac.newTextEncoding(",", "\n");
    }


    public void setData(VaisalaWeatherData weather) {
        DataBlock dataBlock = dataStruct.createDataBlock();
        dataBlock.setDoubleValue(0, weather.sampleTime / 1000d);
        dataBlock.setDoubleValue(1, weather.windDirectionMinimum);
        dataBlock.setDoubleValue(2, weather.windDirectionAverage);
        dataBlock.setDoubleValue(3, weather.windDirectionMaximum);
        dataBlock.setDoubleValue(4, weather.windSpeedMinimum);
        dataBlock.setDoubleValue(5, weather.windSpeedAverage);
        dataBlock.setDoubleValue(6, weather.windSpeedMaximum);

        String foiUID = parentSensor.getSamplingFoiUID();

        latestRecord = dataBlock;
        latestRecordTime = weather.sampleTime;
        eventHandler.publish(new DataEvent(latestRecordTime, this, foiUID, dataBlock));
    }

    @Override
    public double getAverageSamplingPeriod() {
        // sample every 1 second
        return 1.0;
    }


    @Override
    public DataComponent getRecordDescription() {
        return dataStruct;
    }


    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }
}
