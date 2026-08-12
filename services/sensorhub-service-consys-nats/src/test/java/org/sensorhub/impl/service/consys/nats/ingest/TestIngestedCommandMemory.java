/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.BigId;


public class TestIngestedCommandMemory
{
    @Before
    public void clearSharedState()
    {
        IngestedCommandMemory.clear();
    }


    @Test
    public void markThenCheckHits()
    {
        var csId = BigId.fromLong(2, 42L);
        IngestedCommandMemory.mark(csId, 1000L);
        assertTrue(IngestedCommandMemory.wasIngested(csId, 1000L));
        assertFalse(IngestedCommandMemory.wasIngested(csId, 1001L));
        assertFalse(IngestedCommandMemory.wasIngested(BigId.fromLong(2, 43L), 1000L));
    }


    @Test
    public void unmarkRemoves()
    {
        var csId = BigId.fromLong(2, 42L);
        IngestedCommandMemory.mark(csId, 1000L);
        IngestedCommandMemory.unmark(csId, 1000L);
        assertFalse(IngestedCommandMemory.wasIngested(csId, 1000L));
    }


    /**
     * The marker (connector, ids decoded from subject tokens as BigIdBytes) and
     * the checker (publisher, ids from the datastore as BigIdLong) hold
     * different BigId implementations of the same logical id — the key must
     * match across implementations (the issue 09 defect class).
     */
    @Test
    public void keyMatchesAcrossBigIdImplementations()
    {
        var idLong = BigId.fromLong(2, 3L);
        var idBytes = BigId.fromBytes(2, idLong.getIdAsBytes());
        IngestedCommandMemory.mark(idBytes, 5000L);
        assertTrue(IngestedCommandMemory.wasIngested(idLong, 5000L));
        IngestedCommandMemory.unmark(idLong, 5000L);
        assertFalse(IngestedCommandMemory.wasIngested(idBytes, 5000L));
    }


    @Test
    public void extractIssueTime()
    {
        var ms = IngestedCommandMemory.extractIssueTimeMs(
            "{\"issueTime\": \"2026-08-04T23:15:37.713994Z\", \"parameters\": {\"a\": 1}}"
                .getBytes(StandardCharsets.UTF_8));
        // micros truncate to ms — both nodes parse the same string, so keys agree
        assertEquals((Long) java.time.Instant.parse("2026-08-04T23:15:37.713Z").toEpochMilli(), ms);

        assertNull(IngestedCommandMemory.extractIssueTimeMs(
            "{\"parameters\": {\"a\": 1}}".getBytes(StandardCharsets.UTF_8)));
        assertNull(IngestedCommandMemory.extractIssueTimeMs(
            "not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(IngestedCommandMemory.extractIssueTimeMs(
            "{\"issueTime\": \"garbage\"}".getBytes(StandardCharsets.UTF_8)));
    }


    @Test
    public void lruEvictsOldest()
    {
        var csId = BigId.fromLong(2, 1L);
        for (int i = 0; i < IngestedCommandMemory.RECENT_CACHE_SIZE + 1; i++)
            IngestedCommandMemory.mark(csId, i);
        assertFalse(IngestedCommandMemory.wasIngested(csId, 0L));
        assertTrue(IngestedCommandMemory.wasIngested(csId, IngestedCommandMemory.RECENT_CACHE_SIZE));
    }
}
