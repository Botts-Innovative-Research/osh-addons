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


/**
 * <p>
 * Signals that a NATS subject does not conform to the CS API Part 3 channel
 * hierarchy (the NATS analogue of the MQTT binding's
 * {@code InvalidTopicException}).
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
@SuppressWarnings("serial")
public class InvalidSubjectException extends Exception
{
    public InvalidSubjectException(String msg)
    {
        super(msg);
    }


    public InvalidSubjectException(String msg, Throwable cause)
    {
        super(msg, cause);
    }
}
