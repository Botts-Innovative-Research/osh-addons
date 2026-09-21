package org.sensorhub.impl.sensor.vaisala.outputs;


import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;

import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
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


    public void parseAndPublish(String message) {
        long currentTime = System.currentTimeMillis();

        String[] ptuMessage = message.split(","); // split sup message

        DataBlock dataBlock = latestRecord == null ? dataStruct.createDataBlock() : latestRecord.renew();
        dataBlock.setDoubleValue(0, currentTime / 1000d);
    	
    	// parse ptu message and place data in block
    	for (int cnt = 1; cnt < ptuMessage.length; cnt++)
    	{
    		/**************************** PTU Messages ****************************/
    		if (ptuMessage[cnt].startsWith("Ta"))
    			if (ptuMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(ptuMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (ptuMessage[cnt].startsWith("Tp"))
    			if (ptuMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(ptuMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (ptuMessage[cnt].startsWith("Ua"))
    			if (ptuMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(ptuMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (ptuMessage[cnt].startsWith("Pa"))
    			if (ptuMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(ptuMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		else
    			getLogger().debug("Unrecognized Parameter");
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
