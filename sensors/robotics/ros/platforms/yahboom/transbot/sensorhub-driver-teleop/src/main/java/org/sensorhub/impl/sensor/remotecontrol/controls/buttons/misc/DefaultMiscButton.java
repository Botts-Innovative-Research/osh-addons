/*
 *  The contents of this file are subject to the Mozilla Public License, v. 2.0.
 *  If a copy of the MPL was not distributed with this file, You can obtain one
 *  at http://mozilla.org/MPL/2.0/.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 *  for the specific language governing rights and limitations under the License.
 *
 *  Copyright (C) 2023 Botts Innovative Research, Inc. All Rights Reserved.
 */
package org.sensorhub.impl.sensor.remotecontrol.controls.buttons.misc;

import org.sensorhub.impl.ros.controller.buttons.ButtonManager;
import org.sensorhub.impl.ros.controller.buttons.ButtonClass;
import org.sensorhub.impl.ros.controller.buttons.DefaultButton;

public class DefaultMiscButton extends DefaultButton {

    public DefaultMiscButton(final ButtonManager buttonManager, final String name, int buttonIdx) {
        super(buttonManager, ButtonClass.MISCELLANEOUS, name, buttonIdx);
    }
}