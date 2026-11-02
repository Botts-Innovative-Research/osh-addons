/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************************************************************/
package com.botts.impl.service.fileserver;

import java.io.File;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import org.eclipse.jetty.ee8.nested.ServletConstraint;
import org.eclipse.jetty.ee8.security.ConstraintMapping;
import org.eclipse.jetty.ee8.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee8.security.SecurityHandler;
import org.eclipse.jetty.ee8.servlet.DefaultServlet;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import org.sensorhub.impl.service.HttpServer;
import org.vast.util.Asserts;


/**
 * Serves static files from a folder at a configurable URL.<br/>
 * With Jetty 12, the files are served by a dedicated (ee8) servlet context using
 * Jetty's DefaultServlet. OSH permissions are checked by {@link FileHandler} (a servlet
 * filter) and, if required, authentication reuses the main HTTP server authenticator
 * and login service.
 */
public class FileServer extends AbstractHttpServiceModule<FileServerConfig> {

    FileServerSecurity security;
    ServletContextHandler fileServerContext;
    Handler fileServerHandler; // core handler registered with the Jetty server
    Handler.Collection serverHandlers;

    @Override
    public void setConfiguration(FileServerConfig config) {
        super.setConfiguration(config);
        this.securityHandler = this.security = new FileServerSecurity(this, config.securityConfig.enableAccessControl);
    }

    @Override
    protected void doStart() throws SensorHubException {
        super.doStart();

        HttpServer server = (HttpServer) httpServer;

        Asserts.checkNotNull(config.staticDocsRootUrl);
        Asserts.checkNotNull(config.staticDocsRootDir);

        // Jetty 12 refuses to start a context whose base folder does not exist
        if (!new File(config.staticDocsRootDir).isDirectory()) {
            reportError("Static content folder not found: " + config.staticDocsRootDir, new IllegalStateException());
            return;
        }

        if (!(server.getJettyServer().getHandler() instanceof Handler.Collection)) {
            reportError("Cannot register file server: unexpected Jetty server handler", new IllegalStateException());
            return;
        }
        serverHandlers = (Handler.Collection) server.getJettyServer().getHandler();

        if (fileServerHandler != null && serverHandlers.getHandlers().contains(fileServerHandler)) {
            reportError("File server handler already registered to Jetty server", new IllegalStateException());
            return;
        }

        // servlet context serving the static files
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath(config.staticDocsRootUrl);
        ctx.setClassLoader(null); // same as core HttpServer (keep caller's context classloader)
        ctx.setBaseResourceAsString(config.staticDocsRootDir);

        // reuse authentication from the main HTTP server if required
        SecurityHandler coreSecurity = server.getServletHandler() != null ? server.getServletHandler().getSecurityHandler() : null;
        if (config.securityConfig.requireAuth && coreSecurity != null)
            ctx.setSecurityHandler(createSecurityHandler(coreSecurity));

        // OSH permission check
        ctx.addFilter(new FilterHolder(new FileHandler(security, getLogger())), "/*", EnumSet.of(DispatcherType.REQUEST));

        // static content
        ServletHolder files = new ServletHolder("fileserver", DefaultServlet.class);
        files.setInitParameter("dirAllowed", "false");
        files.setInitParameter("etags", "true");
        ctx.addServlet(files, "/");

        fileServerContext = ctx;
        fileServerHandler = ctx.get();
        fileServerHandler.setServer(server.getJettyServer());

        try {
            fileServerHandler.start();
        } catch (Exception e) {
            reportError("Error starting file server", e);
            fileServerHandler = null;
            fileServerContext = null;
            return;
        }

        serverHandlers.addHandler(fileServerHandler);

        getLogger().info("Static resources being served at {} from {}", config.staticDocsRootUrl, config.staticDocsRootDir);
    }

    @Override
    protected void doStop() throws SensorHubException {
        super.doStop();
        if (fileServerHandler != null) {
            if (serverHandlers != null)
                serverHandlers.removeHandler(fileServerHandler);
            try {
                fileServerHandler.stop();
            } catch (Exception e) {
                getLogger().error("Error stopping file server", e);
            }
        }
        fileServerHandler = null;
        fileServerContext = null;
    }

    @Override
    public void cleanup() throws SensorHubException {
        super.cleanup();
        if (securityHandler != null)
            securityHandler.unregister();
    }

    private ConstraintSecurityHandler createSecurityHandler(SecurityHandler coreSecurity) {
        ConstraintSecurityHandler fileSecurityHandler = new ConstraintSecurityHandler();
        fileSecurityHandler.setAuthenticator(coreSecurity.getAuthenticator());
        fileSecurityHandler.setLoginService(coreSecurity.getLoginService());

        ServletConstraint constraint = new ServletConstraint();
        constraint.setRoles(new String[]{ServletConstraint.ANY_AUTH});
        constraint.setAuthenticate(true);
        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(constraint);
        cm.setPathSpec("/*");
        cm.setMethodOmissions(new String[]{"OPTIONS"}); // disable auth on OPTIONS requests (needed for CORS)
        fileSecurityHandler.addConstraintMapping(cm);

        return fileSecurityHandler;
    }

}
