package org.sensorhub.impl.sensor.vaisala.outputs;


import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;

import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherSensor;
import org.vast.swe.SWEHelper;

public class VaisalaWeatherSupervisorOutput extends AbstractSensorOutput<VaisalaWeatherSensor>
{
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    private static final String OUTPUT_NAME = "supervisorOutput";
    private static final String OUTPUT_LABEL = "Supervisor Output";
    private static final String OUTPUT_DESCRIPTION = "Output for supervisor observations from Vaisala Weather Station";

    
    public VaisalaWeatherSupervisorOutput(VaisalaWeatherSensor parentSensor)
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
                .addField("temperature", fac.createQuantity()
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

        String[] supMessage = message.split(","); // split sup message

        DataBlock dataBlock = latestRecord == null ? dataStruct.createDataBlock() : latestRecord.renew();
        dataBlock.setDoubleValue(0, currentTime / 1000d);

        // parse sup message and place data in block
    	for (int cnt = 1; cnt < supMessage.length; cnt++)
    	{
    		if (supMessage[cnt].startsWith("Th"))
    			if (supMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(supMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (supMessage[cnt].startsWith("Vh"))
    			if (supMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(supMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (supMessage[cnt].startsWith("Vs"))
    			if (supMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(supMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (supMessage[cnt].startsWith("Vr"))
    			if (supMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(supMessage[cnt].replaceAll("[^0-9.]", "")));
    				continue;
    			}
    		
    		else if (supMessage[cnt].startsWith("Id"))
    			if (supMessage[cnt].endsWith("#"))
    			{
    				dataBlock.setDoubleValue(cnt, Double.NaN);
    				continue;
    			}
    			else
    			{
    				dataBlock.setDoubleValue(cnt, Double.parseDouble(supMessage[cnt].replaceAll("[^0-9.]", "")));
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
