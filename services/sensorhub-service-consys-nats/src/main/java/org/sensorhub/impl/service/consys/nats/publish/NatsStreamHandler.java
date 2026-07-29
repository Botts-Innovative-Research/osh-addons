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

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.sensorhub.impl.service.consys.stream.StreamHandler;
import org.vast.util.Asserts;
import io.nats.client.Connection;


/**
 * <p>
 * {@link StreamHandler} that publishes CS API Resource Data Messages to a single
 * NATS data subject. This is the NATS analogue of the MQTT binding's
 * {@code MqttSubscriber} inner class, extracted to the top level so it can be
 * driven both on demand (control channel) and proactively
 * ({@link ResourceDataPublisher}).
 * </p>
 *
 * <p>
 * In ON_DEMAND mode multiple clients may share the handler for a data subject;
 * client tracking (leases) lives in {@code ConSysApiNatsConnector.StreamEntry}.
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class NatsStreamHandler implements StreamHandler
{
    final List<String> dataSubjects;
    final NatsOutputStream os;
    Runnable onStart, onClose;
    final AtomicBoolean started = new AtomicBoolean();


    public NatsStreamHandler(Connection nats, String dataSubject)
    {
        this(nats, List.of(Asserts.checkNotNull(dataSubject, "dataSubject")), null);
    }


    public NatsStreamHandler(Connection nats, String dataSubject, String contentType)
    {
        this(nats, List.of(Asserts.checkNotNull(dataSubject, "dataSubject")), contentType);
    }


    /**
     * Publish each packet to every subject in {@code dataSubjects} (one
     * serialization, N publishes), tagging messages with {@code contentType}
     * (may be null if the payload MIME type is not known up front).
     */
    public NatsStreamHandler(Connection nats, List<String> dataSubjects, String contentType)
    {
        this.dataSubjects = List.copyOf(Asserts.checkNotNullOrEmpty(dataSubjects, "dataSubjects"));
        this.os = new NatsOutputStream(nats, this.dataSubjects, contentType, 1024, false);
    }


    @Override
    public void sendPacket() throws IOException
    {
        os.send();
    }


    @Override
    public void sendPacket(long correlId) throws IOException
    {
        os.send(correlId);
    }


    @Override
    public OutputStream getOutputStream()
    {
        return os;
    }


    @Override
    public void setStartCallback(Runnable onStart)
    {
        this.onStart = Asserts.checkNotNull(onStart, "onStart");
    }


    @Override
    public void setCloseCallback(Runnable onClose)
    {
        this.onClose = Asserts.checkNotNull(onClose, "onClose");
    }


    public void maybeStart()
    {
        if (onStart != null && started.compareAndSet(false, true))
            onStart.run();
    }


    @Override
    public void close()
    {
        if (onClose != null)
            onClose.run();
    }
}
