package org.sensorhub.impl.sensor.vaisala.outputs;


import net.opengis.swe.v20.*;

import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherSensor;
import org.vast.swe.SWEHelper;

public class VaisalaWeatherWindOutput extends AbstractSensorOutput<VaisalaWeatherSensor>
{
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    private static final String OUTPUT_NAME = "windOutput";
    private static final String OUTPUT_LABEL = "Wind Output";
    private static final String OUTPUT_DESCRIPTION = "Output for wind observations from Vaisala Weather Station";


    public VaisalaWeatherWindOutput(VaisalaWeatherSensor parentSensor)
    {
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

    
    public void parseAndPublish(String message) {
        long currentTime = System.currentTimeMillis();

        String[] windMessage = message.split(","); // split wind message

        DataBlock dataBlock = latestRecord == null ? dataStruct.createDataBlock() : latestRecord.renew();
    	dataBlock.setDoubleValue(0, currentTime / 1000d);
    	
    	for (int cnt = 1; cnt < windMessage.length; cnt++)
    	{
    		if (windMessage[cnt].startsWith("Dn"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (windMessage[cnt].startsWith("Dm"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (windMessage[cnt].startsWith("Dx"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (windMessage[cnt].startsWith("Sn"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (windMessage[cnt].startsWith("Sm"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (windMessage[cnt].startsWith("Sx"))
    			if (windMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(windMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		else
                getLogger().error("Unrecognized Parameter");
    	}
    	
    	latestRecord = dataBlock;
    	latestRecordTime = currentTime;
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
