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

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.DataStreamingMode;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.ProactiveFormat;


/**
 * Unit tests for {@link ConSysApiNatsConfigValidator}: every hard-fail rule,
 * every inert-setting/glob warning, error aggregation, and null-block
 * normalization. Pure config-object tests — no NATS connection or hub.
 */
public class TestConSysApiNatsConfigValidator
{

    private ConSysApiNatsServiceConfig config()
    {
        return new ConSysApiNatsServiceConfig();
    }


    private void assertOneErrorContaining(ConSysApiNatsServiceConfig cfg, String substring)
    {
        var result = ConSysApiNatsConfigValidator.validate(cfg);
        assertEquals("expected exactly one error, got: " + result.errors, 1, result.errors.size());
        assertTrue("error should mention '" + substring + "': " + result.errors.get(0),
            result.errors.get(0).contains(substring));
    }


    private void assertOneWarningContaining(ConSysApiNatsServiceConfig cfg, String substring)
    {
        var result = ConSysApiNatsConfigValidator.validate(cfg);
        assertTrue("expected no errors, got: " + result.errors, result.errors.isEmpty());
        assertEquals("expected exactly one warning, got: " + result.warnings, 1, result.warnings.size());
        assertTrue("warning should mention '" + substring + "': " + result.warnings.get(0),
            result.warnings.get(0).contains(substring));
    }


    // ---------- baseline ----------

    @Test
    public void defaultConfigIsValid()
    {
        var result = ConSysApiNatsConfigValidator.validate(config());
        assertTrue(result.errors.isEmpty());
        assertTrue(result.warnings.isEmpty());
    }


    @Test
    public void nullNestedBlocksAreNormalizedNotNpe()
    {
        var cfg = config();
        cfg.server = null;
        cfg.proactive = null;
        cfg.onDemand = null;
        cfg.jetStream = null;
        cfg.dataStreamingMode = null;

        var result = ConSysApiNatsConfigValidator.validate(cfg);

        assertTrue(result.errors.isEmpty());
        assertNotNull(cfg.server);
        assertNotNull(cfg.proactive);
        assertNotNull(cfg.proactive.commandRelay);
        assertNotNull(cfg.onDemand);
        assertNotNull(cfg.jetStream);
        assertEquals(DataStreamingMode.PROACTIVE, cfg.dataStreamingMode);
    }


    @Test
    public void nullListsAreNormalized()
    {
        var cfg = config();
        cfg.proactive.dataFormats = null;
        cfg.proactive.excludeSystemUids = null;
        cfg.proactive.commandRelay.onlySystemUids = null;

        var result = ConSysApiNatsConfigValidator.validate(cfg);

        assertTrue(result.errors.isEmpty());
        assertNotNull(cfg.proactive.dataFormats);
        assertNotNull(cfg.proactive.excludeSystemUids);
        assertNotNull(cfg.proactive.commandRelay.onlySystemUids);
    }


    // ---------- nodeId ----------

    @Test
    public void blankNodeIdFails()
    {
        var cfg = config();
        cfg.nodeId = "  ";
        assertOneErrorContaining(cfg, "nodeId");
    }


    @Test
    public void nodeIdWithDotFails()
    {
        var cfg = config();
        cfg.nodeId = "a.b";
        assertOneErrorContaining(cfg, "nodeId");
    }


    @Test
    public void nodeIdWithNatsWildcardsFails()
    {
        var cfg = config();
        cfg.nodeId = "api*";
        assertOneErrorContaining(cfg, "nodeId");

        cfg.nodeId = "api>";
        assertOneErrorContaining(cfg, "nodeId");
    }


    @Test
    public void nodeIdWithSpaceFails()
    {
        var cfg = config();
        cfg.nodeId = "my api";
        assertOneErrorContaining(cfg, "nodeId");
    }


    @Test
    public void nodeIdWithAllowedCharsOk()
    {
        var cfg = config();
        cfg.nodeId = "Node_1-a";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    // ---------- server url ----------

    @Test
    public void blankServerUrlFails()
    {
        var cfg = config();
        cfg.server.url = "";
        assertOneErrorContaining(cfg, "server.url");
    }


    @Test
    public void malformedServerUrlFails()
    {
        var cfg = config();
        cfg.server.url = "localhost:4222";  // no scheme
        assertOneErrorContaining(cfg, "server.url");

        cfg.server.url = "http://localhost:4222";  // wrong scheme
        assertOneErrorContaining(cfg, "server.url");
    }


    @Test
    public void natsAndTlsUrlsOk()
    {
        var cfg = config();
        cfg.server.url = "nats://broker.example.com:4222";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());

        cfg.server.url = "tls://broker.example.com";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    // ---------- server auth ----------

    @Test
    public void tokenPlusUsernameFails()
    {
        var cfg = config();
        cfg.server.authToken = "secret";
        cfg.server.username = "user";
        assertOneErrorContaining(cfg, "ONE NATS auth method");
    }


    @Test
    public void passwordWithoutUsernameFails()
    {
        var cfg = config();
        cfg.server.password = "secret";
        assertOneErrorContaining(cfg, "server.password");
    }


    @Test
    public void tokenAloneOk()
    {
        var cfg = config();
        cfg.server.authToken = "secret";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    @Test
    public void userPasswordAloneOk()
    {
        var cfg = config();
        cfg.server.username = "user";
        cfg.server.password = "secret";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    @Test
    public void nonPositiveConnectTimeoutFails()
    {
        var cfg = config();
        cfg.server.connectTimeoutSeconds = 0;
        assertOneErrorContaining(cfg, "connectTimeoutSeconds");
    }


    // ---------- originNodeUuid ----------

    @Test
    public void malformedOriginUuidFails()
    {
        var cfg = config();
        cfg.originNodeUuid = "not-a-uuid";
        assertOneErrorContaining(cfg, "originNodeUuid");
    }


    @Test
    public void validOriginUuidOk()
    {
        var cfg = config();
        cfg.originNodeUuid = "123e4567-e89b-42d3-a456-426614174000";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    // ---------- lease ----------

    @Test
    public void negativeLeaseFails()
    {
        var cfg = config();
        cfg.dataStreamingMode = DataStreamingMode.ON_DEMAND;
        cfg.onDemand.leaseSeconds = -5;
        assertOneErrorContaining(cfg, "leaseSeconds");
    }


    @Test
    public void zeroLeaseOk()
    {
        var cfg = config();
        cfg.dataStreamingMode = DataStreamingMode.ON_DEMAND;
        cfg.onDemand.leaseSeconds = 0;
        var result = ConSysApiNatsConfigValidator.validate(cfg);
        assertTrue(result.errors.isEmpty());
        assertTrue(result.warnings.isEmpty());
    }


    // ---------- JetStream ----------

    @Test
    public void badStreamNameFailsWhenJetStreamEnabled()
    {
        var cfg = config();
        cfg.jetStream.enabled = true;
        cfg.jetStream.streamName = "BAD NAME!";
        assertOneErrorContaining(cfg, "streamName");
    }


    @Test
    public void badStreamNameIgnoredWhenJetStreamDisabled()
    {
        var cfg = config();
        cfg.jetStream.enabled = false;
        cfg.jetStream.streamName = "BAD NAME!";
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).errors.isEmpty());
    }


    @Test
    public void negativeJetStreamLimitsFail()
    {
        var cfg = config();
        cfg.jetStream.maxAgeSeconds = -1;
        assertOneErrorContaining(cfg, "maxAgeSeconds");

        cfg = config();
        cfg.jetStream.maxMsgsPerSubject = -1;
        assertOneErrorContaining(cfg, "maxMsgsPerSubject");
    }


    // ---------- globs ----------

    @Test
    public void regexStyleGlobWarns()
    {
        var cfg = config();
        cfg.proactive.excludeSystemUids = List.of("urn:osh:.*");
        assertOneWarningContaining(cfg, "only wildcard");
    }


    @Test
    public void regexMetacharGlobWarns()
    {
        var cfg = config();
        cfg.proactive.commandRelay.enabled = true;
        cfg.proactive.commandRelay.onlySystemUids = List.of("urn:osh:(a|b)");
        assertOneWarningContaining(cfg, "only wildcard");
    }


    @Test
    public void plainStarGlobDoesNotWarn()
    {
        var cfg = config();
        cfg.proactive.excludeSystemUids = List.of("urn:osh:system:remote:*", "*:special");
        var result = ConSysApiNatsConfigValidator.validate(cfg);
        assertTrue(result.warnings.isEmpty());
    }


    @Test
    public void literalDotInGlobDoesNotWarn()
    {
        // dots occur in real system UIDs — a lone '.' is a valid literal
        var cfg = config();
        cfg.proactive.excludeSystemUids = List.of("urn:osh:sensor:station.north:*");
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).warnings.isEmpty());
    }


    @Test
    public void blankGlobEntryWarns()
    {
        var cfg = config();
        cfg.proactive.excludeSystemUids = List.of(" ");
        assertOneWarningContaining(cfg, "blank");
    }


    // ---------- inert mode settings ----------

    @Test
    public void proactiveSettingsWarnInOnDemandMode()
    {
        var cfg = config();
        cfg.dataStreamingMode = DataStreamingMode.ON_DEMAND;
        cfg.proactive.dataFormats = List.of(ProactiveFormat.SWE_JSON);
        assertOneWarningContaining(cfg, "ON_DEMAND");
    }


    @Test
    public void relayEnabledWarnsInOnDemandMode()
    {
        var cfg = config();
        cfg.dataStreamingMode = DataStreamingMode.ON_DEMAND;
        cfg.proactive.commandRelay.enabled = true;
        assertOneWarningContaining(cfg, "ON_DEMAND");
    }


    @Test
    public void onDemandLeaseWarnsInProactiveMode()
    {
        var cfg = config();
        cfg.onDemand.leaseSeconds = 60;
        assertOneWarningContaining(cfg, "PROACTIVE");
    }


    @Test
    public void relayFilterWithoutRelayWarns()
    {
        var cfg = config();
        cfg.proactive.commandRelay.onlySystemUids = List.of("urn:osh:system:remote:*");
        assertOneWarningContaining(cfg, "relay");
    }


    @Test
    public void relayFilterWithRelayEnabledDoesNotWarn()
    {
        var cfg = config();
        cfg.proactive.commandRelay.enabled = true;
        cfg.proactive.commandRelay.onlySystemUids = List.of("urn:osh:system:remote:*");
        assertTrue(ConSysApiNatsConfigValidator.validate(cfg).warnings.isEmpty());
    }


    // ---------- aggregation ----------

    @Test
    public void multipleErrorsAreAggregated()
    {
        var cfg = config();
        cfg.nodeId = "a.b";
        cfg.server.url = "bogus";
        cfg.server.password = "secret";
        cfg.onDemand.leaseSeconds = -1;
        cfg.originNodeUuid = "nope";

        var result = ConSysApiNatsConfigValidator.validate(cfg);

        assertEquals("all problems reported in one pass: " + result.errors, 5, result.errors.size());
    }
}
