/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.DataStreamingMode;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.OnDemandConfig;


/**
 * <p>
 * Init-time validation for {@link ConSysApiNatsServiceConfig}. Pure and
 * side-effect free (apart from {@link #normalize}): returns every error and
 * warning in one pass so a user fixes a broken config in one round-trip
 * instead of one module restart per mistake.
 * </p><p>
 * Errors are conditions where the module cannot work as configured (the
 * module must fail init). Warnings are conditions where it works but part of
 * the configuration is inert or probably not what the user meant (e.g.
 * settings for the non-selected streaming mode, regex syntax in a glob).
 * </p>
 */
class ConSysApiNatsConfigValidator
{
    /** NATS URL schemes accepted by the jnats client. */
    static final Set<String> NATS_URL_SCHEMES = Set.of("nats", "tls", "opentls", "ws", "wss");

    /** A single NATS subject token: no '.', '*', '>' or whitespace. */
    static final Pattern SUBJECT_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    /** Characters that suggest a glob was written as a regex ('*' is the only supported wildcard). */
    static final Pattern REGEX_SUSPECT = Pattern.compile("[\\\\\\[\\](){}^$+?|]|\\.\\*");


    static class Result
    {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }


    /**
     * Replace null nested blocks and lists with default instances so the rest
     * of the module never needs null guards (Gson leaves omitted blocks null).
     */
    static void normalize(ConSysApiNatsServiceConfig cfg)
    {
        if (cfg.server == null)
            cfg.server = new ConSysApiNatsServiceConfig.NatsServerConfig();
        if (cfg.proactive == null)
            cfg.proactive = new ConSysApiNatsServiceConfig.ProactiveConfig();
        if (cfg.proactive.dataFormats == null)
            cfg.proactive.dataFormats = new ArrayList<>();
        if (cfg.proactive.excludeSystemUids == null)
            cfg.proactive.excludeSystemUids = new ArrayList<>();
        if (cfg.proactive.commandRelay == null)
            cfg.proactive.commandRelay = new ConSysApiNatsServiceConfig.CommandRelayConfig();
        if (cfg.proactive.commandRelay.onlySystemUids == null)
            cfg.proactive.commandRelay.onlySystemUids = new ArrayList<>();
        if (cfg.onDemand == null)
            cfg.onDemand = new OnDemandConfig();
        if (cfg.jetStream == null)
            cfg.jetStream = new ConSysApiNatsServiceConfig.JetStreamConfig();
        if (cfg.dataStreamingMode == null)
            cfg.dataStreamingMode = DataStreamingMode.PROACTIVE;
    }


    static Result validate(ConSysApiNatsServiceConfig cfg)
    {
        normalize(cfg);
        var result = new Result();

        // node id becomes a subject prefix — must be a single valid token
        if (isBlank(cfg.nodeId) || !SUBJECT_TOKEN.matcher(cfg.nodeId.trim()).matches())
            result.errors.add("nodeId must be a single NATS subject token "
                + "(letters, digits, '_', '-'; no '.', '*', '>'): got '" + cfg.nodeId + "'");

        checkServer(cfg.server, result);

        if (!isBlank(cfg.originNodeUuid))
        {
            try
            {
                UUID.fromString(cfg.originNodeUuid.trim());
            }
            catch (IllegalArgumentException e)
            {
                result.errors.add("originNodeUuid is not a valid UUID: '" + cfg.originNodeUuid + "'");
            }
        }

        if (cfg.onDemand.leaseSeconds < 0)
            result.errors.add("onDemand.leaseSeconds must be >= 0 (0 = leases never expire): got "
                + cfg.onDemand.leaseSeconds);

        checkJetStream(cfg.jetStream, result);

        checkGlobs("proactive.excludeSystemUids", cfg.proactive.excludeSystemUids, result);
        checkGlobs("proactive.commandRelay.onlySystemUids", cfg.proactive.commandRelay.onlySystemUids, result);

        // inert-settings warnings: config for the non-selected mode is silently ignored
        if (cfg.dataStreamingMode == DataStreamingMode.ON_DEMAND)
        {
            if (!cfg.proactive.dataFormats.isEmpty()
                || !cfg.proactive.excludeSystemUids.isEmpty()
                || cfg.proactive.commandRelay.enabled
                || !cfg.proactive.commandRelay.onlySystemUids.isEmpty())
            {
                result.warnings.add("proactive settings are ignored in ON_DEMAND mode");
            }
        }
        else
        {
            if (cfg.onDemand.leaseSeconds != new OnDemandConfig().leaseSeconds)
                result.warnings.add("onDemand settings are ignored in PROACTIVE mode");

            if (!cfg.proactive.commandRelay.enabled && !cfg.proactive.commandRelay.onlySystemUids.isEmpty())
                result.warnings.add("proactive.commandRelay.onlySystemUids is set but command relay "
                    + "is disabled — the filter has no effect");
        }

        return result;
    }


    private static void checkServer(ConSysApiNatsServiceConfig.NatsServerConfig server, Result result)
    {
        if (isBlank(server.url))
        {
            result.errors.add("server.url is required (e.g. nats://localhost:4222)");
        }
        else
        {
            try
            {
                var uri = new URI(server.url.trim());
                if (uri.getScheme() == null || !NATS_URL_SCHEMES.contains(uri.getScheme().toLowerCase())
                    || uri.getHost() == null)
                    throw new IllegalArgumentException();
            }
            catch (Exception e)
            {
                result.errors.add("server.url is not a valid NATS URL "
                    + "(expected <scheme>://host[:port] with scheme " + NATS_URL_SCHEMES + "): got '"
                    + server.url + "'");
            }
        }

        if (!isBlank(server.authToken) && !isBlank(server.username))
            result.errors.add("server.authToken and server.username are both set — choose ONE NATS "
                + "auth method (token or username/password)");

        if (!isBlank(server.password) && isBlank(server.username))
            result.errors.add("server.password is set without server.username — it would be "
                + "silently ignored");

        if (server.connectTimeoutSeconds <= 0)
            result.errors.add("server.connectTimeoutSeconds must be > 0: got "
                + server.connectTimeoutSeconds);
    }


    private static void checkJetStream(ConSysApiNatsServiceConfig.JetStreamConfig js, Result result)
    {
        if (js.enabled && (isBlank(js.streamName) || !SUBJECT_TOKEN.matcher(js.streamName.trim()).matches()))
            result.errors.add("jetStream.streamName must contain only letters, digits, '_', '-': got '"
                + js.streamName + "'");

        if (js.maxAgeSeconds < 0)
            result.errors.add("jetStream.maxAgeSeconds must be >= 0 (0 = unlimited): got " + js.maxAgeSeconds);

        if (js.maxMsgsPerSubject < 0)
            result.errors.add("jetStream.maxMsgsPerSubject must be >= 0 (0 = unlimited): got " + js.maxMsgsPerSubject);
    }


    /**
     * UID globs support '*' as the ONLY wildcard; every other character is
     * matched literally (see {@code ResourceDataPublisher.compileGlobs}). A
     * regex-style pattern like {@code urn:osh:.*} therefore silently never
     * matches — warn, since a bad glob cannot fail loudly at match time.
     * A literal '.' alone is NOT flagged: dots occur in real system UIDs.
     */
    private static void checkGlobs(String fieldName, List<String> globs, Result result)
    {
        for (var glob : globs)
        {
            if (glob == null || glob.isBlank())
                result.warnings.add(fieldName + " contains a blank entry — it is ignored");
            else if (REGEX_SUSPECT.matcher(glob).find())
                result.warnings.add(fieldName + " entry '" + glob + "' looks like a regex, but '*' "
                    + "is the only wildcard — every other character matches literally "
                    + "(e.g. '.*' matches a literal dot, not anything)");
        }
    }


    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }
}
