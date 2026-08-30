package com.example.mycamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

public class ImageStacker {

    private static final String TAG = "ImageStacker";

    public enum StackingMode {
        DEEP_SKY_INTEGRATION, // Accumulates photons, removes sensor noise, stretches faint stars/nebulae
        STAR_TRAILS           // Max lightness blend, forms continuous star trails across the sky
    }

    private final StackingMode mode;
    private final int totalExpectedFrames;
    private int currentFrameCount = 0;

    private int width = 0;
    private int height = 0;

    // 32-bit float accumulators for light integration
    private float[] accumR;
    private float[] accumG;
    private float[] accumB;

    public ImageStacker(StackingMode mode, int totalExpectedFrames) {
        this.mode = mode;
        this.totalExpectedFrames = Math.max(1, totalExpectedFrames);
    }

    /**
     * Adds a JPEG frame to the stack.
     */
    public synchronized void addFrame(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) return;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
        if (bitmap == null) return;
        addBitmapFrame(bitmap);
        bitmap.recycle();
    }

    public synchronized void addBitmapFrame(Bitmap bitmap) {
        int bmpW = bitmap.getWidth();
        int bmpH = bitmap.getHeight();

        if (accumR == null || width != bmpW || height != bmpH) {
            this.width = bmpW;
            this.height = bmpH;
            int totalPixels = width * height;
            accumR = new float[totalPixels];
            accumG = new float[totalPixels];
            accumB = new float[totalPixels];
        }

        int totalPixels = width * height;
        int[] pixels = new int[totalPixels];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        if (mode == StackingMode.STAR_TRAILS) {
            for (int i = 0; i < totalPixels; i++) {
                int color = pixels[i];
                float r = (color >> 16) & 0xFF;
                float g = (color >> 8) & 0xFF;
                float b = color & 0xFF;

                if (r > accumR[i]) accumR[i] = r;
                if (g > accumG[i]) accumG[i] = g;
                if (b > accumB[i]) accumB[i] = b;
            }
        } else {
            // DEEP_SKY_INTEGRATION: Accumulate photon counts
            for (int i = 0; i < totalPixels; i++) {
                int color = pixels[i];
                accumR[i] += ((color >> 16) & 0xFF);
                accumG[i] += ((color >> 8) & 0xFF);
                accumB[i] += (color & 0xFF);
            }
        }

        currentFrameCount++;
        Log.d(TAG, "Frame " + currentFrameCount + " accumulato con successo.");
    }

    /**
     * Finalizes the stacked image with astronomical tone-mapping and light gathering boost.
     */
    public synchronized Bitmap finalizeStack() {
        if (currentFrameCount == 0 || accumR == null) {
            return null;
        }

        int totalPixels = width * height;
        int[] outputPixels = new int[totalPixels];

        if (mode == StackingMode.STAR_TRAILS) {
            for (int i = 0; i < totalPixels; i++) {
                int r = Math.min(255, (int) accumR[i]);
                int g = Math.min(255, (int) accumG[i]);
                int b = Math.min(255, (int) accumB[i]);
                outputPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            // Astronomical non-linear stretch for Deep Sky
            // Light Integration: Signal accumulates with sqrt(N) SNR gain
            // and logarithmic exposure brightening so multi-minute light is clearly visible
            float N = (float) currentFrameCount;
            float lightGain = (float) (1.0 + 0.65 * Math.log(1.0 + N)); // Boost accumulated light
            float invN = 1.0f / N;
            float gamma = 0.88f; // Shadow lift to reveal faint nebulae and stars

            for (int i = 0; i < totalPixels; i++) {
                // Mean normalized value
                float normR = (accumR[i] * invN) / 255.0f;
                float normG = (accumG[i] * invN) / 255.0f;
                float normB = (accumB[i] * invN) / 255.0f;

                // Apply light gain & astro stretch
                float stretchedR = (float) Math.pow(Math.min(1.0f, normR * lightGain), gamma) * 255.0f;
                float stretchedG = (float) Math.pow(Math.min(1.0f, normG * lightGain), gamma) * 255.0f;
                float stretchedB = (float) Math.pow(Math.min(1.0f, normB * lightGain), gamma) * 255.0f;

                int r = Math.min(255, Math.max(0, (int) stretchedR));
                int g = Math.min(255, Math.max(0, (int) stretchedG));
                int b = Math.min(255, Math.max(0, (int) stretchedB));

                outputPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        outputBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height);
        return outputBitmap;
    }

    public int getCurrentFrameCount() {
        return currentFrameCount;
    }

    public int getTotalExpectedFrames() {
        return totalExpectedFrames;
    }

    public void reset() {
        accumR = null;
        accumG = null;
        accumB = null;
        currentFrameCount = 0;
    }
}