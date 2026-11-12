window.org_sensorhub_impl_sensor_rtlsdr_ui_RtlSdrSpectrumComponent = function() {
    var self = this;
    var container = self.getElement();

    // Configuration / State
    var state = {
        centerFreqHz: 100000000,
        sampleRateHz: 2048000,
        sweepSpanMhz: 2.0,
        minDb: -100,
        maxDb: -20,
        autoDb: true,
        paused: false,
        waterfallSpeed: 2,
        averaging: 2, // 1 = raw, 2 = 2x, 4 = 4x, 8 = 8x
        lastBins: null,
        smoothedBins: null,
        peakHoldBins: null,
        peakHoldDecay: 0.25, // dB per frame
        peakFreqHz: 0,
        peakDb: -999,
        mousePos: null,
        isHovering: false,
        statusMsg: null,
        frameCount: 0
    };

    // Precompute 256-color heat palette: [r, g, b] per color
    // Navy -> Blue -> Cyan -> Green -> Yellow -> Orange -> Red -> White
    var palette = new Uint8Array(256 * 3);
    function initPalette() {
        for (var i = 0; i < 256; i++) {
            var norm = i / 255.0;
            var r = 0, g = 0, b = 0;
            if (norm < 0.15) {
                var t = norm / 0.15;
                r = Math.floor(10 * t);
                g = Math.floor(15 * t);
                b = Math.floor(40 + 120 * t);
            } else if (norm < 0.35) {
                var t = (norm - 0.15) / 0.20;
                r = Math.floor(10 + 20 * t);
                g = Math.floor(15 + 185 * t);
                b = Math.floor(160 + 95 * t);
            } else if (norm < 0.55) {
                var t = (norm - 0.35) / 0.20;
                r = Math.floor(30 + 30 * t);
                g = Math.floor(200 + 40 * t);
                b = Math.floor(255 * (1.0 - t));
            } else if (norm < 0.75) {
                var t = (norm - 0.55) / 0.20;
                r = Math.floor(60 + 195 * t);
                g = Math.floor(240 + 15 * t);
                b = 0;
            } else if (norm < 0.90) {
                var t = (norm - 0.75) / 0.15;
                r = 255;
                g = Math.floor(255 * (1.0 - 0.7 * t));
                b = 0;
            } else {
                var t = (norm - 0.90) / 0.10;
                r = 255;
                g = Math.floor(76 + 179 * t);
                b = Math.floor(255 * t);
            }
            palette[i * 3 + 0] = r;
            palette[i * 3 + 1] = g;
            palette[i * 3 + 2] = b;
        }
    }
    initPalette();

    // Inject CSS & HTML
    container.innerHTML = `
        <style>
            .rtlsdr-wrapper {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                background: #0f141c;
                border: 1px solid #283347;
                border-radius: 8px;
                padding: 12px;
                box-sizing: border-box;
                color: #e2e8f0;
                display: flex;
                flex-direction: column;
                gap: 10px;
                width: 100%;
                user-select: none;
            }
            .rtlsdr-toolbar {
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                justify-content: space-between;
                gap: 10px;
                background: #171f2b;
                padding: 8px 12px;
                border-radius: 6px;
                border: 1px solid #263345;
            }
            .rtlsdr-group {
                display: flex;
                align-items: center;
                gap: 6px;
            }
            .rtlsdr-badge {
                font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
                font-size: 14px;
                font-weight: 700;
                color: #38bdf8;
                background: #0a0f16;
                padding: 4px 10px;
                border-radius: 4px;
                border: 1px solid #1e293b;
                letter-spacing: 0.5px;
            }
            .rtlsdr-peak-badge {
                font-size: 12px;
                color: #fbbf24;
                font-family: "SFMono-Regular", Consolas, monospace;
                padding: 4px 8px;
                background: #1f1d16;
                border-radius: 4px;
                border: 1px solid #3b331e;
            }
            .rtlsdr-btn {
                background: #2563eb;
                color: #fff;
                border: none;
                border-radius: 4px;
                padding: 4px 10px;
                font-size: 12px;
                font-weight: 600;
                cursor: pointer;
                transition: background 0.15s ease;
            }
            .rtlsdr-btn:hover {
                background: #1d4ed8;
            }
            .rtlsdr-btn-secondary {
                background: #2d3748;
                color: #cbd5e1;
            }
            .rtlsdr-btn-secondary:hover {
                background: #3b4758;
                color: #fff;
            }
            .rtlsdr-btn-active {
                background: #0284c7;
                color: #fff;
            }
            .rtlsdr-input {
                background: #090e17;
                border: 1px solid #334155;
                color: #f8fafc;
                padding: 4px 8px;
                border-radius: 4px;
                width: 75px;
                font-family: monospace;
                font-size: 13px;
                text-align: right;
            }
            .rtlsdr-label {
                font-size: 11px;
                color: #94a3b8;
                font-weight: 600;
                text-transform: uppercase;
                letter-spacing: 0.5px;
            }
            .rtlsdr-slider {
                width: 65px;
                cursor: pointer;
                accent-color: #38bdf8;
            }
            .rtlsdr-select {
                background: #090e17;
                border: 1px solid #334155;
                color: #f8fafc;
                padding: 3px 6px;
                border-radius: 4px;
                font-size: 12px;
                cursor: pointer;
            }
            .rtlsdr-section-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                padding: 2px 4px;
                font-size: 11px;
                font-weight: 700;
                color: #94a3b8;
                text-transform: uppercase;
                letter-spacing: 0.6px;
            }
            .rtlsdr-section-title {
                display: flex;
                align-items: center;
                gap: 6px;
                color: #38bdf8;
            }
            .rtlsdr-legend-wrapper {
                display: flex;
                align-items: center;
                gap: 6px;
                font-size: 10px;
                font-family: monospace;
                color: #64748b;
            }
            .rtlsdr-gradient-bar {
                width: 90px;
                height: 8px;
                border-radius: 2px;
                background: linear-gradient(to right, #0a0f28, #0022cc, #00cccc, #66dd00, #ffcc00, #ff2200, #ffffff);
                border: 1px solid #334155;
            }
            .rtlsdr-canvas-container {
                position: relative;
                width: 100%;
                display: flex;
                flex-direction: column;
                gap: 6px;
            }
            .rtlsdr-canvas {
                width: 100%;
                display: block;
                border-radius: 4px;
                background: #06090e;
                border: 1px solid #1e293b;
                cursor: crosshair;
            }
            .rtlsdr-tooltip {
                position: absolute;
                display: none;
                pointer-events: none;
                background: rgba(15, 23, 42, 0.95);
                border: 1px solid #38bdf8;
                border-radius: 4px;
                padding: 4px 8px;
                font-family: monospace;
                font-size: 11px;
                color: #f8fafc;
                white-space: nowrap;
                z-index: 100;
                box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.5);
            }
            .rtlsdr-pulse-live {
                display: inline-block;
                width: 8px;
                height: 8px;
                border-radius: 50%;
                background: #22c55e;
                box-shadow: 0 0 6px #22c55e;
                margin-right: 4px;
            }
            .rtlsdr-pulse-paused {
                display: inline-block;
                width: 8px;
                height: 8px;
                border-radius: 50%;
                background: #f59e0b;
                box-shadow: 0 0 6px #f59e0b;
                margin-right: 4px;
            }
        </style>
        <div class="rtlsdr-wrapper">
            <!-- Top Controls Toolbar -->
            <div class="rtlsdr-toolbar">
                <div class="rtlsdr-group">
                    <span id="liveStatus"><span class="rtlsdr-pulse-paused"></span><span style="font-size:12px; font-weight:700; color:#fbbf24;">CONNECTING</span></span>
                    <span id="freqBadge" class="rtlsdr-badge">100.000 MHz</span>
                    <input type="number" id="tuneInput" class="rtlsdr-input" step="0.01" value="100.00" title="Center Frequency (MHz)" />
                    <button id="tuneBtn" class="rtlsdr-btn">Tune</button>
                    <button id="stepMinus1M" class="rtlsdr-btn rtlsdr-btn-secondary" title="Down 1 MHz">-1M</button>
                    <button id="stepMinus100k" class="rtlsdr-btn rtlsdr-btn-secondary" title="Down 100 kHz">-100k</button>
                    <button id="stepPlus100k" class="rtlsdr-btn rtlsdr-btn-secondary" title="Up 100 kHz">+100k</button>
                    <button id="stepPlus1M" class="rtlsdr-btn rtlsdr-btn-secondary" title="Up 1 MHz">+1M</button>

                    <span class="rtlsdr-label" style="margin-left:8px;">Span:</span>
                    <span class="rtlsdr-badge" style="font-size:12px; font-weight:600; padding:2px 8px; color:#cbd5e1;">2 MHz</span>
                </div>

                <div class="rtlsdr-group">
                    <span id="peakBadge" class="rtlsdr-peak-badge">Peak: --- MHz (--- dB)</span>
                    <span id="fpsBadge" style="font-size:11px; font-family:monospace; color:#64748b;">0 fps</span>
                </div>

                <div class="rtlsdr-group">
                    <button id="autoDbBtn" class="rtlsdr-btn rtlsdr-btn-active" title="Automatically adjust dB range to signal floor">Auto dB</button>
                    <span class="rtlsdr-label">Min:</span>
                    <input type="range" id="minDbSlider" class="rtlsdr-slider" min="-140" max="-40" value="-100" title="Min dB (Floor)" />
                    <span id="minDbVal" style="font-size:11px; width:34px; font-family:monospace;">-100</span>

                    <span class="rtlsdr-label">Max:</span>
                    <input type="range" id="maxDbSlider" class="rtlsdr-slider" min="-60" max="10" value="-20" title="Max dB (Ceiling)" />
                    <span id="maxDbVal" style="font-size:11px; width:28px; font-family:monospace;">-20</span>

                    <span class="rtlsdr-label">Avg:</span>
                    <select id="avgSelect" class="rtlsdr-select">
                        <option value="1">Off</option>
                        <option value="2" selected>2x</option>
                        <option value="4">4x</option>
                        <option value="8">8x</option>
                    </select>

                    <span class="rtlsdr-label">Speed:</span>
                    <select id="speedSelect" class="rtlsdr-select">
                        <option value="1">1x</option>
                        <option value="2" selected>2x</option>
                        <option value="4">4x</option>
                    </select>

                    <button id="pauseBtn" class="rtlsdr-btn rtlsdr-btn-secondary">Pause</button>
                </div>
            </div>

            <!-- Audio Demodulator Toolbar -->
            <div class="rtlsdr-toolbar" style="background: #111a24; border-color: #1e293b; padding: 6px 12px;">
                <div class="rtlsdr-group">
                    <span class="rtlsdr-label" style="color: #38bdf8;">Demod:</span>
                    <select id="audioModeSelect" class="rtlsdr-select" style="font-weight: 600;">
                        <option value="OFF">Audio Muted</option>
                        <option value="WFM">WFM (Broadcast FM 88-108 MHz)</option>
                        <option value="NFM">NFM (Ham/Marine/VHF/NOAA)</option>
                        <option value="AM">AM (Airband 118-137 / HF)</option>
                        <option value="USB">USB (Upper Sideband / HF)</option>
                        <option value="LSB">LSB (Lower Sideband / HF)</option>
                        <option value="CW">CW (Continuous Wave / Morse)</option>
                    </select>
                    <button id="audioListenBtn" class="rtlsdr-btn rtlsdr-btn-secondary" title="Start/Stop In-Browser Audio Playback">🔈 Listen</button>
                    <span id="audioStatusBadge" style="font-size: 11px; font-family: monospace; color: #64748b;">Muted</span>
                </div>

                <div class="rtlsdr-group">
                    <span class="rtlsdr-label">Vol:</span>
                    <input type="range" id="volumeSlider" class="rtlsdr-slider" min="0" max="100" value="80" title="Audio Volume (0-100%)" />
                    <span id="volumeVal" style="font-size: 11px; width: 28px; font-family: monospace;">80%</span>

                    <span class="rtlsdr-label" title="Squelch Threshold in dBFS (Mutes static when below threshold)">Sql:</span>
                    <input type="range" id="squelchSlider" class="rtlsdr-slider" min="-100" max="-30" value="-65" title="Squelch Cutoff dBFS" />
                    <span id="squelchVal" style="font-size: 11px; width: 44px; font-family: monospace;">-65 dB</span>
                </div>
            </div>

            <!-- Upper Canvas: RF Spectrum Analyzer -->
            <div class="rtlsdr-section-header">
                <div class="rtlsdr-section-title">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.5"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>
                    <span>Upper View: RF Spectrum Analyzer (Amplitude vs Frequency)</span>
                </div>
                <span id="spectrumRangeText" style="font-size:11px; font-family:monospace; color:#94a3b8;">Span: 2.048 MHz | 1024 bins</span>
            </div>
            <div class="rtlsdr-canvas-container" id="spectrumContainer">
                <canvas id="spectrumCanvas" class="rtlsdr-canvas" width="800" height="220"></canvas>
                <div id="tooltip" class="rtlsdr-tooltip"></div>
            </div>

            <!-- Lower Canvas: Waterfall Spectrogram -->
            <div class="rtlsdr-section-header" style="margin-top: 4px;">
                <div class="rtlsdr-section-title">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.5"><path d="M2 12h20M2 6h20M2 18h20"></path></svg>
                    <span>Lower View: Waterfall Spectrogram (History vs Frequency)</span>
                </div>
                <div class="rtlsdr-legend-wrapper">
                    <span id="legendMin">-100 dB</span>
                    <div class="rtlsdr-gradient-bar"></div>
                    <span id="legendMax">-20 dB</span>
                </div>
            </div>
            <div class="rtlsdr-canvas-container" id="waterfallContainer">
                <canvas id="waterfallCanvas" class="rtlsdr-canvas" width="800" height="280"></canvas>
            </div>
        </div>
    `;

    // DOM Elements
    var freqBadge = container.querySelector("#freqBadge");
    var peakBadge = container.querySelector("#peakBadge");
    var fpsBadge = container.querySelector("#fpsBadge");
    var tuneInput = container.querySelector("#tuneInput");
    var tuneBtn = container.querySelector("#tuneBtn");
    var stepMinus1M = container.querySelector("#stepMinus1M");
    var stepMinus100k = container.querySelector("#stepMinus100k");
    var stepPlus100k = container.querySelector("#stepPlus100k");
    var stepPlus1M = container.querySelector("#stepPlus1M");
    var autoDbBtn = container.querySelector("#autoDbBtn");
    var minDbSlider = container.querySelector("#minDbSlider");
    var minDbVal = container.querySelector("#minDbVal");
    var maxDbSlider = container.querySelector("#maxDbSlider");
    var maxDbVal = container.querySelector("#maxDbVal");
    var legendMin = container.querySelector("#legendMin");
    var legendMax = container.querySelector("#legendMax");
    var avgSelect = container.querySelector("#avgSelect");
    var speedSelect = container.querySelector("#speedSelect");
    var pauseBtn = container.querySelector("#pauseBtn");
    var liveStatus = container.querySelector("#liveStatus");
    var spectrumContainer = container.querySelector("#spectrumContainer");
    var waterfallContainer = container.querySelector("#waterfallContainer");
    var spectrumCanvas = container.querySelector("#spectrumCanvas");
    var waterfallCanvas = container.querySelector("#waterfallCanvas");
    var tooltip = container.querySelector("#tooltip");
    var spectrumRangeText = container.querySelector("#spectrumRangeText");

    // Audio DOM Elements
    var audioModeSelect = container.querySelector("#audioModeSelect");
    var audioListenBtn = container.querySelector("#audioListenBtn");
    var audioStatusBadge = container.querySelector("#audioStatusBadge");
    var volumeSlider = container.querySelector("#volumeSlider");
    var volumeVal = container.querySelector("#volumeVal");
    var squelchSlider = container.querySelector("#squelchSlider");
    var squelchVal = container.querySelector("#squelchVal");

    var specCtx = spectrumCanvas.getContext("2d");
    var wfCtx = waterfallCanvas.getContext("2d");

    // Offscreen 1-row canvas for fast waterfall row rendering
    var rowCanvas = document.createElement("canvas");
    rowCanvas.width = 800;
    rowCanvas.height = 1;
    var rowCtx = rowCanvas.getContext("2d");

    // Web Audio API Engine
    var audioCtx = null;
    var audioGainNode = null;
    var nextAudioStartTime = 0;
    var isListening = false;

    function initAudio() {
        if (!audioCtx) {
            var AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (AudioContextClass) {
                audioCtx = new AudioContextClass({ sampleRate: 48000 });
                audioGainNode = audioCtx.createGain();
                var vol = parseInt(volumeSlider.value, 10) / 100.0;
                audioGainNode.gain.setValueAtTime(vol, audioCtx.currentTime);
                audioGainNode.connect(audioCtx.destination);
            }
        }
        if (audioCtx && audioCtx.state === "suspended") {
            audioCtx.resume();
        }
    }

    audioListenBtn.addEventListener("click", function() {
        initAudio();
        isListening = !isListening;
        if (isListening) {
            audioListenBtn.textContent = "🔊 Playing";
            audioListenBtn.classList.add("rtlsdr-btn-active");
            audioListenBtn.classList.remove("rtlsdr-btn-secondary");
            if (audioModeSelect.value === "OFF") {
                var curMhz = state.centerFreqHz / 1e6;
                if (curMhz >= 87.5 && curMhz <= 108.0) {
                    audioModeSelect.value = "WFM";
                } else if (curMhz < 24.0 || (curMhz >= 118.0 && curMhz <= 137.0)) {
                    audioModeSelect.value = "AM";
                    squelchSlider.value = -95;
                    squelchVal.textContent = "-95 dB";
                    if (self.setSquelch) self.setSquelch(-95);
                } else {
                    audioModeSelect.value = "NFM";
                }
                if (self.setAudioMode) self.setAudioMode(audioModeSelect.value);
            }
            audioStatusBadge.textContent = "48 kHz (" + audioModeSelect.value + ")";
            audioStatusBadge.style.color = "#4ade80";
        } else {
            audioListenBtn.textContent = "🔈 Listen";
            audioListenBtn.classList.remove("rtlsdr-btn-active");
            audioListenBtn.classList.add("rtlsdr-btn-secondary");
            audioStatusBadge.textContent = "Muted";
            audioStatusBadge.style.color = "#64748b";
            if (self.setAudioMode) self.setAudioMode("OFF");
            audioModeSelect.value = "OFF";
        }
    });

    audioModeSelect.addEventListener("change", function() {
        var mode = this.value;
        if (mode === "OFF") {
            isListening = false;
            audioListenBtn.textContent = "🔈 Listen";
            audioListenBtn.classList.remove("rtlsdr-btn-active");
            audioListenBtn.classList.add("rtlsdr-btn-secondary");
            audioStatusBadge.textContent = "Muted";
            audioStatusBadge.style.color = "#64748b";
        } else {
            initAudio();
            isListening = true;
            audioListenBtn.textContent = "🔊 Playing";
            audioListenBtn.classList.add("rtlsdr-btn-active");
            audioListenBtn.classList.remove("rtlsdr-btn-secondary");
            audioStatusBadge.textContent = "48 kHz (" + mode + ")";
            audioStatusBadge.style.color = "#4ade80";

            if (mode === "AM") {
                squelchSlider.value = -95;
                squelchVal.textContent = "-95 dB";
                if (self.setSquelch) self.setSquelch(-95);
            }
        }
        if (self.setAudioMode) {
            self.setAudioMode(mode);
        }
    });

    volumeSlider.addEventListener("input", function() {
        var val = parseInt(this.value, 10);
        volumeVal.textContent = val + "%";
        var volNorm = val / 100.0;
        if (audioGainNode && audioCtx) {
            audioGainNode.gain.setValueAtTime(volNorm, audioCtx.currentTime);
        }
        if (self.setAudioVolume) {
            self.setAudioVolume(volNorm);
        }
    });

    squelchSlider.addEventListener("input", function() {
        var val = parseInt(this.value, 10);
        squelchVal.textContent = val + " dB";
        if (self.setSquelch) {
            self.setSquelch(val);
        }
    });

    // Dynamic canvas sizing to match container width
    function syncCanvasWidth() {
        var rect = spectrumContainer.getBoundingClientRect();
        var width = Math.floor(rect.width) || spectrumCanvas.clientWidth || 800;
        if (width > 50 && spectrumCanvas.width !== width) {
            spectrumCanvas.width = width;
            waterfallCanvas.width = width;
            rowCanvas.width = width;
            drawSpectrum();
        }
    }

    window.addEventListener("resize", syncCanvasWidth);
    if (window.ResizeObserver) {
        new ResizeObserver(syncCanvasWidth).observe(spectrumContainer);
    }
    setTimeout(syncCanvasWidth, 50);

    // Event Listeners for UI Controls
    function doTune(mhz) {
        mhz = Math.max(0.1, Math.round(mhz * 1000.0) / 1000.0);
        tuneInput.value = mhz.toFixed(3);
        freqBadge.textContent = mhz.toFixed(3) + " MHz";
        state.centerFreqHz = Math.round(mhz * 1000000.0);

        if (mhz < 24.0) {
            if (audioModeSelect.value !== "AM") {
                audioModeSelect.value = "AM";
                squelchSlider.value = -95;
                squelchVal.textContent = "-95 dB";
                if (isListening) {
                    audioStatusBadge.textContent = "48 kHz (AM)";
                    audioStatusBadge.style.color = "#4ade80";
                    if (self.setAudioMode) self.setAudioMode("AM");
                    if (self.setSquelch) self.setSquelch(-95);
                }
            }
        }

        if (self.tuneFrequency) {
            self.tuneFrequency(mhz);
        }
    }

    tuneBtn.addEventListener("click", function() {
        var mhz = parseFloat(tuneInput.value);
        if (!isNaN(mhz)) doTune(mhz);
    });

    tuneInput.addEventListener("keydown", function(e) {
        if (e.key === "Enter") {
            var mhz = parseFloat(tuneInput.value);
            if (!isNaN(mhz)) doTune(mhz);
        }
    });

    stepMinus1M.addEventListener("click", function() {
        var cur = parseFloat(tuneInput.value) || (state.centerFreqHz / 1e6);
        doTune(cur - 1.0);
    });
    stepMinus100k.addEventListener("click", function() {
        var cur = parseFloat(tuneInput.value) || (state.centerFreqHz / 1e6);
        doTune(cur - 0.1);
    });
    stepPlus100k.addEventListener("click", function() {
        var cur = parseFloat(tuneInput.value) || (state.centerFreqHz / 1e6);
        doTune(cur + 0.1);
    });
    stepPlus1M.addEventListener("click", function() {
        var cur = parseFloat(tuneInput.value) || (state.centerFreqHz / 1e6);
        doTune(cur + 1.0);
    });

    autoDbBtn.addEventListener("click", function() {
        state.autoDb = !state.autoDb;
        if (state.autoDb) {
            autoDbBtn.classList.add("rtlsdr-btn-active");
            autoDbBtn.classList.remove("rtlsdr-btn-secondary");
        } else {
            autoDbBtn.classList.remove("rtlsdr-btn-active");
            autoDbBtn.classList.add("rtlsdr-btn-secondary");
        }
    });

    minDbSlider.addEventListener("input", function() {
        state.autoDb = false;
        autoDbBtn.classList.remove("rtlsdr-btn-active");
        autoDbBtn.classList.add("rtlsdr-btn-secondary");
        state.minDb = parseInt(this.value, 10);
        minDbVal.textContent = state.minDb;
        legendMin.textContent = state.minDb + " dB";
        drawSpectrum();
    });

    maxDbSlider.addEventListener("input", function() {
        state.autoDb = false;
        autoDbBtn.classList.remove("rtlsdr-btn-active");
        autoDbBtn.classList.add("rtlsdr-btn-secondary");
        state.maxDb = parseInt(this.value, 10);
        maxDbVal.textContent = state.maxDb;
        legendMax.textContent = state.maxDb + " dB";
        drawSpectrum();
    });

    avgSelect.addEventListener("change", function() {
        state.averaging = parseInt(this.value, 10);
    });

    speedSelect.addEventListener("change", function() {
        state.waterfallSpeed = parseInt(this.value, 10);
    });

    pauseBtn.addEventListener("click", function() {
        state.paused = !state.paused;
        if (state.paused) {
            pauseBtn.textContent = "Resume";
            pauseBtn.style.background = "#d97706";
            liveStatus.innerHTML = '<span class="rtlsdr-pulse-paused"></span><span style="font-size:12px; font-weight:700; color:#fbbf24;">PAUSED</span>';
        } else {
            pauseBtn.textContent = "Pause";
            pauseBtn.style.background = "#2d3748";
            liveStatus.innerHTML = '<span class="rtlsdr-pulse-live"></span><span style="font-size:12px; font-weight:700; color:#4ade80;">LIVE</span>';
        }
    });

    // Mouse Tracking / Crosshair / Click to Tune
    function handleMouseMove(e, canvas) {
        var rect = canvas.getBoundingClientRect();
        var x = e.clientX - rect.left;
        var y = e.clientY - rect.top;
        state.mousePos = { x: x, y: y, canvasType: canvas === spectrumCanvas ? "spectrum" : "waterfall" };
        state.isHovering = true;

        var spanHz = state.sampleRateHz;
        var startHz = state.centerFreqHz - spanHz / 2.0;
        var freqAtX = startHz + (x / rect.width) * spanHz;
        var freqMhz = (freqAtX / 1e6).toFixed(4);

        var dbRange = Math.max(1, state.maxDb - state.minDb);
        var powerAtY = state.maxDb - (y / rect.height) * dbRange;

        tooltip.style.display = "block";
        tooltip.style.left = Math.min(x + 12, rect.width - 130) + "px";
        tooltip.style.top = (canvas === spectrumCanvas ? y + 8 : y + 250) + "px";
        if (canvas === spectrumCanvas) {
            tooltip.innerHTML = freqMhz + " MHz<br/>" + powerAtY.toFixed(1) + " dB";
        } else {
            tooltip.innerHTML = freqMhz + " MHz";
        }

        drawSpectrum();
    }

    spectrumCanvas.addEventListener("mousemove", function(e) { handleMouseMove(e, spectrumCanvas); });
    waterfallCanvas.addEventListener("mousemove", function(e) { handleMouseMove(e, waterfallCanvas); });

    function handleMouseLeave() {
        state.isHovering = false;
        state.mousePos = null;
        tooltip.style.display = "none";
        drawSpectrum();
    }
    spectrumCanvas.addEventListener("mouseleave", handleMouseLeave);
    waterfallCanvas.addEventListener("mouseleave", handleMouseLeave);

    // Click to tune
    function handleClickToTune(e, canvas) {
        var rect = canvas.getBoundingClientRect();
        var x = e.clientX - rect.left;
        var spanHz = state.sampleRateHz;
        var startHz = state.centerFreqHz - spanHz / 2.0;
        var freqAtX = startHz + (x / rect.width) * spanHz;
        var freqMhz = freqAtX / 1e6;
        doTune(freqMhz);
    }
    spectrumCanvas.addEventListener("click", function(e) { handleClickToTune(e, spectrumCanvas); });
    waterfallCanvas.addEventListener("click", function(e) { handleClickToTune(e, waterfallCanvas); });

    // Status updates from server
    self.setStatus = function(status, message) {
        if (status === "STOPPED") {
            liveStatus.innerHTML = '<span class="rtlsdr-pulse-paused"></span><span style="font-size:12px; font-weight:700; color:#ef4444;">STOPPED</span>';
            state.statusMsg = message || "Receiver stopped. Click Start above.";
        } else if (status === "WAITING") {
            liveStatus.innerHTML = '<span class="rtlsdr-pulse-paused"></span><span style="font-size:12px; font-weight:700; color:#fbbf24;">WAITING</span>';
            state.statusMsg = message || "Waiting for RF data...";
        }
        drawSpectrum();
    };

    // Drawing Functions
    function drawSpectrum() {
        syncCanvasWidth();
        var w = spectrumCanvas.width;
        var h = spectrumCanvas.height;
        if (w <= 0 || h <= 0) return;

        specCtx.fillStyle = "#06090e";
        specCtx.fillRect(0, 0, w, h);

        var minDb = state.minDb;
        var maxDb = state.maxDb;
        var dbRange = Math.max(1, maxDb - minDb);

        // Draw horizontal dB grid lines
        specCtx.strokeStyle = "#172033";
        specCtx.lineWidth = 1;
        specCtx.font = "bold 11px monospace";
        specCtx.fillStyle = "#f8fafc";

        var stepDb = dbRange <= 40 ? 10 : (dbRange <= 80 ? 20 : 30);
        var startDb = Math.ceil(minDb / stepDb) * stepDb;
        for (var db = startDb; db <= maxDb; db += stepDb) {
            var y = h - ((db - minDb) / dbRange) * (h - 24) - 12;
            specCtx.beginPath();
            specCtx.moveTo(0, y);
            specCtx.lineTo(w, y);
            specCtx.stroke();
            specCtx.fillText(db + " dB", 6, y - 3);
        }

        // Draw vertical frequency grid lines
        var spanHz = state.sampleRateHz;
        var startFreqHz = state.centerFreqHz - (spanHz / 2.0);
        var numFreqDivs = 8;
        for (var f = 0; f <= numFreqDivs; f++) {
            var x = (f / numFreqDivs) * w;
            specCtx.beginPath();
            specCtx.moveTo(x, 0);
            specCtx.lineTo(x, h);
            specCtx.stroke();

            var freqAtX = (startFreqHz + (f / numFreqDivs) * spanHz) / 1e6;
            specCtx.fillText(freqAtX.toFixed(3) + " MHz", Math.min(x + 4, w - 65), h - 4);
        }

        // Center frequency line (yellow dash)
        var centerX = w / 2.0;
        specCtx.save();
        specCtx.strokeStyle = "rgba(251, 191, 36, 0.4)";
        specCtx.setLineDash([4, 4]);
        specCtx.beginPath();
        specCtx.moveTo(centerX, 0);
        specCtx.lineTo(centerX, h);
        specCtx.stroke();
        specCtx.restore();

        // If stopped / waiting / no bins, show status overlay
        if (state.statusMsg || !state.smoothedBins) {
            specCtx.fillStyle = "#94a3b8";
            specCtx.font = "14px -apple-system, sans-serif";
            specCtx.textAlign = "center";
            specCtx.fillText(state.statusMsg || "Awaiting receiver RF data...", w / 2, h / 2);
            specCtx.textAlign = "left";
            return;
        }

        var bins = state.smoothedBins;
        var numBins = bins.length;
        if (numBins < 2) return;

        var denom = numBins - 1;

        // Draw Peak-Hold line (amber/orange)
        if (state.peakHoldBins) {
            specCtx.beginPath();
            for (var i = 0; i < numBins; i++) {
                var x = (i / denom) * w;
                var dbVal = Math.max(minDb, Math.min(maxDb, state.peakHoldBins[i]));
                var y = h - ((dbVal - minDb) / dbRange) * (h - 24) - 12;
                if (i === 0) specCtx.moveTo(x, y);
                else specCtx.lineTo(x, y);
            }
            specCtx.strokeStyle = "rgba(245, 158, 11, 0.65)";
            specCtx.lineWidth = 1.0;
            specCtx.stroke();
        }

        // Draw Filled RF Spectrum polygon
        specCtx.beginPath();
        specCtx.moveTo(0, h);
        for (var i = 0; i < numBins; i++) {
            var x = (i / denom) * w;
            var dbVal = Math.max(minDb, Math.min(maxDb, bins[i]));
            var y = h - ((dbVal - minDb) / dbRange) * (h - 24) - 12;
            specCtx.lineTo(x, y);
        }
        specCtx.lineTo(w, h);
        specCtx.closePath();

        // Fill with cyan gradient
        var grad = specCtx.createLinearGradient(0, 0, 0, h);
        grad.addColorStop(0, "rgba(56, 189, 248, 0.50)");
        grad.addColorStop(0.5, "rgba(14, 165, 233, 0.20)");
        grad.addColorStop(1, "rgba(14, 165, 233, 0.0)");
        specCtx.fillStyle = grad;
        specCtx.fill();

        // Stroke line in bright neon cyan with glow
        specCtx.save();
        specCtx.shadowColor = "#00f5d4";
        specCtx.shadowBlur = 4;
        specCtx.beginPath();
        for (var i = 0; i < numBins; i++) {
            var x = (i / denom) * w;
            var dbVal = Math.max(minDb, Math.min(maxDb, bins[i]));
            var y = h - ((dbVal - minDb) / dbRange) * (h - 24) - 12;
            if (i === 0) specCtx.moveTo(x, y);
            else specCtx.lineTo(x, y);
        }
        specCtx.strokeStyle = "#00f5d4";
        specCtx.lineWidth = 2.0;
        specCtx.stroke();
        specCtx.restore();

        // Crosshair if hovering over spectrum
        if (state.isHovering && state.mousePos && state.mousePos.canvasType === "spectrum") {
            var mx = state.mousePos.x;
            var my = state.mousePos.y;
            specCtx.save();
            specCtx.strokeStyle = "rgba(56, 189, 248, 0.75)";
            specCtx.setLineDash([2, 3]);
            specCtx.beginPath();
            specCtx.moveTo(mx, 0); specCtx.lineTo(mx, h);
            specCtx.moveTo(0, my); specCtx.lineTo(w, my);
            specCtx.stroke();
            specCtx.restore();
        }
    }

    function updateWaterfall(bins) {
        var w = waterfallCanvas.width;
        var h = waterfallCanvas.height;
        if (w <= 0 || h <= 0 || !bins || bins.length === 0) return;

        var speed = state.waterfallSpeed;

        // Shift existing waterfall down by 'speed' pixels
        wfCtx.drawImage(waterfallCanvas, 0, 0, w, h - speed, 0, speed, w, h - speed);

        // Generate 1 new row of pixel data using direct RGBA bytes
        if (rowCanvas.width !== w) {
            rowCanvas.width = w;
        }
        var rowImgData = rowCtx.createImageData(w, 1);
        var data = rowImgData.data;

        var minDb = state.minDb;
        var maxDb = state.maxDb;
        var range = Math.max(1, maxDb - minDb);
        var numBins = bins.length;

        for (var x = 0; x < w; x++) {
            var binIdx = Math.floor((x / w) * numBins);
            var val = bins[binIdx];
            var norm = (val - minDb) / range;
            if (norm < 0) norm = 0;
            if (norm > 1) norm = 1;

            var cIdx = Math.floor(norm * 255);
            var pIdx = cIdx * 3;
            var dIdx = x * 4;

            data[dIdx + 0] = palette[pIdx + 0];
            data[dIdx + 1] = palette[pIdx + 1];
            data[dIdx + 2] = palette[pIdx + 2];
            data[dIdx + 3] = 255;
        }

        rowCtx.putImageData(rowImgData, 0, 0);

        // Draw new row stretched across 'speed' pixels at y = 0
        wfCtx.drawImage(rowCanvas, 0, 0, w, 1, 0, 0, w, speed);
    }

    // Play incoming audio chunks via Web Audio API
    self.playAudioChunk = function(base64Pcm) {
        if (!isListening || !audioCtx || !base64Pcm || audioCtx.state !== "running") return;

        try {
            var binaryStr = atob(base64Pcm);
            var len = binaryStr.length;
            if (len < 2) return;

            var bytes = new Uint8Array(len);
            for (var i = 0; i < len; i++) {
                bytes[i] = binaryStr.charCodeAt(i);
            }

            var int16Samples = new Int16Array(bytes.buffer);
            var numSamples = int16Samples.length;
            if (numSamples === 0) return;

            var audioBuf = audioCtx.createBuffer(1, numSamples, 48000);
            var channelData = audioBuf.getChannelData(0);
            for (var i = 0; i < numSamples; i++) {
                channelData[i] = int16Samples[i] / 32768.0;
            }

            var now = audioCtx.currentTime;
            if (nextAudioStartTime < now || nextAudioStartTime > now + 0.35) {
                nextAudioStartTime = now + 0.05; // 50ms buffer lead
            }

            var src = audioCtx.createBufferSource();
            src.buffer = audioBuf;
            src.connect(audioGainNode);
            src.start(nextAudioStartTime);
            nextAudioStartTime += audioBuf.duration;

        } catch (e) {
            // ignore chunk decode error
        }
    };

    // Server-to-Client RPC callback
    var lastFpsTime = Date.now();
    var framesThisSec = 0;

    self.updateSpectrum = function(centerFreqHz, sampleRateHz, dbArray) {
        if (state.paused) return;

        state.statusMsg = null;
        if (!dbArray) return;

        // Support both native Array and Object dictionary
        var binsArray = Array.isArray(dbArray) ? dbArray : Object.values(dbArray);
        var numBins = binsArray.length;
        if (!numBins || numBins === 0) return;

        state.frameCount++;
        framesThisSec++;
        var now = Date.now();
        if (now - lastFpsTime >= 1000) {
            fpsBadge.textContent = framesThisSec + " fps";
            framesThisSec = 0;
            lastFpsTime = now;
        }

        liveStatus.innerHTML = '<span class="rtlsdr-pulse-live"></span><span style="font-size:12px; font-weight:700; color:#4ade80;">LIVE</span>';

        state.centerFreqHz = centerFreqHz;
        state.sampleRateHz = sampleRateHz;

        var mhz = centerFreqHz / 1e6;
        var curText = mhz.toFixed(3) + " MHz";
        if (freqBadge.textContent !== curText) {
            freqBadge.textContent = curText;
            if (document.activeElement !== tuneInput) {
                tuneInput.value = mhz.toFixed(3);
            }
        }

        var spanMhz = (sampleRateHz / 1e6).toFixed(3);
        spectrumRangeText.textContent = "Span: " + spanMhz + " MHz (" + numBins + " bins)";


        if (!state.smoothedBins || state.smoothedBins.length !== numBins) {
            state.smoothedBins = new Float32Array(numBins);
            state.peakHoldBins = new Float32Array(numBins);
            for (var k = 0; k < numBins; k++) {
                var v = Number(binsArray[k]);
                if (isNaN(v) || !isFinite(v)) v = -100.0;
                state.smoothedBins[k] = v;
                state.peakHoldBins[k] = v;
            }
        }

        // Exponential smoothing & Peak tracking & Auto dB calculation
        var alpha = 1.0 / state.averaging;
        var maxPower = -999;
        var minPower = 999;
        var maxIdx = 0;

        for (var i = 0; i < numBins; i++) {
            var val = Number(binsArray[i]);
            if (isNaN(val) || !isFinite(val)) {
                val = -100.0;
            }

            state.smoothedBins[i] = (1.0 - alpha) * state.smoothedBins[i] + alpha * val;

            // Peak hold
            if (val > state.peakHoldBins[i]) {
                state.peakHoldBins[i] = val;
            } else {
                state.peakHoldBins[i] -= state.peakHoldDecay;
            }

            if (val > maxPower) {
                maxPower = val;
                maxIdx = i;
            }
            if (val < minPower) {
                minPower = val;
            }
        }

        // Dynamic Auto-ranging for minDb / maxDb with strict RF sanity checks
        if (state.autoDb && minPower < 500 && maxPower > -500 && minPower <= maxPower) {
            var targetMin = Math.max(-140, Math.min(-30, Math.floor(minPower - 6)));
            var targetMax = Math.max(targetMin + 15, Math.min(10, Math.ceil(maxPower + 10)));

            if (state.minDb > 0 || state.minDb < -150) {
                state.minDb = targetMin;
            } else {
                state.minDb = Math.round(0.85 * state.minDb + 0.15 * targetMin);
            }

            if (state.maxDb > 20 || state.maxDb < -80) {
                state.maxDb = targetMax;
            } else {
                state.maxDb = Math.round(0.85 * state.maxDb + 0.15 * targetMax);
            }

            // Clamp to slider bounds
            state.minDb = Math.max(-140, Math.min(-40, state.minDb));
            state.maxDb = Math.max(-60, Math.min(10, state.maxDb));
            if (state.maxDb <= state.minDb) state.maxDb = state.minDb + 20;

            minDbSlider.value = state.minDb;
            minDbVal.textContent = state.minDb;
            legendMin.textContent = state.minDb + " dB";

            maxDbSlider.value = state.maxDb;
            maxDbVal.textContent = state.maxDb;
            legendMax.textContent = state.maxDb + " dB";
        }

        state.lastBins = state.smoothedBins;

        // Update Peak signal readout
        var spanHz = sampleRateHz;
        var startHz = centerFreqHz - spanHz / 2.0;
        var peakFreqHz = startHz + (maxIdx / numBins) * spanHz;
        peakBadge.textContent = "Peak: " + (peakFreqHz / 1e6).toFixed(3) + " MHz (" + maxPower.toFixed(1) + " dB)";

        // Draw spectrum & waterfall
        drawSpectrum();
        updateWaterfall(state.smoothedBins);
    };

    // Periodic client polling to guarantee continuous data flow
    var pollTimer = setInterval(function() {
        if (!state.paused && self.requestSpectrum) {
            self.requestSpectrum();
        }
    }, 100);

    // Initial draw
    drawSpectrum();
};
