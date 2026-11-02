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

import org.sensorhub.api.security.ISecurityManager;
import org.slf4j.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

/**
 * Servlet filter applying OSH permissions to file server requests.<br/>
 * (was a Jetty ResourceHandler subclass before Jetty 12; files are now served
 * by Jetty's DefaultServlet, see {@link FileServer})
 */
public class FileHandler implements Filter {

    static final String LOG_REQUEST_MSG = "{} {}{} (from ip={}, user={})";
    static final String INTERNAL_ERROR_MSG = "Internal server error";
    static final String INTERNAL_ERROR_LOG_MSG = INTERNAL_ERROR_MSG + " while processing request " + LOG_REQUEST_MSG;
    static final String ACCESS_DENIED_ERROR_MSG = "Permission denied";

    FileServerSecurity security;
    Logger log;

    public FileHandler(FileServerSecurity security, Logger log) {
        this.security = security;
        this.log = log;
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        try {
            // Set current user
            setCurrentUser(request);

            // Check if current user has permissions
            if (!security.hasPermission(security.get)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "You do not have access to this resource");
                return;
            }

            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            logError(request, e);
        } finally {
            clearCurrentUser();
        }
    }

    private void setCurrentUser(HttpServletRequest req) {
        String userID = ISecurityManager.ANONYMOUS_USER;
        if (req.getRemoteUser() != null)
            userID = req.getRemoteUser();
        security.setCurrentUser(userID);
    }

    private void clearCurrentUser() {
        security.clearCurrentUser();
    }

    private void sendError(int code, String msg, HttpServletRequest req, HttpServletResponse resp) {
        try {
            var accept = req.getHeader("Accept");

            if (accept == null || accept.contains("json")) {
                resp.setStatus(code);
                if (msg != null) {
                    var json =
                            "{\n" +
                                    "  \"status\": " + code + ",\n" +
                                    "  \"message\": \"" + msg.replace("\"", "\\\"") + "\"\n" +
                                    "}";
                    resp.getOutputStream().write(json.getBytes());
                }
            } else
                resp.sendError(code, msg);
        } catch (IOException e) {
            log.error("Could not send error response", e);
        }
    }

    protected void handleAuthException(HttpServletRequest req, HttpServletResponse resp, SecurityException e)
    {
        try
        {
            log.debug("Not authorized: {}", e.getMessage());

            if (req != null && resp != null)
            {
                if (req.getRemoteUser() == null)
                    req.authenticate(resp);
                else
                    sendError(SC_FORBIDDEN, ACCESS_DENIED_ERROR_MSG, req, resp);
            }
        }
        catch (Exception e1)
        {
            log.error("Could not send authentication request", e1);
        }
    }

    protected void logRequest(HttpServletRequest req)
    {
        if (log.isInfoEnabled())
            logRequestInfo(req, null);
    }


    protected void logError(HttpServletRequest req, Throwable e)
    {
        if (log.isErrorEnabled())
            logRequestInfo(req, e);
    }

    protected void logRequestInfo(HttpServletRequest req, Throwable error)
    {
        String method = req.getMethod();
        String url = req.getRequestURI();
        String ip = req.getRemoteAddr();
        String user = req.getRemoteUser() != null ? req.getRemoteUser() : "anonymous";

        // if proxy header present, use source ip instead of proxy ip
        String proxyHeader = req.getHeader("X-Forwarded-For");
        if (proxyHeader != null)
        {
            String[] ips = proxyHeader.split(",");
            if (ips.length >= 1)
                ip = ips[0];
        }

        // detect websocket upgrade
        if ("websocket".equalsIgnoreCase(req.getHeader("Upgrade")))
            method += "/Websocket";

        // append decoded request if any
        String query = "";
        if (req.getQueryString() != null)
            query = "?" + req.getQueryString();

        if (error != null)
            log.error(INTERNAL_ERROR_LOG_MSG, method, url, query, ip, user, error);
        else
            log.info(LOG_REQUEST_MSG, method, url, query, ip, user);
    }

}
