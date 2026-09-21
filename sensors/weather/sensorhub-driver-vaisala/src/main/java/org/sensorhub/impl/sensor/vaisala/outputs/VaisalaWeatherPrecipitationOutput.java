package org.sensorhub.impl.sensor.vaisala.outputs;

import java.util.Map;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.vaisala.VaisalaWeatherSensor;
import org.vast.swe.SWEHelper;

public class VaisalaWeatherPrecipitationOutput extends AbstractSensorOutput<VaisalaWeatherSensor>
{
    DataRecord dataStruct;
    DataEncoding dataEncoding;

    private static final String OUTPUT_NAME = "precipitationOutput";
    private static final String OUTPUT_LABEL = "Precipitation Output";
    private static final String OUTPUT_DESCRIPTION = "Output for precipitation observations from  Vaisala Weather Station";

    private static final Map<String, Integer> TAG_TO_INDEX = Map.of(
            "Rc", 1,  // rainAccumulation
            "Rd", 2,  // rainDuration
            "Ri", 3,  // rainIntensity
            "Hc", 4,  // hailAccumulation
            "Hd", 5,  // hailDuration
            "Hi", 6,  // hailIntensity
            "Rp", 7,  // rainPeakIntensity
            "Hp", 8   // hailPeakIntensity
    );

    public VaisalaWeatherPrecipitationOutput(VaisalaWeatherSensor parentSensor)
    {
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
                .build();

        dataEncoding = fac.newTextEncoding(",", "\n");
    }

    public void parseAndPublish(String precipInMessage) {
        long currentTime = System.currentTimeMillis();

        DataBlock dataBlock = latestRecord == null ? dataStruct.createDataBlock() : latestRecord.renew();

        dataBlock.setDoubleValue(0, currentTime / 1000d);
        for (int i = 0; i < TAG_TO_INDEX.get(precipInMessage); i++) {
            dataBlock.setDoubleValue(i, Double.NaN);
        }

        String[] tokens = precipInMessage.split(",");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < 2) {
                continue;
            }

            String tag = token.substring(0, 2);
            Integer index = TAG_TO_INDEX.get(tag);
            if (index == null) {
                getLogger().warn("Unrecognized precipitation field tag: '{}' in message '{}'" + tag, precipInMessage);
                continue;
            }

            dataBlock.setDoubleValue(index, parseValue(token, tag, precipInMessage));
        }

        latestRecord = dataBlock;
        latestRecordTime = currentTime;
        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
    }

    private double parseValue(String token, String tag, String fullMessage)
    {
        if (token.endsWith("#"))
            return Double.NaN;

        try
        {
            return Double.parseDouble(token.replaceAll("[^0-9.]", ""));
        }
        catch (NumberFormatException e)
        {
            getLogger().warn("Could not parse value for '{}' in message: {}", tag, fullMessage);
            return Double.NaN;
        }
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
