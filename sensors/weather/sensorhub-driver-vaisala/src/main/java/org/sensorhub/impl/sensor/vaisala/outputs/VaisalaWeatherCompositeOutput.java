package org.sensorhub.impl.sensor.vaisala.outputs;

import net.opengis.swe.v20.*;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherData;

import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherSensor;
import org.vast.swe.SWEHelper;

public class VaisalaWeatherCompositeOutput extends AbstractSensorOutput<VaisalaWeatherSensor>
{
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    private static final String OUTPUT_NAME = "weatherOutput";
    private static final String OUTPUT_LABEL = "Weather Output";
    private static final String OUTPUT_DESCRIPTION = "Output for all weather observations from Vaisala Weather Station";

    public VaisalaWeatherCompositeOutput(VaisalaWeatherSensor parentSensor)
    {
        super(OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        SWEHelper fac = new SWEHelper();

        dataStruct = fac.createRecord()
                .name(getName())
                .label(OUTPUT_LABEL)
                .definition(SWEHelper.getPropertyUri("Weather"))
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

                .addField("temperatureHeater", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Temperature"))
                        .label("Heater Temperature")
                        .uom("[degF]"))
                .addField("heatingVoltage", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Voltage"))
                        .label("Heating Voltage")
                        .description("Voltage of the internal heating element")
                        .uom("V"))
                .addField("supplyVoltage", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Voltage"))
                        .label("Supply Voltage")
                        .uom("V"))
                .addField("referenceVoltage", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("Voltage"))
                        .label("Reference Voltage")
                        .uom("V"))
                .addField("information", fac.createText()
                        .definition(SWEHelper.getPropertyUri("Information"))
                        .label("Information"))
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
        dataBlock.setDoubleValue(7, weather.pressure);
        dataBlock.setDoubleValue(8, weather.temperature);
        dataBlock.setDoubleValue(9, weather.temperatureInternal);
        dataBlock.setDoubleValue(10, weather.relativeHumidity);
        dataBlock.setDoubleValue(11, weather.rainAccumulation);
        dataBlock.setDoubleValue(12, weather.rainDuration);
        dataBlock.setDoubleValue(13, weather.rainIntensity);
        dataBlock.setDoubleValue(14, weather.hailAccumulation);
        dataBlock.setDoubleValue(15, weather.hailDuration);
        dataBlock.setDoubleValue(16, weather.hailIntensity);
        dataBlock.setDoubleValue(17, weather.rainPeakIntensity);
        dataBlock.setDoubleValue(18, weather.hailPeakIntensity);
        dataBlock.setDoubleValue(19, weather.temperatureHeater);
        dataBlock.setDoubleValue(20, weather.heatingVoltage);
        dataBlock.setDoubleValue(21, weather.supplyVoltage);
        dataBlock.setDoubleValue(22, weather.referenceVoltage);
        dataBlock.setStringValue(23, weather.information);

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
