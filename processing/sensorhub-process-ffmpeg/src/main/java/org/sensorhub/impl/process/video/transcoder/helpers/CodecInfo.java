package org.sensorhub.impl.process.video.transcoder.helpers;

public class CodecInfo {
    private FullCodecEnum codec;
    private FullPixelEnum pixelFmt;

    public CodecInfo(String name) {
        try {
            this.codec = Enum.valueOf(FullCodecEnum.class, name);
            // YUVJ works best here
            if (this.codec == FullCodecEnum.MJPEG)
                this.pixelFmt = FullPixelEnum.YUVJ420P;
            else
                this.pixelFmt = FullPixelEnum.YUV420P;

        } catch (Exception e) {
            this.codec = FullCodecEnum.RAWVIDEO;
            this.pixelFmt = Enum.valueOf(FullPixelEnum.class, name);
        }
    }

    public CodecInfo(FullCodecEnum codec, FullPixelEnum pixelFmt) {
        this.codec = codec;
        this.pixelFmt = pixelFmt;
    }

    public FullCodecEnum getCodec() {
        return codec;
    }

    public FullPixelEnum getPixelFmt() {
        return pixelFmt;
    }
}
