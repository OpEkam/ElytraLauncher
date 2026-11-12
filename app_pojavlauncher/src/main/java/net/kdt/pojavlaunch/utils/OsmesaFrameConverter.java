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
        if (!JREUtils.convertRgbaToI420(source, sourceBase, width, height,
                rowStridePixels, destination, outputBase)) {
            throw new IllegalArgumentException("Native RGBA-to-I420 conversion failed");
        }
        destination.position(outputBase + requiredOutput);
    }


    /** Converts an ARGB bitmap into planar I420 for the video encoder. */
    public static void bitmapToI420(Bitmap bitmap, int width, int height,
                                    ByteBuffer destination) {
        bitmapToI420(bitmap, width, height, destination,
                new int[width], new int[width]);
    }

    /**
     * Reuses caller-owned scanline buffers to avoid allocating large temporary
     * arrays for every recorded frame. Luma and chroma are generated together
     * from pairs of rows to reduce Bitmap readbacks.
     */
    public static void bitmapToI420(Bitmap bitmap, int width, int height,
                                    ByteBuffer destination, int[] row0, int[] row1) {
        if (bitmap == null || destination == null || width < 2 || height < 2
                || (width & 1) != 0 || (height & 1) != 0
                || bitmap.getWidth() < width || bitmap.getHeight() < height
                || row0 == null || row1 == null || row0.length < width || row1.length < width) {
            throw new IllegalArgumentException("Invalid bitmap, scratch buffers, or I420 dimensions");
        }
        int ySize = width * height;
        int chromaSize = ySize / 4;
        int requiredOutput = ySize + 2 * chromaSize;
        if (destination.remaining() < requiredOutput) {
            throw new IllegalArgumentException("I420 destination is too small");
        }
        int base = destination.position();
        int uBase = base + ySize;
        int vBase = uBase + chromaSize;
        for (int y = 0; y < height; y += 2) {
            bitmap.getPixels(row0, 0, width, 0, y, width, 1);
            bitmap.getPixels(row1, 0, width, 0, y + 1, width, 1);
            for (int x = 0; x < width; x += 2) {
                int p0 = row0[x], p1 = row0[x + 1];
                int p2 = row1[x], p3 = row1[x + 1];
                int r0 = (p0 >> 16) & 255, g0 = (p0 >> 8) & 255, b0 = p0 & 255;
                int r1 = (p1 >> 16) & 255, g1 = (p1 >> 8) & 255, b1 = p1 & 255;
                int r2 = (p2 >> 16) & 255, g2 = (p2 >> 8) & 255, b2 = p2 & 255;
                int r3 = (p3 >> 16) & 255, g3 = (p3 >> 8) & 255, b3 = p3 & 255;

                int yIndex = y * width + x;
                destination.put(base + yIndex,
                        clamp(((66 * r0 + 129 * g0 + 25 * b0 + 128) >> 8) + 16));
                destination.put(base + yIndex + 1,
                        clamp(((66 * r1 + 129 * g1 + 25 * b1 + 128) >> 8) + 16));
                destination.put(base + yIndex + width,
                        clamp(((66 * r2 + 129 * g2 + 25 * b2 + 128) >> 8) + 16));
                destination.put(base + yIndex + width + 1,
                        clamp(((66 * r3 + 129 * g3 + 25 * b3 + 128) >> 8) + 16));

                int red = (r0 + r1 + r2 + r3 + 2) >> 2;
                int green = (g0 + g1 + g2 + g3 + 2) >> 2;
                int blue = (b0 + b1 + b2 + b3 + 2) >> 2;
                int chromaIndex = (y / 2) * (width / 2) + x / 2;
                destination.put(uBase + chromaIndex,
                        clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128));
                destination.put(vBase + chromaIndex,
                        clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128));
            }
        }
        destination.position(base + requiredOutput);
    }


    /**
     * Converts an ARGB bitmap through a reusable full-frame pixel array and
     * native conversion. The scanline implementation remains as a safe fallback.
     */
    public static void bitmapToI420(Bitmap bitmap, int width, int height,
                                    ByteBuffer destination, int[] pixels,
                                    int[] row0, int[] row1) {
        if (bitmap == null || pixels == null || pixels.length < width * height) {
            throw new IllegalArgumentException("Invalid bitmap pixel buffer");
        }
        if (destination == null || destination.remaining() < width * height * 3 / 2) {
            throw new IllegalArgumentException("I420 destination is too small");
        }
        int base = destination.position();
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        if (JREUtils.convertArgbPixelsToI420(pixels, width, height, destination, base)) {
            destination.position(base + width * height * 3 / 2);
            return;
        }
        bitmapToI420(bitmap, width, height, destination, row0, row1);
    }

    private static byte clamp(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }
}
