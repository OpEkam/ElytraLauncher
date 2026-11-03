package net.kdt.pojavlaunch.utils;

import java.nio.ByteBuffer;

/**
 * Converts an OSMesa/Android RGBX_8888 framebuffer snapshot to planar
 * YUV 4:2:0 (I420), suitable for encoders that accept byte-buffer input.
 *
 * The source must contain tightly-addressable RGBX pixels with the supplied
 * row stride (in pixels). The caller owns synchronization and must ensure the
 * source is a stable snapshot for the duration of this call.
 */
public final class OsmesaFrameConverter {
    private OsmesaFrameConverter() {}

    /**
     * Writes I420 bytes to {@code destination}: Y plane, then U, then V.
     * Width and height must be even. The destination's current position is
     * advanced by width * height * 3 / 2 bytes.
     */
    public static void rgbxToI420(ByteBuffer source, int width, int height,
                                  int rowStridePixels, ByteBuffer destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination are required");
        }
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0
                || rowStridePixels < width) {
            throw new IllegalArgumentException("Invalid even frame dimensions or row stride");
        }

        final int requiredSource = (height - 1) * rowStridePixels * 4 + width * 4;
        final int ySize = width * height;
        final int chromaSize = ySize / 4;
        final int requiredOutput = ySize + 2 * chromaSize;
        if (source.remaining() < requiredSource || destination.remaining() < requiredOutput) {
            throw new IllegalArgumentException("Framebuffer or I420 destination is too small");
        }

        final int sourceBase = source.position();
        final int outputBase = destination.position();
        final int yBase = outputBase;
        final int uBase = yBase + ySize;
        final int vBase = uBase + chromaSize;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = sourceBase + (y * rowStridePixels + x) * 4;
                int r = source.get(pixel) & 0xff;
                int g = source.get(pixel + 1) & 0xff;
                int b = source.get(pixel + 2) & 0xff;
                destination.put(yBase + y * width + x, clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
            }
        }

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int pixel = sourceBase + ((y + dy) * rowStridePixels + x + dx) * 4;
                        r += source.get(pixel) & 0xff;
                        g += source.get(pixel + 1) & 0xff;
                        b += source.get(pixel + 2) & 0xff;
                    }
                }
                r = (r + 2) >> 2;
                g = (g + 2) >> 2;
                b = (b + 2) >> 2;
                int chromaIndex = (y / 2) * (width / 2) + x / 2;
                destination.put(uBase + chromaIndex,
                        clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                destination.put(vBase + chromaIndex,
                        clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
            }
        }
        destination.position(outputBase + requiredOutput);
    }

    private static byte clamp(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }
}
