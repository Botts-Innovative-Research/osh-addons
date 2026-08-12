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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.sensorhub.api.common.BigId;
import com.google.gson.JsonParser;


/**
 * <p>
 * Recently-ingested command identities, JVM-shared:
 * {@code (control stream, issueTime @ epoch-ms)}.
 * </p><p>
 * The connector marks a command's identity BEFORE POSTing an inbound NATS
 * command publish; the relay-mode command publisher checks the memory and
 * skips republishing a match. Rationale: a command that ARRIVED over NATS is
 * already on the broker — everyone who cares has seen it — and republishing
 * it hands it back to the external relay, which would forward it to the
 * source node again and double-task the driver. Ingest is terminal (issue 08)
 * applied to commands. The observe-only status-triggered echo (source nodes)
 * is deliberately NOT gated: the echo of an ingested command is how a relay
 * learns the id this node assigned.
 * </p><p>
 * Only commands whose payload carries an explicit {@code issueTime} are
 * marked (relayed copies always do — issueTime is preserved verbatim across
 * nodes; operator-issued POSTs normally omit it and are never suppressed).
 * Keys are {@code (BigId, Long)} pairs, never {@code BigId.toString()} —
 * BigId equality is consistent across implementations, its string form is not
 * (see issue 09).
 * </p>
 *
 * @author CR31
 * @since August 4, 2026
 */
public class IngestedCommandMemory
{
    /** Bound of the recently-ingested command cache (LRU). */
    static final int RECENT_CACHE_SIZE = 1000;

    static final Set<Object> recent = Collections.newSetFromMap(Collections.synchronizedMap(
        new LinkedHashMap<Object, Boolean>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest)
            {
                return size() > RECENT_CACHE_SIZE;
            }
        }));


    private IngestedCommandMemory() {}


    /** Record a command identity BEFORE the ingest POST (mark-first: the
     *  receiver delivers to the relay publisher during the POST, and the mark
     *  must win that race). Use {@link #unmark} if the POST fails. */
    public static void mark(BigId csInternalId, long issueTimeMs)
    {
        recent.add(key(csInternalId, issueTimeMs));
    }


    /** Roll back a {@link #mark} after a failed POST. */
    public static void unmark(BigId csInternalId, long issueTimeMs)
    {
        recent.remove(key(csInternalId, issueTimeMs));
    }


    /** True iff this command identity was recently ingested from NATS. */
    public static boolean wasIngested(BigId csInternalId, long issueTimeMs)
    {
        return recent.contains(key(csInternalId, issueTimeMs));
    }


    /** Test support: reset the JVM-shared state. */
    public static void clear()
    {
        recent.clear();
    }


    /**
     * Epoch-ms {@code issueTime} of a JSON command payload, or null when the
     * payload is not a JSON object, carries no issueTime, or fails to parse
     * (fail open — an unmarked command is simply republished as before).
     */
    public static Long extractIssueTimeMs(byte[] payload)
    {
        try
        {
            var json = JsonParser.parseReader(new InputStreamReader(
                new ByteArrayInputStream(payload), StandardCharsets.UTF_8));
            if (!json.isJsonObject() || !json.getAsJsonObject().has("issueTime"))
                return null;
            return OffsetDateTime.parse(json.getAsJsonObject().get("issueTime").getAsString())
                .toInstant().toEpochMilli();
        }
        catch (Exception e)
        {
            return null;
        }
    }


    private static Object key(BigId csInternalId, long issueTimeMs)
    {
        return Map.entry(csInternalId, issueTimeMs);
    }
}
