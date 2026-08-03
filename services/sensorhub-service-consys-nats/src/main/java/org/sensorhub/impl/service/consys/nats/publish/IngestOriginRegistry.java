/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.publish;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.vast.util.Asserts;


/**
 * <p>
 * JVM-wide registry of system UIDs whose data was <b>ingested from another
 * node</b> (mirrors). Fed by mirror-creating components (the consys-nats-client
 * module, third-party relays); consulted by {@link ResourceDataPublisher},
 * which opens no observation data streams for foreign systems — a node never
 * republishes data it did not originate.
 * </p>
 *
 * <p>
 * Static because the binding and the mirror client are separate modules whose
 * only common ground is the JVM. The origin label is informational; suppression
 * only needs the boolean. Listeners let the publisher close already-open
 * streams when a system is marked foreign after the publisher's startup scan
 * (client discovery runs asynchronously and may lose that race for mirrors
 * persisted from earlier runs).
 * </p>
 *
 * @author CR31
 * @since August 3, 2026
 */
public final class IngestOriginRegistry
{
    static final Map<String, String> originByUid = new ConcurrentHashMap<>();
    static final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();


    private IngestOriginRegistry() {}


    /**
     * Mark a system as ingested from another node. Idempotent; listeners fire
     * only on the first record of a UID.
     */
    public static void record(String systemUid, String originLabel)
    {
        Asserts.checkNotNullOrEmpty(systemUid, "systemUid");
        var first = originByUid.putIfAbsent(systemUid, originLabel != null ? originLabel : "") == null;
        if (first)
        {
            for (var l : listeners)
            {
                try { l.accept(systemUid); }
                catch (Exception e) { /* listener errors must not break recording */ }
            }
        }
    }


    /** True iff this system's data originates on another node. */
    public static boolean isForeign(String systemUid)
    {
        return systemUid != null && originByUid.containsKey(systemUid);
    }


    /** True iff any system has been marked foreign (cheap pre-check). */
    public static boolean hasEntries()
    {
        return !originByUid.isEmpty();
    }


    /** Informational origin label recorded for a foreign system, or null. */
    public static String getOrigin(String systemUid)
    {
        return systemUid != null ? originByUid.get(systemUid) : null;
    }


    /**
     * Register a listener called with the system UID whenever a system is
     * FIRST marked foreign. Close the returned handle to deregister.
     */
    public static AutoCloseable addListener(Consumer<String> onForeignUid)
    {
        Asserts.checkNotNull(onForeignUid, "onForeignUid");
        listeners.add(onForeignUid);
        return () -> listeners.remove(onForeignUid);
    }


    /** Test hook: wipe all state (the registry is JVM-global). */
    public static void clearForTests()
    {
        originByUid.clear();
        listeners.clear();
    }
}
