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

    // 32-bit float accumulators for light integration (single primary set of 3 channels = 50% RAM reduction)
    private float[] accumR;
    private float[] accumG;
    private float[] accumB;

    // Reusable single pixel buffer (allocated once, zero per-frame garbage)
    private int[] cachedPixels;

    // First frame reference for star centroid alignment
    private int refStarCenterX = -1;
    private int refStarCenterY = -1;

    // AI Feature Options
    private boolean isAiDenoiseEnabled = true;
    private boolean isAiSatelliteFilterEnabled = true;
    private boolean isAiSkyGroundEnabled = true;

    // Latest AI Scene analysis profile
    private AiAstroProcessor.SceneProfile lastProfile = new AiAstroProcessor.SceneProfile();

    public ImageStacker(StackingMode mode) {
        this.mode = mode;
        this.totalExpectedFrames = -1;
    }

    public ImageStacker(StackingMode mode, int totalExpectedFrames) {
        this.mode = mode;
        this.totalExpectedFrames = totalExpectedFrames;
    }

    private TfLiteAstroSegmenter tfLiteSegmenter;

    public void setTfLiteSegmenter(TfLiteAstroSegmenter segmenter) {
        this.tfLiteSegmenter = segmenter;
    }

    public void setAiOptions(boolean denoise, boolean satelliteFilter, boolean skyGround) {
        this.isAiDenoiseEnabled = denoise;
        this.isAiSatelliteFilterEnabled = satelliteFilter;
        this.isAiSkyGroundEnabled = skyGround;
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
            cachedPixels = new int[totalPixels];
            refStarCenterX = -1;
            refStarCenterY = -1;
        }

        int totalPixels = width * height;
        bitmap.getPixels(cachedPixels, 0, width, 0, 0, width, height);

        // 1. AI Real-Time Frame Quality Gatekeeper & Scene Analysis
        AiAstroProcessor.SceneProfile profile = AiAstroProcessor.analyzeSubframe(cachedPixels, width, height, currentFrameCount);
        if (!profile.shouldAcceptFrame) {
            Log.w(TAG, "Frame scartato dal Neural Gatekeeper a causa di bagliore anomalo.");
            return;
        }
        this.lastProfile = profile;

        // 2. AI Satellite & Hot-Pixel Transient Filter
        if (isAiSatelliteFilterEnabled && mode == StackingMode.DEEP_SKY_INTEGRATION) {
            AiAstroProcessor.filterSatelliteAndHotPixels(cachedPixels, width, height);
        }

        if (mode == StackingMode.STAR_TRAILS) {
            // Star Trails: Maximum Lightness Blending across entire sensor
            for (int i = 0; i < totalPixels; i++) {
                int color = cachedPixels[i];
                float r = (color >> 16) & 0xFF;
                float g = (color >> 8) & 0xFF;
                float b = color & 0xFF;

                if (r > accumR[i]) accumR[i] = r;
                if (g > accumG[i]) accumG[i] = g;
                if (b > accumB[i]) accumB[i] = b;
            }
        } else {
            // DEEP SKY: Star Alignment (Translation Registration) + Photon Flux Accumulation
            int shiftX = 0;
            int shiftY = 0;

            // Find star centroid in central region of frame to track Earth rotation
            int[] centroid = findStarCentroid(cachedPixels, width, height);
            if (centroid != null) {
                if (refStarCenterX < 0) {
                    refStarCenterX = centroid[0];
                    refStarCenterY = centroid[1];
                } else {
                    shiftX = centroid[0] - refStarCenterX;
                    shiftY = centroid[1] - refStarCenterY;
                    // Earth rotation causes ~1° drift in 4 min ≈ 60–120px on 4K crop sensors
                    if (Math.abs(shiftX) > 120 || Math.abs(shiftY) > 120) {
                        shiftX = 0;
                        shiftY = 0;
                    }
                }
            }

            // Accumulate shifted pixels
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                int srcY = y + shiftY;
                int srcRowOffset = (srcY >= 0 && srcY < height) ? (srcY * width) : -1;

                for (int x = 0; x < width; x++) {
                    int dstIdx = rowOffset + x;
                    int srcX = x + shiftX;

                    if (srcRowOffset >= 0 && srcX >= 0 && srcX < width) {
                        int shiftedColor = cachedPixels[srcRowOffset + srcX];
                        accumR[dstIdx] += ((shiftedColor >> 16) & 0xFF);
                        accumG[dstIdx] += ((shiftedColor >> 8) & 0xFF);
                        accumB[dstIdx] += (shiftedColor & 0xFF);
                    } else {
                        int directColor = cachedPixels[dstIdx];
                        accumR[dstIdx] += ((directColor >> 16) & 0xFF);
                        accumG[dstIdx] += ((directColor >> 8) & 0xFF);
                        accumB[dstIdx] += (directColor & 0xFF);
                    }
                }
            }
        }

        currentFrameCount++;
        Log.d(TAG, "Frame " + currentFrameCount + " accumulato (Bortle index: " + String.format(java.util.Locale.US, "%.2f", profile.lightPollutionScore) + ")");
    }

    private int[] findStarCentroid(int[] pixels, int w, int h) {
        int startX = (int) (w * 0.25f);
        int endX = (int) (w * 0.75f);
        int startY = (int) (h * 0.25f);
        int endY = (int) (h * 0.75f);
        int step = 4;

        int maxVal = 40;
        int bestX = -1, bestY = -1;

        for (int y = startY; y < endY; y += step) {
            int offset = y * w;
            for (int x = startX; x < endX; x += step) {
                int color = pixels[offset + x];
                int luma = (((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF)) / 3;
                if (luma > maxVal) {
                    maxVal = luma;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        if (bestX > 0) {
            return new int[]{bestX, bestY};
        }
        return null;
    }

    /**
     * Finalizes the stacked image with AI Semantic Sky/Ground Fusion, 2D Background Extraction, and Dynamic Asinh Stretch.
     */
    public synchronized Bitmap finalizeStack() {
        if (currentFrameCount == 0 || accumR == null) {
            return null;
        }

        int totalPixels = width * height;
        int[] outputPixels = (cachedPixels != null && cachedPixels.length == totalPixels) ? cachedPixels : new int[totalPixels];

        if (mode == StackingMode.STAR_TRAILS) {
            // Star Trails: Clean background + star track enhancement
            for (int i = 0; i < totalPixels; i++) {
                float r = accumR[i];
                float g = accumG[i];
                float b = accumB[i];

                float maxChannel = Math.max(r, Math.max(g, b));
                if (maxChannel > 30.0f) {
                    float starBoost = 1.0f + 0.30f * (maxChannel / 255.0f);
                    r = Math.min(255.0f, r * starBoost);
                    g = Math.min(255.0f, g * starBoost);
                    b = Math.min(255.0f, b * starBoost);
                }

                int ir = Math.min(255, Math.max(0, (int) r));
                int ig = Math.min(255, Math.max(0, (int) g));
                int ib = Math.min(255, Math.max(0, (int) b));
                outputPixels[i] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
            }
        } else {
            // DEEP SKY: AI Dual-Layer Synthesis (Sky Dome vs Static Landscape)
            float N = (float) currentFrameCount;
            float invN = 1.0f / N;

            // AI Semantic Sky/Ground Soft Alpha Mask (computed with invN directly, zero extra arrays)
            float[] skyMask = (tfLiteSegmenter != null) ?
                    tfLiteSegmenter.segmentSky(accumR, accumG, accumB, invN, width, height) :
                    AiAstroProcessor.generateSkyGroundMask(accumR, accumG, accumB, invN, width, height);

            // Build 16x16 2D Low-Frequency Background Grid
            int gridCols = 16;
            int gridRows = 16;
            float[][] bgGridR = new float[gridRows][gridCols];
            float[][] bgGridG = new float[gridRows][gridCols];
            float[][] bgGridB = new float[gridRows][gridCols];

            int cellW = width / gridCols;
            int cellH = height / gridRows;

            for (int gy = 0; gy < gridRows; gy++) {
                for (int gx = 0; gx < gridCols; gx++) {
                    int x0 = gx * cellW;
                    int y0 = gy * cellH;
                    int x1 = Math.min(width, (gx + 1) * cellW);
                    int y1 = Math.min(height, (gy + 1) * cellH);

                    int cellPixelCount = (x1 - x0) * (y1 - y0);
                    int sampleRate = Math.max(1, cellPixelCount / 200);
                    int maxSamples = (cellPixelCount / sampleRate) + 1;
                    float[] lumaSamples = new float[maxSamples];
                    float[] rSamples = new float[maxSamples];
                    float[] gSamples = new float[maxSamples];
                    float[] bSamples = new float[maxSamples];
                    int sCount = 0;

                    for (int cy = y0; cy < y1; cy += sampleRate) {
                        int rowOffset = cy * width;
                        for (int cx = x0; cx < x1; cx += sampleRate) {
                            if (sCount >= maxSamples) break;
                            int idx = rowOffset + cx;
                            float r = accumR[idx] * invN;
                            float g = accumG[idx] * invN;
                            float b = accumB[idx] * invN;
                            float luma = (r + g + b) / 3.0f;
                            lumaSamples[sCount] = luma;
                            rSamples[sCount] = r;
                            gSamples[sCount] = g;
                            bSamples[sCount] = b;
                            sCount++;
                        }
                    }

                    if (sCount > 0) {
                        // 6th percentile: standard in PixInsight BackgroundExtraction and Siril
                        int targetIdx = (int) (sCount * 0.06f);
                        for (int i = 0; i <= targetIdx; i++) {
                            int minIdx = i;
                            for (int j = i + 1; j < sCount; j++) {
                                if (lumaSamples[j] < lumaSamples[minIdx]) minIdx = j;
                            }
                            float tmpL = lumaSamples[i]; lumaSamples[i] = lumaSamples[minIdx]; lumaSamples[minIdx] = tmpL;
                            float tmpR = rSamples[i]; rSamples[i] = rSamples[minIdx]; rSamples[minIdx] = tmpR;
                            float tmpG = gSamples[i]; gSamples[i] = gSamples[minIdx]; gSamples[minIdx] = tmpG;
                            float tmpB = bSamples[i]; bSamples[i] = bSamples[minIdx]; bSamples[minIdx] = tmpB;
                        }
                        bgGridR[gy][gx] = rSamples[targetIdx];
                        bgGridG[gy][gx] = gSamples[targetIdx];
                        bgGridB[gy][gx] = bSamples[targetIdx];
                    }
                }
            }

            // Dynamic parameter selection from AI Scene Profile
            float asinhBeta = (lastProfile != null) ? lastProfile.suggestedAsinhBeta : 45.0f;
            double asinhBetaInv = 1.0 / asinh(asinhBeta);
            float baseStarGain = (lastProfile != null) ? lastProfile.suggestedStarGain : 8.0f;
            float starAmplification = (float) Math.max(3.0, (baseStarGain * 6.5) / Math.sqrt(N));

            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                float gy = ((float) y / height) * (gridRows - 1);
                int gy0 = (int) gy;
                int gy1 = Math.min(gridRows - 1, gy0 + 1);
                float yFrac = gy - gy0;

                for (int x = 0; x < width; x++) {
                    int idx = rowOffset + x;
                    float gx = ((float) x / width) * (gridCols - 1);
                    int gx0 = (int) gx;
                    int gx1 = Math.min(gridCols - 1, gx0 + 1);
                    float xFrac = gx - gx0;

                    // Interpolated local background for sky
                    float bgR = (1 - yFrac) * ((1 - xFrac) * bgGridR[gy0][gx0] + xFrac * bgGridR[gy0][gx1])
                            + yFrac * ((1 - xFrac) * bgGridR[gy1][gx0] + xFrac * bgGridR[gy1][gx1]);
                    float bgG = (1 - yFrac) * ((1 - xFrac) * bgGridG[gy0][gx0] + xFrac * bgGridG[gy0][gx1])
                            + yFrac * ((1 - xFrac) * bgGridG[gy1][gx0] + xFrac * bgGridG[gy1][gx1]);
                    float bgB = (1 - yFrac) * ((1 - xFrac) * bgGridB[gy0][gx0] + xFrac * bgGridB[gy0][gx1])
                            + yFrac * ((1 - xFrac) * bgGridB[gy1][gx0] + xFrac * bgGridB[gy1][gx1]);

                    // 1. Process Sky Pixel
                    float meanSkyR = accumR[idx] * invN;
                    float meanSkyG = accumG[idx] * invN;
                    float meanSkyB = accumB[idx] * invN;

                    float sigR = Math.max(0f, meanSkyR - bgR);
                    float sigG = Math.max(0f, meanSkyG - bgG);
                    float sigB = Math.max(0f, meanSkyB - bgB);

                    float normR = Math.min(1.0f, (sigR * starAmplification) / 255.0f);
                    float normG = Math.min(1.0f, (sigG * starAmplification) / 255.0f);
                    float normB = Math.min(1.0f, (sigB * starAmplification) / 255.0f);

                    double strR = asinh(asinhBeta * normR) * asinhBetaInv;
                    double strG = asinh(asinhBeta * normG) * asinhBetaInv;
                    double strB = asinh(asinhBeta * normB) * asinhBetaInv;

                    float skyR = (float) (strR * 255.0 + 3.0f);
                    float skyG = (float) (strG * 255.0 + 3.0f);
                    float skyB = (float) (strB * 255.0 + 4.0f);

                    // 2. Process Ground Pixel (Static noise-reduced temporal mean)
                    float gR = meanSkyR;
                    float gG = meanSkyG;
                    float gB = meanSkyB;

                    // 3. AI Semantic Alpha Blend (Seamless edge-preserving fusion)
                    float m = isAiSkyGroundEnabled ? skyMask[idx] : 1.0f; // 1.0 = Pure Sky, 0.0 = Pure Ground
                    float finalR = m * skyR + (1.0f - m) * gR;
                    float finalG = m * skyG + (1.0f - m) * gG;
                    float finalB = m * skyB + (1.0f - m) * gB;

                    int r = Math.min(255, Math.max(0, Math.round(finalR)));
                    int g = Math.min(255, Math.max(0, Math.round(finalG)));
                    int b = Math.min(255, Math.max(0, Math.round(finalB)));

                    outputPixels[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            // 4. AI Neural Chroma Denoising & Star PSF Sharpener
            if (isAiDenoiseEnabled) {
                AiAstroProcessor.applyNeuralChromaDenoise(outputPixels, width, height, 0.85f);
                AiAstroProcessor.enhanceStarPSF(outputPixels, skyMask, width, height);
            }
        }

        Bitmap outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        outputBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height);
        return outputBitmap;
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1.0));
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
        cachedPixels = null;
        currentFrameCount = 0;
        refStarCenterX = -1;
        refStarCenterY = -1;
    }
}