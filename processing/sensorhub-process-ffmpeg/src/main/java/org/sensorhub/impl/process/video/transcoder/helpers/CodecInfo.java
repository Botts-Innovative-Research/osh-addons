package org.sensorhub.impl.process.video.transcoder.helpers;

public record CodecInfo(FullCodecEnum codec, FullPixelEnum pixelFmt) {
    public static CodecInfo newCodecInfoFromName(String name) {
        FullCodecEnum codec;
        FullPixelEnum pixel;
        try {
            codec = Enum.valueOf(FullCodecEnum.class, name);
            if (codec == FullCodecEnum.MJPEG)
                pixel = FullPixelEnum.YUVJ420P;
            else
                pixel = FullPixelEnum.YUV420P;
        } catch (Exception e) {
            codec = FullCodecEnum.RAWVIDEO;
            pixel = Enum.valueOf(FullPixelEnum.class, name);
        }

        return new CodecInfo(codec, pixel);
    }
}
