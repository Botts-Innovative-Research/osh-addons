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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.opengis.sensorml.v20.IdentifierList;
import net.opengis.sensorml.v20.Term;
import org.sensorhub.impl.comm.RobustHTTPConnection;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.api.common.SensorHubException;
import org.vast.sensorML.SMLFactory;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Implementation of sensor interface for generic Esprit Cameras using IP
 * protocol
 * </p>
 * 
 * @author Mike Botts
 * @since October 30, 2014
 */
public class EspritCameraDriver extends AbstractSensorModule <EspritCameraConfig> {

    public static final String DEFAULT_RTSP_VIDEO_PATH = "/Esprit-media/media.amp?videocodec=h264";

    RobustConnection connection;
    EspritVideoStream videoStream;
    EspritPtzOutput ptzPosOutput;
    EspritVideoControl videoControlInterface;
    EspritPtzControl ptzControlInterface;

    String hostUrl;
    String serialNumber;
    String modelNumber;
    String longName;
    String shortName;

    public EspritCameraDriver() {

    }


    @Override
    public void setConfiguration(final EspritCameraConfig config) {
        super.setConfiguration(config);

        // compute full host URL
//        hostUrl = "http://" + config.http.remoteHost + ":" + config.http.remotePort + VAPIX_API_BASE_URL;
    }


    @Override
    protected void doInit() throws SensorHubException {
        // reset internal state in case init() was already called
        super.doInit();
        videoStream = null;
        ptzPosOutput = null;
        ptzControlInterface = null;
        //ptzSupported = false;

        // add PTZ output
        ptzPosOutput = new EspritPtzOutput(this);
        addOutput(ptzPosOutput, false);
        ptzPosOutput.init();

        // add PTZ controller
        ptzControlInterface = new EspritPtzControl(this);
        addControlInput(ptzControlInterface);
        ptzControlInterface.init();

        // create connection handler
        connection = new RobustHTTPConnection(this, config.connection, "Esprit Camera") {
            public boolean tryConnect() throws IOException {
                // check we can reach the HTTP server
                // and access the param URL
                if (ptzControlInterface != null)
                    return ptzControlInterface.ptzMove(PtzDirection.STOP);
                else {
                    logger.error("PTZ control interface not initialized");
                    return false;
                }
            }
        };

        // TODO we could check if basic metadata is in cache, in which case
        // it's not necessary to connect to camera at this point

        // wait for valid connection to camera
        connection.waitForConnection();

        // generate identifiers
        generateUniqueID("urn:Esprit:cam:", config.uidExtension.isBlank() ? serialNumber : serialNumber.trim() + ":" + config.uidExtension);
        generateXmlID("Esprit_CAM_", config.uidExtension.isBlank() ? serialNumber : serialNumber.trim() + "_" + config.uidExtension);

        // create I/O objects
        String videoOutName = "video";
        int videoOutNum = 1;

        videoStream = new EspritVideoStream(this, "video", buildRtspUrl(), "-timeout 3000000");
        videoStream.init();

//        // add MJPEG video output (HTTP via FFmpeg)
//        if (mjpegSupported && config.enableMJPEG) {
//            String outputName = videoOutName + videoOutNum++;
//            videoStream = new EspritVideoStream(this, outputName, buildMjpegUrl(), "-timeout 3000000");
//            videoStream.init();
//            addOutput(videoStream.getOutput(), false);
//        }
//
//        // add H.264 video output (RTSP via FFmpeg)
//        if (h264Supported && config.enableH264) {
//            String outputName = videoOutName + videoOutNum++;
//            h264VideoStream = new EspritVideoStream(this, outputName, buildRtspUrl("h264"), "-rtsp_transport tcp -stimeout 3000000");
//            h264VideoStream.init();
//            addOutput(h264VideoStream.getOutput(), false);
//        }
//
//        // add H.265 video output (RTSP via FFmpeg)
//        if (h265Supported && config.enableH265) {
//            String outputName = videoOutName + videoOutNum++;
//            h265VideoStream = new EspritVideoStream(this, outputName, buildRtspUrl("h265"), "-rtsp_transport tcp -stimeout 3000000");
//            h265VideoStream.init();
//            addOutput(h265VideoStream.getOutput(), false);
//        }
//
//        if (ptzSupported) {
//
//        }
    }


    @Override
    protected void doStart() throws SensorHubException {
        // wait for valid connection to camera
        connection.waitForConnection();

        // start video outputs
        if (videoStream != null)
            videoStream.start();

//        if (h264VideoStream != null)
//            h264VideoStream.start();
//
//        if (h265VideoStream != null)
//            h265VideoStream.start();
//
//        // if PTZ supported
//        if (ptzSupported) {
//            ptzPosOutput.start();
//            ptzControlInterface.start();
//        }
    }


    @Override
    protected void updateSensorDescription() {
        synchronized(sensorDescLock) {
            // parent class reads SensorML from config if provided
            // and then sets unique ID, outputs and control inputs
            super.updateSensorDescription();

            SMLFactory smlFac = new SMLFactory();

            if (!sensorDescription.isSetDescription())
                sensorDescription.setDescription("Esprit Video Camera");

            IdentifierList identifierList = smlFac.newIdentifierList();
            sensorDescription.addIdentification(identifierList);

            Term term;
            term = smlFac.newTerm();
            term.setDefinition(SWEHelper.getPropertyUri("Manufacturer"));
            term.setLabel("Manufacturer Name");
            term.setValue("Esprit");
            identifierList.addIdentifier(term);

            if (modelNumber != null) {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("ModelNumber"));
                term.setLabel("Model Number");
                term.setValue(modelNumber);
                identifierList.addIdentifier(term);
            }

            if (serialNumber != null) {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("SerialNumber"));
                term.setLabel("Serial Number");
                term.setValue(serialNumber);
                identifierList.addIdentifier(term);
            }

            if (longName != null) {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("LongName"));
                term.setLabel("Long Name");
                term.setValue(longName);
                identifierList.addIdentifier(term);
            }

            if (shortName != null) {
                term = smlFac.newTerm();
                term.setDefinition(SWEHelper.getPropertyUri("ShortName"));
                term.setLabel("Short Name");
                term.setValue(shortName);
                identifierList.addIdentifier(term);
            }
        }
    }


    @Override
    public boolean isConnected() {
        if (connection == null)
            return false;

        return connection.isConnected();
    }


    protected void setAuth() {
        ClientAuth.getInstance().setUser(config.http.user);
        if (config.http.password != null)
            ClientAuth.getInstance().setPassword(config.http.password.toCharArray());
    }


    @Override
    protected void doStop() {
        if (connection != null)
            connection.cancel();

        if (ptzPosOutput != null)
            ptzPosOutput.stop();

        if (ptzControlInterface != null)
            ptzControlInterface.stop();

        if (videoStream != null)
            videoStream.stop();
//
//        if (h264VideoStream != null)
//            h264VideoStream.stop();
//
//        if (h265VideoStream != null)
//            h265VideoStream.stop();

        if (videoControlInterface != null)
            videoControlInterface.stop();
    }


    @Override
    public void cleanup() {}


    protected String getHostUrl() {
        setAuth();
        return hostUrl;
    }


    /**
     * Builds the FFmpeg-compatible RTSP URL, inlining credentials
     * if the user/password are configured (FFmpeg cannot consult
     * {@link ClientAuth} the way the Java {@code URL} opener can).
     */
    protected String buildRtspUrl() {
        String userInfo = buildUserInfo(config.http.user, config.http.password);
        return "rtsp://" + userInfo + config.http.remoteHost + ":" + config.http.remotePort + "nvif";
    }


    /**
     * Builds the FFmpeg-compatible RTSP URL for the requested codec. The base
     * path is taken from {@code config.rtsp.videoPath}; the {@code videocodec}
     * query parameter is rewritten to the requested codec so the same
     * configuration field can serve both H.264 and H.265 streams.
     *
     * @param codec one of {@code "h264"}, {@code "h265"}, or {@code "jpeg"}.
     */
    /*
    protected String buildRtspUrl(String codec) {
        String userInfo = buildUserInfo(config.rtsp.user, config.rtsp.password);
        String path = config.rtsp.videoPath == null ? DEFAULT_RTSP_VIDEO_PATH : config.rtsp.videoPath;
        if (path.toLowerCase().contains("videocodec=")) {
            path = path.replaceAll("(?i)videocodec=[a-z0-9]+", "videocodec=" + codec);
        } else {
            path = path + (path.contains("?") ? "&" : "?") + "videocodec=" + codec;
        }
        if (!path.startsWith("/"))
            path = "/" + path;
        return "rtsp://" + userInfo + config.rtsp.remoteHost + ":" + config.rtsp.remotePort + path;
    }

     */


    /**
     * Returns a URL userinfo segment ({@code "user:pass@"}) with both components
     * URL-encoded, or the empty string if no credentials are configured.
     */
    private static String buildUserInfo(String user, String password) {
        if (user == null || user.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append(URLEncoder.encode(user, StandardCharsets.UTF_8));
        if (password != null && !password.isEmpty())
            sb.append(':').append(URLEncoder.encode(password, StandardCharsets.UTF_8));
        sb.append('@');
        return sb.toString();
    }
}