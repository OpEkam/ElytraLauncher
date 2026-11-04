package net.kdt.pojavlaunch.utils;

import android.graphics.Bitmap;

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


    /** Converts an ARGB bitmap into planar I420 for the video encoder. */
    public static void bitmapToI420(Bitmap bitmap, int width, int height,
                                    ByteBuffer destination) {
        if (bitmap == null || destination == null || width < 2 || height < 2
                || (width & 1) != 0 || (height & 1) != 0
                || bitmap.getWidth() < width || bitmap.getHeight() < height) {
            throw new IllegalArgumentException("Invalid bitmap or I420 dimensions");
        }
        int ySize = width * height;
        int chromaSize = ySize / 4;
        int requiredOutput = ySize + 2 * chromaSize;
        if (destination.remaining() < requiredOutput) {
            throw new IllegalArgumentException("I420 destination is too small");
        }
        int[] row0 = new int[width];
        int[] row1 = new int[width];
        int base = destination.position();
        int uBase = base + ySize;
        int vBase = uBase + chromaSize;

        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row0, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int pixel = row0[x];
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                destination.put(base + y * width + x,
                        clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
            }
        }

        for (int y = 0; y < height; y += 2) {
            bitmap.getPixels(row0, 0, width, 0, y, width, 1);
            bitmap.getPixels(row1, 0, width, 0, y + 1, width, 1);
            for (int x = 0; x < width; x += 2) {
                int r = 0, g = 0, b = 0;
                int p0 = row0[x], p1 = row0[x + 1];
                int p2 = row1[x], p3 = row1[x + 1];
                int[] pixels = {p0, p1, p2, p3};
                for (int pixel : pixels) {
                    r += (pixel >> 16) & 0xff;
                    g += (pixel >> 8) & 0xff;
                    b += pixel & 0xff;
                }
                r = (r + 2) >> 2;
                g = (g + 2) >> 2;
                b = (b + 2) >> 2;
                int index = (y / 2) * (width / 2) + x / 2;
                destination.put(uBase + index,
                        clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                destination.put(vBase + index,
                        clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
            }
        }
        destination.position(base + requiredOutput);
    }

    private static byte clamp(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }
}
