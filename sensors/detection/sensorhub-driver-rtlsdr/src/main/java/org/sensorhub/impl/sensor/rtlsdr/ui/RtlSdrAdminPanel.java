/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.ui;

import com.vaadin.ui.Panel;
import com.vaadin.ui.VerticalLayout;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.ui.SensorAdminPanel;
import org.sensorhub.ui.data.MyBeanItem;

public class RtlSdrAdminPanel extends SensorAdminPanel {
    private static final long serialVersionUID = 1L;

    protected RtlSdrSpectrumComponent spectrumComponent;
    protected Panel spectrumPanel;

    @Override
    public void build(MyBeanItem<ModuleConfig> configItem, ISensorModule<?> module) {
        super.build(configItem, module);

        if (module instanceof RtlSdrDriver) {
            RtlSdrDriver driver = (RtlSdrDriver) module;
            buildSpectrumDisplay(driver);
        }
    }

    protected void buildSpectrumDisplay(RtlSdrDriver driver) {
        spectrumPanel = newPanel("Live RF Spectrum & Waterfall");

        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);
        layout.setSpacing(true);
        layout.setWidth("100%");
        layout.setHeightUndefined();

        spectrumComponent = new RtlSdrSpectrumComponent(driver);
        spectrumComponent.setWidth("100%");
        spectrumComponent.setHeight("780px");

        layout.addComponent(spectrumComponent);
        spectrumPanel.setContent(layout);

        // Place right after configuration tabs (index 2) if present, else append
        if (getComponentCount() > 2) {
            addComponent(spectrumPanel, 2);
        } else {
            addComponent(spectrumPanel);
        }
    }
}
