package org.sensorhub.impl.sensor.vaisala.outputs;

import net.opengis.swe.v20.*;

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
                        .uom("[in_i]"))
                .addField("HailDuration", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailDuration"))
                        .label("Hail Duration")
                        .uom("s"))
                .addField("hailIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailIntensity"))
                        .label("Hail Intensity")
                        .uom("[in_i]/h"))
                .addField("rainPeakIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("RainPeakIntensity"))
                        .label("Rain Peak Intensity")
                        .uom("[in_i]/h"))
                .addField("hailPeakIntensity", fac.createQuantity()
                        .definition(SWEHelper.getPropertyUri("HailPeakIntensity"))
                        .label("Hail Peak Intensity")
                        .uom("[in_i]/h"))
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


    public void parseAndPublish(String message) {
        long currentTime = System.currentTimeMillis();

        String[] compMessage = message.split(",");

        DataBlock dataBlock = latestRecord == null ? dataStruct.createDataBlock() : latestRecord.renew();
        dataBlock.setDoubleValue(0, currentTime / 1000d);

        for (int cnt = 1; cnt < compMessage.length; cnt++)
    	{
    		/*************************** Wind Messages ****************************/
    		if (compMessage[cnt].startsWith("Dn"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Dm"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Dx"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Sn"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Sm"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Sx"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}

    		else if (compMessage[cnt].startsWith("Ta"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Tp"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Ua"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Pa"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		else if (compMessage[cnt].startsWith("Rc"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Rd"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Ri"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Hc"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Hd"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Hi"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Rp"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Hp"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		else if (compMessage[cnt].startsWith("Th"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Vh"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Vs"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Vr"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (compMessage[cnt].startsWith("Id"))
    			if (compMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(compMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		else
                getLogger().error("Unrecognized Parameter");
    	}
    	
    	latestRecord = dataBlock;
    	latestRecordTime = System.currentTimeMillis();
    	eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
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
