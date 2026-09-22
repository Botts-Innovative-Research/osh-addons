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

public class VaisalaWeatherSupervisorOutput extends AbstractSensorOutput<VaisalaWeatherSensor> {
    private static final String OUTPUT_NAME = "supervisorOutput";
    private static final String OUTPUT_LABEL = "Supervisor Output";
    private static final String OUTPUT_DESCRIPTION = "Output for supervisor observations from Vaisala Weather Station";
    DataRecord dataStruct;
    DataEncoding dataEncoding;


    public VaisalaWeatherSupervisorOutput(VaisalaWeatherSensor parentSensor) {
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

    public void setData(VaisalaWeatherData weather) {
        DataBlock dataBlock = dataStruct.createDataBlock();
        dataBlock.setDoubleValue(0, weather.sampleTime / 1000d);
        dataBlock.setDoubleValue(1, weather.temperatureHeater);
        dataBlock.setDoubleValue(2, weather.heatingVoltage);
        dataBlock.setDoubleValue(3, weather.supplyVoltage);
        dataBlock.setDoubleValue(4, weather.referenceVoltage);
        dataBlock.setStringValue(5, weather.information);

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
