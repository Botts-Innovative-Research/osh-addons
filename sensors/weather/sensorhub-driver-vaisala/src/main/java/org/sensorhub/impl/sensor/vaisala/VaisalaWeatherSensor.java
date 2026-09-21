package org.sensorhub.impl.sensor.vaisala;

import java.io.*;
import net.opengis.sensorml.v20.IdentifierList;
import net.opengis.sensorml.v20.Term;

import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.vaisala.outputs.*;
import org.vast.sensorML.SMLFactory;
import org.vast.swe.SWEHelper;


public class VaisalaWeatherSensor extends AbstractSensorModule<VaisalaWeatherConfig> {
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

        serialNumber = config.serialNumber;
        if (serialNumber == null)
        {
            int endIndex = Math.min(config.id.length(), 8);
            serialNumber = config.id.substring(0, endIndex);
        }

        // Generate identifiers
        this.uniqueID = "urn:osh:georobotix:sensor:vaisala:" + serialNumber;
        this.xmlID = "VAISALA_" + serialNumber.toUpperCase();

        // Add outputs
        createOutputs();

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

    }

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


    private void getMeasurement()
    {	
    	String inputLine = null;
    	try {
    		
    		/******** Get Input from Serial and Split String ************/
    		//System.out.println(CRLF + "Got Measurement!");
            inputLine = dataIn.readLine();
            //System.out.println("Message: " + inputLine);
            inputTemp = inputLine.split(",");
            //System.out.println("Message Type: " + inputTemp[0].substring(1));
            
            // send message to appropriate output class to be processed
            switch (inputTemp[0].substring(1))
            {
            case "R0":
            	compOut.ParseAndSendCompMeasurement(inputLine);
            	break;
            case "R1":
            	windOut.ParseAndSendWindMeasurement(inputLine);
            	break;
            case "R2":
            	ptuOut.ParseAndSendPTUMeasurement(inputLine);
            	break;
            case "R3":
            	precipOut.ParseAndSendPrecipMeasurement(inputLine);
            	break;
            case "R5":
            	supOut.ParseAndSendSupMeasurement(inputLine);
            	break;
            default:
            	break;
            }
            /***********************************************************/
		}
    	catch (Exception e)
    	{
			e.printStackTrace();
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
        } catch (IOException | SensorHubException | RuntimeException e) {
            doStop();
            throw new SensorHubException("Error starting Vaisala after command " + lastCommand, e);
        }
    }

    }

    @Override
    protected void afterStart() {
        // Begin heartbeat check
        started = true;
    }

    @Override
    protected void doStop() {
        logger.info("Stopping Vaisala Weather {} ...", getUniqueIdentifier());

        started = false;

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
        logger.info("VaisalaWeather {} stopped", getUniqueIdentifier());
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isConnected()
                && messageHandler != null && messageHandler.isRunning();
    }
}
