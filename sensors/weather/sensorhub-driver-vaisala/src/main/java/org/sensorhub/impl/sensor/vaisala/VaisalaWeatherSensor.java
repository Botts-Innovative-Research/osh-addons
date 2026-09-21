package org.sensorhub.impl.sensor.vaisala;

import java.io.*;
import net.opengis.sensorml.v20.IdentifierList;
import net.opengis.sensorml.v20.Term;

import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.vaisala.outputs.*;
import org.vast.ogc.om.SamplingPoint;
import net.opengis.gml.v32.impl.GMLFactory;
import org.vast.swe.SWEConstants;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.vast.sensorML.SMLFactory;
import org.vast.swe.SWEHelper;


public class VaisalaWeatherSensor extends AbstractSensorModule<VaisalaWeatherConfig> {
    static final String UID_PREFIX = "urn:osh:sensor:georobotix:vaisala:";

    RobustConnection connection;
    ICommProvider<?> commProvider;
    InputStream dataIn;
    MessageHandler messageHandler;

    VaisalaWeatherCompositeOutput compOut;
    VaisalaWeatherWindOutput windOut;
    VaisalaWeatherPrecipitationOutput precipOut;
    VaisalaWeatherPTUOutput ptuOut;
    VaisalaWeatherSupervisorOutput supOut;

    String modelNumber;
    String serialNumber = null;
    private String samplingFoiUID;
    String deviceAddress = null;

    String lastCommand = null;

    volatile boolean started;
    public final static char CR = (char) 0x0D;
    public final static char LF = (char) 0x0A;
    public final static String CRLF = "" + CR + LF;

    /******************** Settings Messages **************************/
    private String commsSettingsInit = "M=P,T=0,C=2,I=0,B=19200";
    private String commsSettingsAutoASCII = "M=A,I=1";
    /*****************************************************************/

    /******************* Editable Sensor Settings ********************/
    private String supervisorSettings1 = "R=0000000000100000";
    private String supervisorSettings2 = "I=15,S=N,H=N";
    private String windSettings1 = "R=0000000011111100";
    private String windSettings2 = "I=1,A=12,U=S,D=0,N=W,F=2";
    private String ptuSettings1 = "R=0000000011110000";
    private String ptuSettings2 = "I=60,P=I,T=F";
    private String precipSettings1 = "R=0000000010110111";
    private String precipSettings2 = "I=60,U=I,S=I,M=T,Z=A";
    /*****************************************************************/

    public VaisalaWeatherSensor() {}

    @Override
    protected void doInit() throws SensorHubException {
        super.doInit();
        if (config.commSettings == null)
            throw new SensorHubException("No communication settings specified");
        if (config.commandTimeoutMillis <= 0)
            throw new SensorHubException("Command timeout must be positive");

        serialNumber = config.serialNumber == null ? null : config.serialNumber.trim();

        // Generate identifiers
        this.uniqueID = "urn:osh:georobotix:sensor:vaisala:" + serialNumber;
        this.xmlID = "VAISALA_" + serialNumber.toUpperCase();

        // Add outputs
        createOutputs();

        createSamplingFoi();
        getLogger().info("Vaisala initialization complete: address={}, model={}", deviceAddress, modelNumber);
    }

    protected void tryConnection() throws SensorHubException {
        if(config.commSettings == null) throw new SensorHubException("No communication settings specified, please select and enter communication settings for sensor");

        connection = new RobustConnection(this, config.connection, "Vaisala Weather Station") {
            @Override
            public boolean tryConnect() throws IOException {
                try {
                    var moduleReg = getParentHub().getModuleRegistry();
                    commProvider = (ICommProvider<?>) moduleReg.loadSubModule(config.commSettings, true);
                    commProvider.start();

                    if (!commProvider.isStarted()) throw new SensorHubException("Comm provider failed to start. Check communication settings and try again.");

                    return true;
                } catch (SensorHubException e) {
                    reportError("Cannot connect to Vaisala Weather Station", e, true);
                    return false;
                }
            }
        };
        connection.waitForConnection();
    }

    private void createOutputs() {
        compOut = new VaisalaWeatherCompositeOutput(this);
        addOutput(compOut, false);
        compOut.doInit();

        windOut = new VaisalaWeatherWindOutput(this);
        addOutput(windOut, false);
        windOut.doInit();

        ptuOut = new VaisalaWeatherPTUOutput(this);
        addOutput(ptuOut, false);
        ptuOut.doInit();

        precipOut = new VaisalaWeatherPrecipitationOutput(this);
        addOutput(precipOut, false);
        precipOut.doInit();

        supOut = new VaisalaWeatherSupervisorOutput(this);
        addOutput(supOut, false);
        supOut.doInit();
    }

    private String sendAndReceive(String command) throws IOException {
        lastCommand = command;
        return messageHandler.sendAndAwait(command, config.commandTimeoutMillis);
    }

    private void createSamplingFoi() {
        samplingFoiUID = uniqueID + ":foi";
        SamplingPoint foi = new SamplingPoint();
        foi.setId("FOI_" + xmlID);
        foi.setUniqueIdentifier(samplingFoiUID);
        foi.setHostedProcedureUID(uniqueID);
        foi.setName(config.name == null ? "Vaisala" : config.name);
        foi.setDescription("Vaisala weather observations");
//        var location = config.getLocation();
//        if (location != null) {
//            var point = new GMLFactory(true).newPoint();
//            point.setSrsName(SWEConstants.REF_FRAME_4979);
//            point.setSrsDimension(3);
//            point.setPos(new double[] {location.lat, location.lon, location.alt});
//            foi.setShape(point);
//        }
        synchronized (foiMap) {
            foiMap.clear();
            addFoi(foi);
        }
    }

    public String getSamplingFoiUID() { return samplingFoiUID; }

    @Override
    protected void updateSensorDescription() {
        synchronized (sensorDescLock)
        {
            super.updateSensorDescription();
            // set identifiers in SensorML
            SMLFactory smlFac = new SMLFactory();

            if (!sensorDescription.isSetDescription())
                sensorDescription.setDescription("Vaisala Weather Transmitter " + modelNumber);

            IdentifierList identifierList = smlFac.newIdentifierList();
            sensorDescription.addIdentification(identifierList);

            Term term;
            term = smlFac.newTerm();
            term.setDefinition(SWEHelper.getPropertyUri("Manufacturer"));
            term.setLabel("Manufacturer Name");
            term.setValue("Vaisala");
            identifierList.addIdentifier(term);

            if (modelNumber != null)
            {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("ModelNumber"));
                term.setLabel("Model Number");
                term.setValue(modelNumber);
                identifierList.addIdentifier(term);
            }

            if (serialNumber != null)
            {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("SerialNumber"));
                term.setLabel("Serial Number");
                term.setValue(serialNumber);
                identifierList.addIdentifier(term);
            }

            // Long Name
            term = smlFac.newTerm();
            term.setDefinition(SWEHelper.getPropertyUri("LongName"));
            term.setLabel("Long Name");
            term.setValue("Vaisala " + modelNumber + " Weather Transmitter #" + serialNumber);
            identifierList.addIdentifier(term);

            // Short Name
            term = smlFac.newTerm();
            term.setDefinition(SWEHelper.getPropertyUri("ShortName"));
            term.setLabel("Short Name");
            term.setValue("Vaisala " + modelNumber);
            identifierList.addIdentifier(term);
        }
    }

    private void processMeasurement(String line, long receivedAt) {
        if (deviceAddress != null && !line.startsWith(deviceAddress)) return;
        VaisalaWeatherData weather = VaisalaWeatherData.parse(line);
        switch (line.substring(1, 3)) {
            case "R0":
                getLogger().info("Vaisala raw composite message: {}", line);
                compOut.setData(weather);
                break;
            case "R1": windOut.setData(weather); break;
            case "R2": ptuOut.setData(weather); break;
            case "R3": precipOut.setData(weather); break;
            case "R5": supOut.setData(weather); break;
            default: getLogger().debug("Unknown Vaisala measurement: {}", line);
        }
    }

    @Override
    protected void doStart() throws SensorHubException {
        try {
            tryConnection();
            dataIn = commProvider.getInputStream();
            var output = commProvider.getOutputStream();
            if (dataIn == null || output == null)
                throw new IOException("Communication provider started without serial streams");
            messageHandler = new MessageHandler(dataIn, output, this::processMeasurement,
                    error -> reportError("Vaisala reader failed", error), getLogger());
            messageHandler.start();
            deviceAddress = sendAndReceive("?");
            sendAndReceive(deviceAddress + "XU," + commsSettingsInit);
            String settings = sendAndReceive(deviceAddress + "XU");
            modelNumber = getSetting(settings, "N");
            sendAndReceive(deviceAddress + "SU," + supervisorSettings1);
            sendAndReceive(deviceAddress + "SU," + supervisorSettings2);
            sendAndReceive(deviceAddress + "WU," + windSettings1);
            sendAndReceive(deviceAddress + "WU," + windSettings2);
            sendAndReceive(deviceAddress + "TU," + ptuSettings1);
            sendAndReceive(deviceAddress + "TU," + ptuSettings2);
            sendAndReceive(deviceAddress + "RU," + precipSettings1);
            sendAndReceive(deviceAddress + "RU," + precipSettings2);
            sendAndReceive(deviceAddress + "XU," + commsSettingsAutoASCII);
            getLogger().info("Vaisala ready: address={}, model={}", deviceAddress, modelNumber);
        } catch (IOException | SensorHubException | RuntimeException e) {
            doStop();
            throw new SensorHubException("Error starting Vaisala after command " + lastCommand, e);
        }
    }

    private static String getSetting(String response, String key) throws IOException {
        for (String field : response.split(",")) {
            if (field.startsWith(key + "=")) return field.substring(key.length() + 1);
        }
        throw new IOException("Missing " + key + " in Vaisala response: " + response);
    }

    @Override
    protected void afterStart() {
        // Begin heartbeat check
        started = true;
        messageHandler.enablePublishing();
    }

    @Override
    protected void doStop() {
        logger.info("Stopping Vaisala Weather {} ...", getUniqueIdentifier());

        started = false;
        if (messageHandler != null) messageHandler.stop();

        if (dataIn != null)
        {
            try { dataIn.close(); }
            catch (IOException e) { }
        }

        if (connection != null) {
            try {
                connection.cancel();
            } catch (Exception e) {
                logger.error("Error canceling connection", e);
            }
        }
        if (commProvider != null) {
            try {
                if (commProvider.isStarted()) {
                    commProvider.stop();
                }
            } catch (Exception e) {
                logger.error("Error stopping comm module", e);
            } finally {
                commProvider = null;
            }
        }
        if (messageHandler != null) {
            try {
                messageHandler.awaitStopped(5000);
                messageHandler = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted waiting for Vaisala reader shutdown", e);
            } catch (IOException e) {
                logger.error("Vaisala reader shutdown failed", e);
            }
        }
        dataIn = null;
        logger.info("VaisalaWeather {} stopped", getUniqueIdentifier());
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isConnected()
                && messageHandler != null && messageHandler.isRunning();
    }
}
