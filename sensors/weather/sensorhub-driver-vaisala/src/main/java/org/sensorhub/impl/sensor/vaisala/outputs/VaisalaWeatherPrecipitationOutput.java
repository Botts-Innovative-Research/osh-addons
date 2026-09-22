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

public class VaisalaWeatherPrecipitationOutput extends AbstractSensorOutput<VaisalaWeatherSensor> {
    private static final String OUTPUT_NAME = "precipitationOutput";
    private static final String OUTPUT_LABEL = "Precipitation Output";
    private static final String OUTPUT_DESCRIPTION = "Output for precipitation observations from  Vaisala Weather Station";
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    public VaisalaWeatherPrecipitationOutput(VaisalaWeatherSensor parentSensor) {
        super(OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        SWEHelper fac = new SWEHelper();

        dataStruct = fac.createRecord()
                .name(getName())
                .label(OUTPUT_LABEL)
                .definition(SWEHelper.getPropertyUri("Precipitation"))
                .description(OUTPUT_DESCRIPTION)
                .addField("sampleTime", fac.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("rainAccumulation", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("RainAccumulation"))
                        .label("Rain Accumulation")
                        .uom("[in_i]"))
                .addField("rainDuration", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("RainDuration"))
                        .label("Rain Duration")
                        .uom("s"))
                .addField("rainIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("RainIntensity"))
                        .label("Rain Intensity")
                        .uom("[in_i]/h"))
                .addField("hailAccumulation", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailAccumulation"))
                        .label("Hail Accumulation")
                        .description("Number of hail hits per square inch")
                        .uom("1/[in_i]2"))
                .addField("HailDuration", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailDuration"))
                        .label("Hail Duration")
                        .uom("s"))
                .addField("hailIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailIntensity"))
                        .label("Hail Intensity")
                        .description("Hail hits per square inch per hour")
                        .uom("1/[in_i]2/h"))
                .addField("rainPeakIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("RainPeakIntensity"))
                        .label("Rain Peak Intensity")
                        .uom("[in_i]/h"))
                .addField("hailPeakIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailPeakIntensity"))
                        .label("Hail Peak Intensity")
                        .description("Peak hail hits per square inch per hour")
                        .uom("1/[in_i]2/h"))
                .build();

        dataEncoding = fac.newTextEncoding(",", "\n");
    }

    public void setData(VaisalaWeatherData weather) {
        DataBlock dataBlock = dataStruct.createDataBlock();
        dataBlock.setDoubleValue(0, weather.sampleTime / 1000d);
        dataBlock.setDoubleValue(1, weather.rainAccumulation);
        dataBlock.setDoubleValue(2, weather.rainDuration);
        dataBlock.setDoubleValue(3, weather.rainIntensity);
        dataBlock.setDoubleValue(4, weather.hailAccumulation);
        dataBlock.setDoubleValue(5, weather.hailDuration);
        dataBlock.setDoubleValue(6, weather.hailIntensity);
        dataBlock.setDoubleValue(7, weather.rainPeakIntensity);
        dataBlock.setDoubleValue(8, weather.hailPeakIntensity);

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
