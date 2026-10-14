/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.hivemq;

import java.io.IOException;
import java.net.InetSocketAddress;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.slf4j.Logger;
import org.vast.util.Asserts;


/**
 * <p>
 * Simple servlet used to expose an "MQTT over websocket" proxy at the desired
 * endpoint.
 * </p>
 *
 * @author Alex Robin
 * @since Jun 9, 2022
 */
@SuppressWarnings("serial")
public class WebSocketProxyServlet extends HttpServlet
{
    final static String MQTT_SUB_PROTOCOL = "mqtt";
    
    final InetSocketAddress mqttServerAddress;
    final Logger log;
    transient JettyWebSocketServerContainer wsContainer;


    public WebSocketProxyServlet(InetSocketAddress mqttServerAddress, Logger logger)
    {
        this.log = Asserts.checkNotNull(logger, Logger.class);
        this.mqttServerAddress = Asserts.checkNotNull(mqttServerAddress, InetSocketAddress.class);
    }
    
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);

        // get websocket container (lifecycle is managed by Jetty, no need to start/stop it)
        wsContainer = JettyWebSocketServerContainer.getContainer(getServletContext());
        if (wsContainer == null)
        {
            throw new ServletException("WebSocket container not available. " +
                "JettyWebSocketServletContainerInitializer.configure() must be called " +
                "on the ServletContextHandler before the server is started");
        }
    }


    @Override
    public void destroy()
    {
        // nothing to stop: websocket container is stopped together with the servlet context
        wsContainer = null;
        super.destroy();
    }


    protected boolean isWebSocketUpgradeRequest(HttpServletRequest req)
    {
        return "GET".equalsIgnoreCase(req.getMethod()) &&
               "websocket".equalsIgnoreCase(req.getHeader("Upgrade"));
    }


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // check if we have an upgrade request for websockets
        if (isWebSocketUpgradeRequest(req))
        {
            boolean upgraded = wsContainer.upgrade((upgradeReq, upgradeResp) -> {
                try
                {
                    if (!upgradeReq.getSubProtocols().contains(MQTT_SUB_PROTOCOL))
                    {
                        upgradeResp.sendError(405, "Only 'mqtt' sub-protocol is supported by this endpoint");
                        return null;
                    }
                    
                    upgradeResp.setAcceptedSubProtocol(MQTT_SUB_PROTOCOL);
                    return new WebSocketProxy(mqttServerAddress, log);
                }
                catch (IOException e)
                {
                    log.error("Error creating websocket", e);
                    return null;
                }
            }, req, resp);
            
            if (!upgraded && !resp.isCommitted())
                resp.sendError(400, "WebSocket upgrade failed");
            return;
        }
        
        resp.sendError(405, "Only websocket requests are accepted on this endpoint");
    }
}
