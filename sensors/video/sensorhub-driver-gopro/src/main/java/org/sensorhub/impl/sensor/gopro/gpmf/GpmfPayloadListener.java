/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.gopro.gpmf;

/**
 * Callback for consumers of decoded GPMF payloads.
 * <p>
 * A GPMF stream carries one listener, so the outputs that publish telemetry do not listen to it directly.
 * They register here instead, and {@link GpmfDispatcher} decodes each packet once and hands the result to
 * all of them.
 */
public interface GpmfPayloadListener {
    /**
     * Called for each GPMF packet that decoded successfully.
     *
     * @param payload         The sensor streams the packet carried.
     * @param timestampMillis Collection time of the packet, in milliseconds since the epoch.
     */
    void onGpmfPayload(GpmfPayload payload, long timestampMillis);
}