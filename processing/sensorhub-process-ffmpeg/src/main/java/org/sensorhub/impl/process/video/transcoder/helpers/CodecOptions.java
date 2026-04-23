package org.sensorhub.impl.process.video.transcoder.helpers;
import static org.bytedeco.ffmpeg.global.avcodec.*;

public class CodecOptions {
    private int fps;
    private int bitRate;
    private int width;
    private int height;
    private int compliance;
    private String preset;
    private String tune;

    public CodecOptions(int fps, int bitRate, int width, int height, int compliance, String preset, String tune) {
        this.fps = Math.max(fps, 1);
        this.bitRate = bitRate;
        this.width = Math.max(width, 1);
        this.height = Math.max(height, 1);
        this.compliance = compliance;
        this.preset = preset;
        this.tune = tune;
    }

    public int getFps() {
        return fps;
    }

    public int getBitRate() {
        return bitRate;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getCompliance() {
        return compliance;
    }

    public String getPreset() {
        return preset;
    }

    public String getTune() {
        return tune;
    }

    public static class Builder {
        private int fps = 30;
        private int bitRate = -1;
        private int width = 1920;
        private int height = 1080;
        private int compliance = FF_COMPLIANCE_UNOFFICIAL;
        private String preset = null;
        private String tune = null;

        public CodecOptions build() {
            return new CodecOptions(fps, bitRate, width, height, compliance, preset, tune);
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setBitRate(int bitRate) {
            this.bitRate = bitRate;
            return this;
        }

        public Builder setWidth(int width) {
            this.width = width;
            return this;
        }

        public Builder setHeight(int height) {
            this.height = height;
            return this;
        }

        public Builder setCompliance(int compliance) {
            this.compliance = compliance;
            return this;
        }

        public Builder setPreset(String preset) {
            this.preset = preset;
            return this;
        }

        public Builder presetUltraFast() {
            this.preset = "ultrafast";
            return this;
        }

        public Builder setTune(String tune) {
            this.tune = tune;
            return this;
        }

        public Builder tuneZeroLatency() {
            this.tune = "zerolatency";
            return this;
        }
    }
}
