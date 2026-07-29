/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.subject;

import static org.junit.Assert.*;
import static org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator.ResourceType.*;
import java.util.Optional;
import org.junit.Test;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator.ResourceType;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;


/**
 * Unit tests for {@link ConSysSubjectValidator}. Port of the MQTT binding's
 * {@code TestConSysTopicValidator} with NATS subject syntax: {@code .} token
 * separators, {@code *} single-token wildcard, {@code >} multi-token wildcard,
 * and {@code :data.<token>} format subtopics.
 *
 * <p>Each test method covers one category of subjects. Within each method the
 * cases are laid out as an aligned table — {@code path} on the left, expected
 * {@link ResourceType} (or {@code null} = no match) on the right.</p>
 *
 * <p>{@code >} wildcard semantics (per OGC CS API Part 3, NATS syntax):
 * <ul>
 *   <li>{@code >} is valid at any resource-ID slot — it replaces that ID and
 *       everything below (e.g. {@code systems.*.subsystems.>}).</li>
 *   <li>{@code >} is valid as a trailing wildcard after the full pattern is
 *       consumed (e.g. {@code systems.abc123.>}).</li>
 *   <li>{@code >} is invalid at a keyword/literal slot
 *       (e.g. {@code systems.>.datastreams.*} — also not last, doubly invalid).</li>
 *   <li>{@code >} must always be the last segment (standard NATS).</li>
 * </ul>
 * The ResourceType returned for a {@code >} subscription is determined by the
 * shallowest pattern that matches up to the {@code >} position, which is also
 * the type used for permission checking.</p>
 */
public class TestConSysSubjectValidator
{
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void assertMatches(ResourceType expected, String path)
    {
        var result = ConSysSubjectValidator.matchEventSubject(path);
        assertTrue("Expected " + expected + " for path '" + path + "' but got empty", result.isPresent());
        assertEquals("Wrong ResourceType for path '" + path + "'", expected, result.get());
    }

    private static void assertNoMatch(String path)
    {
        var result = ConSysSubjectValidator.matchEventSubject(path);
        assertFalse("Expected no match for path '" + path + "' but got " + result.orElse(null), result.isPresent());
    }

    /** Runs a table of {path, ResourceType-or-null} pairs through the validator. */
    private static void assertTable(Object[][] rows)
    {
        for (var row : rows)
        {
            var path = (String) row[0];
            var expected = (ResourceType) row[1];
            if (expected == null)
                assertNoMatch(path);
            else
                assertMatches(expected, path);
        }
    }


    // =========================================================================
    // Exact subjects — concrete IDs in every slot, no wildcards
    // =========================================================================

    @Test
    public void testExactSubjects()
    {
        assertTable(new Object[][] {
            // path                                                                          expected
            { "systems.abc123",                                                              SYSTEM        },
            { "systems.abc123.subsystems.def456",                                            SYSTEM        },
            { "systems.abc123.deployments.dep001",                                           SYSTEM        },
            { "systems.abc123.datastreams.ds001",                                            DATASTREAM    },
            { "systems.abc123.controlstreams.cs001",                                         CONTROLSTREAM },
            { "systems.abc123.datastreams.ds001.observations.obs001",                        OBSERVATION   },
            { "systems.abc123.controlstreams.cs001.commands.cmd001",                         COMMAND       },
            { "systems.abc123.controlstreams.cs001.commands.cmd001.status.st001",            COMMAND       },
            { "systems.abc123.controlstreams.cs001.commands.cmd001.result.res001",           COMMAND       },
            { "deployments.dep001",                                                          DEPLOYMENT    },
            { "deployments.dep001.subdeployments.sub001",                                    DEPLOYMENT    },
            { "procedures.proc001",                                                          PROCEDURE     },
            { "properties.prop001",                                                          PROPERTY      },
        });
    }


    // =========================================================================
    // Single-token wildcard (*) — only valid in resource-ID slots
    // =========================================================================

    @Test
    public void testStarWildcardSubjects()
    {
        assertTable(new Object[][] {
            // path                                                                          expected
            // Valid: * in every ID slot
            { "systems.*",                                                                   SYSTEM        },
            { "systems.*.subsystems.*",                                                      SYSTEM        },
            { "systems.*.datastreams.*",                                                     DATASTREAM    },
            { "systems.*.controlstreams.*",                                                  CONTROLSTREAM },
            { "systems.*.datastreams.*.observations.*",                                      OBSERVATION   },
            { "systems.abc123.datastreams.*",                                                DATASTREAM    },  // concrete system + wildcard datastream
            { "systems.abc123.datastreams.ds001.observations.*",                             OBSERVATION   },
            { "systems.abc123.datastreams.*.observations.*",                                 OBSERVATION   },
            { "systems.03ie1mkrr9r0.datastreams.*",                                          DATASTREAM    },  // alphanumeric system ID
            { "systems.03ie1mkrr9r0.datastreams.*.observations.*",                           OBSERVATION   },
            { "deployments.*",                                                               DEPLOYMENT    },
            { "procedures.*",                                                                PROCEDURE     },
            { "properties.*",                                                                PROPERTY      },

            // Invalid: * in a keyword slot
            { "*.abc123",                                                                    null          },
            { "systems.abc123.*.ds001",                                                      null          },
            { "systems.abc123.datastreams.ds001.*.obs001",                                   null          },
        });
    }


    // =========================================================================
    // Multi-token wildcard (>)
    //
    // > is valid at any resource-ID slot (replacing that ID and everything below)
    // and as a trailing wildcard after the pattern is fully consumed.
    // > is invalid at a keyword/literal slot or when not the last segment.
    //
    // The ResourceType returned is that of the shallowest matching pattern
    // (e.g. systems.abc123.datastreams.> → DATASTREAM, not OBSERVATION).
    // =========================================================================

    @Test
    public void testGtWildcardSubjects()
    {
        assertTable(new Object[][] {
            // path                                                                          expected

            // --- Valid: > at system-level ID slot (returns SYSTEM) ---
            { "systems.>",                                                                   SYSTEM        },  // all system events (> replaces system ID)
            { "systems.abc123.>",                                                            SYSTEM        },  // everything under one system (trailing >)
            { "systems.*.>",                                                                 SYSTEM        },  // everything under all systems (trailing >)

            // --- Valid: > at subsystem-level ID slot (returns SYSTEM) ---
            { "systems.*.subsystems.>",                                                      SYSTEM        },  // all subsystems of all systems at all levels
            { "systems.abc123.subsystems.>",                                                 SYSTEM        },  // all subsystems of one system
            { "systems.*.subsystems.*.>",                                                    SYSTEM        },  // trailing > after subsystem pattern

            // --- Valid: > at datastream-level ID slot (returns DATASTREAM) ---
            { "systems.abc123.datastreams.>",                                                DATASTREAM    },  // all datastreams of one system
            { "systems.*.datastreams.>",                                                     DATASTREAM    },  // all datastreams of all systems
            { "systems.abc123.datastreams.ds001.>",                                          DATASTREAM    },  // everything under one datastream (trailing >)
            { "systems.abc123.datastreams.*.>",                                              DATASTREAM    },  // trailing > with wildcard datastream ID
            { "systems.*.datastreams.*.>",                                                   DATASTREAM    },  // trailing > with both IDs wildcarded

            // --- Valid: > at observation-level ID slot (returns OBSERVATION) ---
            { "systems.abc123.datastreams.ds001.observations.>",                             OBSERVATION   },  // all observations from one datastream

            // --- Valid: top-level collection shortcuts ---
            { "deployments.>",                                                               DEPLOYMENT    },
            { "procedures.>",                                                                PROCEDURE     },

            // --- Invalid: > at a keyword slot ---
            { ">",                                                                           null          },  // bare > — "systems" keyword expected at pos 0
            { "systems.>.datastreams.*",                                                     null          },  // > not last segment
        });
    }


    // =========================================================================
    // Invalid — malformed or unknown paths
    // =========================================================================

    @Test
    public void testInvalidSubjects()
    {
        assertTable(new Object[][] {
            // path                                                                          expected
            { "",                                                                            null          },  // empty
            { "systems",                                                                     null          },  // missing ID segment
            { "sensors.abc123",                                                              null          },  // unknown top-level keyword
            { "oshex.systems.abc123",                                                        null          },  // nodeId prefix not stripped
            { "systems.abc123.datastreams.ds001.observations.obs001.extra",                  null          },  // too many segments
        });
    }


    // =========================================================================
    // hasWildcard
    // =========================================================================

    @Test
    public void testHasWildcard()
    {
        Object[][] cases = {
            // subject                                          hasWildcard
            { "systems.abc123.datastreams.ds001",              false },
            { "",                                              false },
            { "systems.*.datastreams.*",                       true  },
            { "systems.abc123.>",                              true  },
            { "systems.*.datastreams.>",                       true  },
        };
        for (var row : cases)
            assertEquals("hasWildcard(\"" + row[0] + "\")", row[1],
                ConSysSubjectValidator.hasWildcard((String) row[0]));
    }


    // =========================================================================
    // isDataSubject / parseDataSubjectFormat / stripDataSuffix
    // =========================================================================

    @Test
    public void testIsDataSubject()
    {
        Object[][] cases = {
            // subject                                                              isDataSubject
            { "systems.abc.datastreams.xyz.observations:data",                      true  },
            { "systems.abc.datastreams.xyz.observations:data.swe-proto",            true  },
            { "systems.abc.datastreams.xyz.observations:data.swe-json",             true  },
            { "systems.abc.controlstreams.cs1.commands:data",                       true  },
            { "systems.abc.datastreams.xyz.observations",                           false },
            { "systems.abc",                                                        false },
            { "",                                                                   false },
            { null,                                                                 false },
        };
        for (var row : cases)
            assertEquals("isDataSubject(\"" + row[0] + "\")", row[1],
                ConSysSubjectValidator.isDataSubject((String) row[0]));
    }


    @Test
    public void testParseDataSubjectFormat() throws InvalidSubjectException
    {
        // Bare :data → empty (server-default negotiation)
        assertEquals(Optional.empty(),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data"));

        // Known tokens map to their ResourceFormat constant
        assertEquals(Optional.of(ResourceFormat.fromMimeType("application/swe+proto")),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.swe-proto"));
        assertEquals(Optional.of(ResourceFormat.fromMimeType("application/swe+flatbuffers")),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.swe-flatbuffers"));
        assertEquals(Optional.of(ResourceFormat.SWE_JSON),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.swe-json"));
        assertEquals(Optional.of(ResourceFormat.SWE_BINARY),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.swe-binary"));
        assertEquals(Optional.of(ResourceFormat.SWE_TEXT),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.swe-csv"));
        assertEquals(Optional.of(ResourceFormat.OM_JSON),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations:data.om-json"));

        // Subject with no :data suffix → empty (caller decides whether to treat as error)
        assertEquals(Optional.empty(),
            ConSysSubjectValidator.parseDataSubjectFormat("systems.abc.datastreams.xyz.observations"));
        assertEquals(Optional.empty(), ConSysSubjectValidator.parseDataSubjectFormat(null));
    }


    @Test
    public void testParseDataSubjectFormatUnknownToken()
    {
        try
        {
            ConSysSubjectValidator.parseDataSubjectFormat(
                "systems.abc.datastreams.xyz.observations:data.yaml");
            fail("Unknown token should throw InvalidSubjectException");
        }
        catch (InvalidSubjectException expected)
        {
            assertTrue(expected.getMessage().contains("yaml"));
        }
    }


    @Test
    public void testStripDataSuffix()
    {
        assertEquals("systems.abc.datastreams.xyz.observations",
            ConSysSubjectValidator.stripDataSuffix(
                "systems.abc.datastreams.xyz.observations:data"));
        assertEquals("systems.abc.datastreams.xyz.observations",
            ConSysSubjectValidator.stripDataSuffix(
                "systems.abc.datastreams.xyz.observations:data.swe-proto"));
        assertEquals("systems.abc.datastreams.xyz.observations",
            ConSysSubjectValidator.stripDataSuffix(
                "systems.abc.datastreams.xyz.observations"));
        assertNull(ConSysSubjectValidator.stripDataSuffix(null));
    }
}
