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

import com.vaadin.annotations.JavaScript;
import com.vaadin.ui.AbstractJavaScriptComponent;
import com.vaadin.ui.UI;
import elemental.json.Json;
import elemental.json.JsonArray;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConfig;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputSpectrum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;

@JavaScript({"rtlsdr_spectrum.js"})
public class RtlSdrSpectrumComponent extends AbstractJavaScriptComponent {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(RtlSdrSpectrumComponent.class);

    private final RtlSdrDriver driver;
    private transient Timer pushTimer;
    private long lastFrameTimestamp = -1;

    private final transient RtlSdrAudioDemodulator.AudioFrameListener audioListener = (pcmData, numSamples, base64Pcm) -> {
        try {
            UI ui = getUI();
            if (ui != null && isAttached()) {
                ui.access(() -> {
                    callFunction("playAudioChunk", base64Pcm);
                    ui.push();
                });
            }
        } catch (Exception ignored) {
        }
    };

    public RtlSdrSpectrumComponent(RtlSdrDriver driver) {
        this.driver = driver;

        // Register client -> server callbacks
        addFunction("tuneFrequency", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    double freqMhz = args.getNumber(0);
                    driver.setCenterFrequency(freqMhz);
                }
            } catch (Exception e) {
                log.error("Failed to tune frequency from UI: {}", e.getMessage());
            }
        });


        addFunction("setGain", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    double gainDb = args.getNumber(0);
                    driver.setTunerGain(gainDb);
                }
            } catch (Exception e) {
                log.error("Failed to set gain from UI: {}", e.getMessage());
            }
        });

        addFunction("setAutoGain", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    boolean autoGain = args.getBoolean(0);
                    driver.setAutoGain(autoGain);
                }
            } catch (Exception e) {
                log.error("Failed to set auto-gain from UI: {}", e.getMessage());
            }
        });

        addFunction("setAudioMode", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    String modeStr = args.getString(0);
                    RtlSdrConfig.AudioDemodMode mode = RtlSdrConfig.AudioDemodMode.valueOf(modeStr.toUpperCase());
                    driver.setAudioMode(mode);
                }
            } catch (Exception e) {
                log.error("Failed to set audio mode from UI: {}", e.getMessage());
            }
        });

        addFunction("setAudioVolume", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    double vol = args.getNumber(0);
                    driver.setAudioVolume((float) vol);
                }
            } catch (Exception e) {
                log.error("Failed to set audio volume from UI: {}", e.getMessage());
            }
        });

        addFunction("setSquelch", (JsonArray args) -> {
            try {
                if (args.length() > 0) {
                    double squelch = args.getNumber(0);
                    driver.setSquelchThreshold((float) squelch);
                }
            } catch (Exception e) {
                log.error("Failed to set squelch from UI: {}", e.getMessage());
            }
        });

        addFunction("requestSpectrum", (JsonArray args) -> {
            pushLatestFrame();
        });
    }

    public void pushLatestFrame() {
        try {
            RtlSdrOutputSpectrum output = driver.getOutputSpectrum();
            if (output == null) {
                callFunction("setStatus", "WAITING", "Output spectrum not available");
                return;
            }

            RtlSdrOutputSpectrum.SpectrumFrame frame = output.getLatestSpectrumFrame();
            if (frame == null) {
                if (driver.isStarted()) {
                    callFunction("setStatus", "WAITING", "Tuned to " + driver.getConfiguration().centerFrequency + " MHz. Awaiting samples...");
                } else {
                    callFunction("setStatus", "STOPPED", "Receiver stopped. Click Start above.");
                }
                return;
            }

            int len = frame.powerDb.length;
            JsonArray jsonArray = Json.createArray();
            for (int i = 0; i < len; i++) {
                jsonArray.set(i, Math.round(frame.powerDb[i] * 10.0) / 10.0);
            }

            callFunction("updateSpectrum", frame.centerFreqHz, frame.sampleRateHz, jsonArray);
        } catch (Exception e) {
            log.trace("Error in pushLatestFrame: {}", e.getMessage());
        }
    }

    @Override
    public void attach() {
        super.attach();

        // Register audio listener
        driver.getAudioDemodulator().addListener(audioListener);

        if (pushTimer != null) {
            pushTimer.cancel();
        }

        // Send initial state immediately
        pushLatestFrame();

        // Start high-responsiveness 25 Hz refresh timer (every 40 ms)
        pushTimer = new Timer("RtlSdrSpectrumPush-" + driver.getLocalID(), true);
        pushTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    UI ui = getUI();
                    if (ui == null || !isAttached()) {
                        cancel();
                        return;
                    }

                    RtlSdrOutputSpectrum output = driver.getOutputSpectrum();
                    if (output == null) {
                        return;
                    }

                    RtlSdrOutputSpectrum.SpectrumFrame frame = output.getLatestSpectrumFrame();
                    if (frame == null || frame.timestamp == lastFrameTimestamp) {
                        return;
                    }
                    lastFrameTimestamp = frame.timestamp;

                    ui.access(() -> {
                        pushLatestFrame();
                        ui.push();
                    });

                } catch (Exception e) {
                    // ignore push error
                }
            }
        } , 300, 40);
    }

    @Override
    public void detach() {
        driver.getAudioDemodulator().removeListener(audioListener);
        if (pushTimer != null) {
            pushTimer.cancel();
            pushTimer = null;
        }
        super.detach();
    }
}
