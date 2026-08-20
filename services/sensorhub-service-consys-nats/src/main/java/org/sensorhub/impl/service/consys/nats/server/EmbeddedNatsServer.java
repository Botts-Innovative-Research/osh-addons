/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.EmbeddedServerConfig;
import org.slf4j.Logger;


/**
 * <p>
 * Manages a locally launched NATS server process.
 * </p><p>
 * NATS server is distributed as a native Go binary; there is no pure-Java NATS
 * server implementation. "Embedded" therefore means this class launches and
 * supervises an external {@code nats-server} process so the OSH node can act as
 * a self-contained NATS deployment, then the module connects to it as a normal
 * client.
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class EmbeddedNatsServer
{
    final EmbeddedServerConfig config;
    final Logger logger;
    Process process;


    public EmbeddedNatsServer(EmbeddedServerConfig config, Logger logger)
    {
        this.config = config;
        this.logger = logger;
    }


    public String getServerUrl()
    {
        return "nats://" + config.host + ":" + config.port;
    }


    public void start() throws SensorHubException
    {
        var exec = (config.executablePath != null && !config.executablePath.isBlank()) ?
            config.executablePath : "nats-server";

        List<String> cmd = new ArrayList<>();
        cmd.add(exec);
        cmd.add("--addr");
        cmd.add(config.host);
        cmd.add("--port");
        cmd.add(Integer.toString(config.port));
        if (config.jetStream)
            cmd.add("--jetstream");

        logger.info("Starting embedded NATS server: {}", String.join(" ", cmd));

        try
        {
            process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        }
        catch (IOException e)
        {
            throw new SensorHubException("Cannot launch nats-server executable '" + exec + "'. "
                + "Make sure NATS server is installed and on the PATH, or set an explicit "
                + "executable path in the module configuration.", e);
        }

        startOutputPump();
        waitUntilReady();
        logger.info("Embedded NATS server ready at {}", getServerUrl());
    }


    private void startOutputPump()
    {
        var t = new Thread(() -> {
            try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                    logger.debug("[nats-server] {}", line);
            }
            catch (IOException e)
            {
                // process output stream closed; nothing to do
            }
        }, "nats-server-output");
        t.setDaemon(true);
        t.start();
    }


    private void waitUntilReady() throws SensorHubException
    {
        long deadline = System.currentTimeMillis() + config.startupTimeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (process != null && !process.isAlive())
            {
                throw new SensorHubException("nats-server process exited during startup (exit code "
                    + process.exitValue() + "). Check the log for details.");
            }

            try (var socket = new Socket())
            {
                socket.connect(new InetSocketAddress(config.host, config.port), 500);
                return; // a successful TCP connect means the server is accepting clients
            }
            catch (IOException e)
            {
                try
                {
                    Thread.sleep(200);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        stop();
        throw new SensorHubException("Embedded NATS server did not become ready within "
            + config.startupTimeoutMs + " ms");
    }


    public void stop()
    {
        if (process != null)
        {
            logger.info("Stopping embedded NATS server");
            process.destroy();
            try
            {
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    process.destroyForcibly();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
    }
}
