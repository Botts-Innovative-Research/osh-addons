/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
Developer are Copyright (C) 2014 the Initial Developer. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.esprit;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collection;
import java.net.MalformedURLException;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.command.CommandException;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.impl.sensor.videocam.VideoCamHelper;
import org.sensorhub.impl.sensor.videocam.ptz.PtzPresetsHandler;
import org.vast.data.DataChoiceImpl;


/**
 * <p>
 * Implementation of sensor interface for generic Esprit Cameras using IP
 * protocol. This particular class provides control of the Pan-Tilt-Zoom
 * (PTZ) capabilities.
 * </p>
 * 
 * @author Mike Botts
 * @since October 30, 2014
 */
public class EspritPtzControl extends AbstractSensorControl<EspritCameraDriver>
{
	DataChoice commandData;

    public static final String HTTP_API_PTZ_BASE_URL = "/httpapi/SendPTZ?action=sendptz&PTZ_CHANNEL=1&";

    // define and set default values
    double minPan = -180.0;
    double maxPan = 180.0;
    double minTilt = -180.0;
    double maxTilt = 0.0;
    double minZoom = 1.0;
    double maxZoom = 9999;

    PtzPresetsHandler presetsHandler;
    URL httpApiUrl = null;

    private static class PtzVec {
        int x, y;

        PtzVec(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public void add(int x, int y) {
            this.x += x;
            this.y += y;
        }

        public PtzDirection getDirection() {
            if (Math.abs(x) > Math.abs(y)) {
                if (x > 0) {
                    return PtzDirection.RIGHT;
                } else {
                    return PtzDirection.LEFT;
                }
            } else {
                if (y > 0) {
                    return PtzDirection.UP;
                } else if (y < 0) {
                    return PtzDirection.DOWN;
                } else {
                    return PtzDirection.STOP;
                }
            }
        }

        // Doing it like this since we can't move diagonally
        public int getMagnitude() {
            return Math.max(Math.abs(x), Math.abs(y));
        }
    }

    protected EspritPtzControl(EspritCameraDriver driver)
    {
        super("ptzControl", driver);

//        try {
//            httpApiUrl = new URL(parentSensor.getHostUrl() + driver.VAPIX_QUERY_PARAMS_LIST_GROUP_PTZ);
//
//        } catch (MalformedURLException e) {
//
//            e.printStackTrace();
//        }
    }
    
    
    protected void init()
    {
        EspritCameraConfig config = parentSensor.getConfiguration();
        presetsHandler = new PtzPresetsHandler(config.ptz);

        /*
        // get PTZ limits
        try
        {
            InputStream is = httpApiUrl.openStream();
            BufferedReader bReader = new BufferedReader(new InputStreamReader(is));

            // get limit values from IP stream
            String line;
            while ((line = bReader.readLine()) != null)
            {
                // parse response
                String[] tokens = line.split("=");

                if (tokens[0].trim().equalsIgnoreCase("root.PTZ.Limit.L1.MinPan"))
                    minPan = Double.parseDouble(tokens[1]);
                else if (tokens[0].trim().equalsIgnoreCase("root.PTZ.Limit.L1.MaxPan"))
                    maxPan = Double.parseDouble(tokens[1]);
                else if (tokens[0].trim().equalsIgnoreCase("root.PTZ.Limit.L1.MinTilt"))
                    minTilt = Double.parseDouble(tokens[1]);
                else if (tokens[0].trim().equalsIgnoreCase("root.PTZ.Limit.L1.MaxTilt"))
                    maxTilt = Double.parseDouble(tokens[1]);
                else if (tokens[0].trim().equalsIgnoreCase("root.PTZ.Limit.L1.MaxZoom"))
                    maxZoom = Double.parseDouble(tokens[1]);
            }
	    }
	    catch (Exception e)
	    {
	        e.printStackTrace();
	    }
         */

        // build SWE data structure for the tasking parameters
        VideoCamHelper videoHelper = new VideoCamHelper();
        Collection<String> presetList = presetsHandler.getPresetNames();
        commandData = videoHelper.getPtzTaskParameters(getName(), minPan, maxPan, minTilt, maxTilt, minZoom, maxZoom, presetList);      
    }
    
    
    protected void start()
    {        
    }
    

    @Override
    protected boolean execCommand(DataBlock command) throws CommandException
    {
    	// associate command data to msg structure definition
        DataChoice commandMsg = (DataChoice) commandData.copy();
        commandMsg.setData(command);
              
        DataComponent component = ((DataChoiceImpl) commandMsg).getSelectedItem();
        String itemID = component.getName();
        DataBlock data = component.getData();
        String itemValue = data.getStringValue();


        switch (itemID) {
            // TODO PRESET FUNCTIONALITY NOT YET IMPLEMENTED
            case "t" -> {
                int preset = Integer.parseInt(itemValue);
                return ptzPresetSet(preset);
            }
            case VideoCamHelper.TASKING_PTZPRESET -> {
                int preset = Integer.parseInt(itemValue);
                return ptzPresetGoto(preset);
            }
            case "n/a" -> {
                boolean auto = Boolean.parseBoolean(itemValue);
                return ptzFocusAuto(auto);
            }
            case "temp" -> {
                //PtzDirection direction = PtzDirection.valueOf(itemValue);
                return false;
                //return ptzMove(direction);
            }
            default -> {
                throw new CommandException("Invalid PTZ command: " + itemID);
            }
        }
          
        // NOTE: you can use validate() method in DataComponent
        // component.validateData(errorList);  // give it a list so it can store the errors
        // if (errorList != empty)  //then you have an error
        /*
        try
        {
            // set parameter value on camera 
            // NOTE: except for "presets", the item IDs are labeled the same as the Esprit parameters so just use those in the command
        	if (itemID.equals(VideoCamHelper.TASKING_PTZPRESET))
        	{
        	    PtzPreset preset = presetsHandler.getPreset(data.getStringValue());
        	    
                // pan + tilt + zoom (supported since v2 at least)
        	    httpApiUrl = new URL(parentSensor.getHostUrl() + "/com/ptz.cgi?pan=" + preset.pan
        	    		+ "&tilt=" + preset.tilt + "&zoom=" + preset.zoom);
                InputStream is = httpApiUrl.openStream();
                is.close();
       	    
        	}
        	
        	// Act on full PTZ Position
        	else if (itemID.equalsIgnoreCase(VideoCamHelper.TASKING_PTZ_POS))
        	{

        	    httpApiUrl = new URL(parentSensor.getHostUrl() + "/com/ptz.cgi?pan=" + data.getStringValue(0)
        	    		+ "&tilt=" + data.getStringValue(1) + "&zoom=" + data.getStringValue(2));
                InputStream is = httpApiUrl.openStream();
                is.close();

        	}
     	
        	else
        	{
        		String cmd = " ";
        		if (itemID.equals(VideoCamHelper.TASKING_PAN)) 
        			cmd = "pan";
        		else if (itemID.equals(VideoCamHelper.TASKING_RPAN)) 
        			cmd = "rpan";
        		else if (itemID.equals(VideoCamHelper.TASKING_TILT)) 
        			cmd = "tilt";
        		else if (itemID.equals(VideoCamHelper.TASKING_RTILT)) 
        			cmd = "rtilt";
        		else if (itemID.equals(VideoCamHelper.TASKING_ZOOM)) 
        			cmd = "zoom";
        		else if (itemID.equals(VideoCamHelper.TASKING_RZOOM)) 
        			cmd = "rzoom";
        			      			
                httpApiUrl = new URL(parentSensor.getHostUrl() + "/com/ptz.cgi?" + cmd + "=" + itemValue);
                InputStream is = httpApiUrl.openStream();
                is.close();       		
        	}
        	
	    }
	    catch (Exception e)
	    {	    	
	        throw new CommandException("Error connecting to Esprit PTZ control", e);
	    }

         */

    }

    public boolean ptzPresetSet(int preset) {
        return sendPtzCommand(PtzControl.PTZ_PRESETSET.name(), null, preset, null);
    }

    public boolean ptzPresetGoto(int preset) {
        return sendPtzCommand(PtzControl.PTZ_PRESETGOTO.name(), null, preset, null);
    }

    public boolean ptzFocusAuto(boolean auto) {
        return sendPtzCommand(PtzControl.PTZ_FOCUSAUTO.name(), null, auto ? 1 : 0, null);
    }

    public boolean ptzMove(PtzDirection direction) {
        return sendPtzCommand(PtzControl.PTZ_MOVE.name(), direction.getApiString(), null, null);
    }

    public boolean ptzMove(PtzDirection direction, int speed) {
        return sendPtzCommand(PtzControl.PTZ_MOVE.name(), direction.getApiString(), speed, null);
    }

    public boolean ptzMove(PtzDirection direction, int speed, int duration) {
        return sendPtzCommand(PtzControl.PTZ_MOVE.name(), direction.getApiString(), speed, duration);
    }

    public boolean sendPtzCommand(String command, String string, Integer integer, Integer timeout) {
        Throwable error = validateCommandParams(command, string, integer, timeout);
        if (error != null) {
            log.error("Error validating PTZ command", error);
            return false;
        }

        InputStream is = null;
        boolean status = false;
        StringBuilder sb = new StringBuilder(HTTP_API_PTZ_BASE_URL).append(command).append("=");

        if (string != null) {
            sb.append(string);
        }

        if (integer != null) {
            if (string != null) {
                sb.append(",");
            }
            sb.append(integer);
        }

        if (timeout != null) {
            sb.append("&").append(PtzControl.PTZ_TIMEOUT).append("=").append(timeout);
        }

        try {
            httpApiUrl = new URL(parentSensor.getHostUrl() + HTTP_API_PTZ_BASE_URL + string);
            is = httpApiUrl.openStream();
            status = true;
        } catch (Exception e) {
            log.warn("Error creating PTZ URL", e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    log.warn("Error closing stream", e);
                }
            }
        }

        return status;
    }

    private static IllegalArgumentException validateCommandParams(String command, String string, Integer integer, Integer timeout) {
        // Null command is not allowed
        if (command == null) {
            return new IllegalArgumentException(("PTZ command cannot be null"));
        }

        if (!command.equals(PtzControl.PTZ_MOVE.name())) {
            // Only PTZ_MOVE supports timeout
            if (timeout != null) {
                return new IllegalArgumentException("PTZ " + command + " does not support timeout");
            }
            // All non-PTZ_MOVE commands require an integer parameter
            if (integer == null) {
                return new IllegalArgumentException("PTZ " + command + " requires an integer parameter");
            }
        } else {
            // PTZ_MOVE requires a string parameter for direction
            if (string == null) {
                return new IllegalArgumentException("PTZ Move requires a string parameter");
            }
        }
        return null;
    }
    
    
    @Override
    public DataComponent getCommandDescription()
    {    
        return commandData;
    }


	public void stop()
	{

	}

}
