package com.example.mycamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AstroCameraController {

    private static final String TAG = "AstroCameraController";

    public interface CameraEventListener {
        void onCameraReady(CameraCapabilities capabilities);
        void onExposureUpdated(long exposureNs, int iso, float focusDistance);
        void onCaptureStarted(long estimatedDurationMs, int totalFrames);
        void onStackProgress(int currentFrame, int totalFrames, long elapsedMs, long totalMs);
        void onCaptureCompleted(Uri imageUri, Bitmap thumbnail);
        void onCaptureFailed(String errorMessage);
        void onError(String errorMessage);
    }

    public static class CameraCapabilities {
        public Range<Long> exposureTimeRange;     // nanoseconds
        public Range<Integer> isoRange;           // ISO values
        public float minFocusDistance;            // diopters (0 = infinity)
        public Range<Integer> aeCompensationRange;
        public Rational aeCompensationStep;
        public boolean supportsManualSensor;
        public Size previewSize;
        public Size captureSize;
    }

    public enum NoiseReductionPreset {
        HIGH_QUALITY,
        MINIMAL,
        OFF
    }

    public enum FlashMode {
        OFF,
        TORCH,
        AUTO
    }

    private final Activity activity;
    private final AutoFitTextureView textureView;
    private final CameraEventListener listener;
    private final Handler mainHandler;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private ImageReader imageReader;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private CameraCapabilities capabilities;
    private int sensorOrientation = 0;
    private int facing = CameraCharacteristics.LENS_FACING_BACK;

    // Current Manual Parameters (-1 means AUTO)
    private long manualExposureNs = -1; // nanoseconds (-1 = Auto)
    private int manualIso = -1;         // (-1 = Auto)
    private float manualFocusDistance = -1f; // (-1 = Auto Focus, 0.0f = Infinity)
    private int aeCompensation = 0;
    private FlashMode flashMode = FlashMode.OFF;
    private NoiseReductionPreset noiseReductionPreset = NoiseReductionPreset.HIGH_QUALITY;
    private ImageStacker.StackingMode stackingMode = ImageStacker.StackingMode.DEEP_SKY_INTEGRATION;
    private ImageStacker activeStacker;
    private int targetFramesCount = 1;
    private int capturedFramesCount = 0;
    private long subFrameExposureNs = 0;
    private long totalRequestedExposureNs = 0;
    private volatile boolean cancelOrFinishEarly = false;
    private long captureStartTimeMs = 0;
    private CaptureRequest.Builder currentCaptureBuilder;

    private boolean isCapturing = false;

    public AstroCameraController(Activity activity, AutoFitTextureView textureView, CameraEventListener listener) {
        this.activity = activity;
        this.textureView = textureView;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    }

    public void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    public void startCamera() {
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    public void stopCamera() {
        closeCamera();
        stopBackgroundThread();
    }

    public void switchCamera() {
        closeCamera();
        facing = (facing == CameraCharacteristics.LENS_FACING_BACK) ?
                CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access the camera", e);
            notifyError("Accesso alla fotocamera fallito: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        } catch (SecurityException e) {
            notifyError("Permesso fotocamera non concesso.");
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void setUpCameraOutputs(int width, int height) {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == facing) {
                    this.cameraId = id;
                    this.capabilities = new CameraCapabilities();

                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    capabilities.exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    capabilities.isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

                    Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    capabilities.minFocusDistance = (minFocus != null) ? minFocus : 0f;

                    capabilities.aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    capabilities.aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);

                    int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    capabilities.supportsManualSensor = false;
                    if (caps != null) {
                        for (int cap : caps) {
                            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                                capabilities.supportsManualSensor = true;
                                break;
                            }
                        }
                    }

                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;

                    // Choose largest JPEG size for pristine night shots
                    Size largest = Collections.max(
                            Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                            new CompareSizesByArea()
                    );
                    capabilities.captureSize = largest;

                    imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
                    imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

                    // Choose optimal preview size
                    Size optimalPreviewSize = chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class),
                            width, height, largest
                    );
                    capabilities.previewSize = optimalPreviewSize;

                    activity.runOnUiThread(() -> {
                        textureView.setAspectRatio(optimalPreviewSize.getHeight(), optimalPreviewSize.getWidth());
                        if (listener != null) {
                            listener.onCameraReady(capabilities);
                        }
                    });

                    return;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting up camera outputs", e);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            notifyError("Errore hardware fotocamera: " + error);
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;

            texture.setDefaultBufferSize(capabilities.previewSize.getWidth(), capabilities.previewSize.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            updatePreviewSettings();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            notifyError("Configurazione sessione fotocamera fallita.");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session", e);
        }
    }

    /**
     * Applies manual / auto settings to preview request and updates repeating request.
     */
    public void updatePreviewSettings() {
        if (captureSession == null || previewRequestBuilder == null) return;

        try {
            // Noise reduction configuration
            applyNoiseReduction(previewRequestBuilder);

            // Flash / Torch configuration
            applyFlash(previewRequestBuilder);

            // Exposure controls (Manual vs Auto)
            if (manualExposureNs > 0 && manualIso > 0) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                // For live preview, sensor exposure cannot exceed preview frame duration (e.g. 1/15s = ~66ms),
                // so we clamp for preview, but still capture full exposure during takePicture!
                long previewExposure = Math.min(manualExposureNs, 66_666_666L);
                previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewExposure);
                previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
            } else {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensation);
            }

            // Focus controls (Manual vs Auto)
            if (manualFocusDistance >= 0f) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance);
            } else {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, previewCaptureCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error updating preview settings", e);
        }
    }

    private void applyNoiseReduction(CaptureRequest.Builder builder) {
        switch (noiseReductionPreset) {
            case MINIMAL:
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL);
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY);
                break;
            case OFF:
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
                break;
            case HIGH_QUALITY:
            default:
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY);
                break;
        }
    }

    private void applyFlash(CaptureRequest.Builder builder) {
        switch (flashMode) {
            case TORCH:
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                break;
            case OFF:
            default:
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer sens = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

            long currentExp = (manualExposureNs > 0) ? manualExposureNs : (exp != null ? exp : 0L);
            int currentIso = (manualIso > 0) ? manualIso : (sens != null ? sens : 0);
            float currentFocus = (manualFocusDistance >= 0f) ? manualFocusDistance : (focus != null ? focus : 0f);

            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onExposureUpdated(currentExp, currentIso, currentFocus);
                }
            });
        }
    };

    /**
     * Executes still capture with multi-frame stacking for ultra-long exposures (e.g. 1-3+ minutes).
     */
    public void captureStillPicture() {
        if (cameraDevice == null || captureSession == null || isCapturing) return;

        try {
            isCapturing = true;
            cancelOrFinishEarly = false;
            capturedFramesCount = 0;
            totalRequestedExposureNs = manualExposureNs;

            // Determine maximum sensor hardware exposure
            long maxSensorNs = 15_000_000_000L; // 15s default fallback
            if (capabilities != null && capabilities.exposureTimeRange != null) {
                maxSensorNs = capabilities.exposureTimeRange.getUpper();
            }

            if (manualExposureNs <= 0) {
                // Auto single capture
                targetFramesCount = 1;
                subFrameExposureNs = -1;
            } else if (manualExposureNs == -2L) {
                // Bulb mode: continuous stacking until stopped
                subFrameExposureNs = Math.min(maxSensorNs, 15_000_000_000L);
                targetFramesCount = 9999;
            } else if (manualExposureNs <= maxSensorNs) {
                // Single native long exposure
                targetFramesCount = 1;
                subFrameExposureNs = manualExposureNs;
            } else {
                // Multi-frame Astro Stacking (e.g. 60s, 120s, 180s, 300s)
                subFrameExposureNs = Math.min(maxSensorNs, 15_000_000_000L);
                targetFramesCount = (int) Math.ceil((double) manualExposureNs / subFrameExposureNs);
            }

            activeStacker = new ImageStacker(stackingMode, targetFramesCount);
            captureStartTimeMs = System.currentTimeMillis();

            long totalEstimatedDurationMs;
            if (targetFramesCount == 9999) {
                totalEstimatedDurationMs = 0; // Bulb
            } else if (subFrameExposureNs > 0) {
                totalEstimatedDurationMs = targetFramesCount * (subFrameExposureNs / 1_000_000L);
            } else {
                totalEstimatedDurationMs = 500L;
            }

            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onCaptureStarted(totalEstimatedDurationMs, targetFramesCount);
                }
            });

            // Stop repeating viewfinder preview during astro exposure
            captureSession.stopRepeating();

            // Build capture request
            currentCaptureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            currentCaptureBuilder.addTarget(imageReader.getSurface());
            currentCaptureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            applyNoiseReduction(currentCaptureBuilder);
            applyFlash(currentCaptureBuilder);

            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            int jpegOrientation = getOrientation(rotation);
            currentCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);

            if (subFrameExposureNs > 0) {
                int effectiveIso = (manualIso > 0) ? manualIso : 1600;
                currentCaptureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, subFrameExposureNs);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, subFrameExposureNs);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, effectiveIso);
                Log.i(TAG, "Impostato scatto manuale: Esposizione=" + (subFrameExposureNs / 1_000_000_000.0) + "s, ISO=" + effectiveIso);
            } else {
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensation);
            }

            if (manualFocusDistance >= 0f) {
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                currentCaptureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance);
            } else {
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            triggerNextSubFrame();

        } catch (CameraAccessException e) {
            isCapturing = false;
            Log.e(TAG, "Error starting capture", e);
            notifyError("Errore durante l'avvio dello scatto: " + e.getMessage());
        }
    }

    private void triggerNextSubFrame() {
        if (captureSession == null || currentCaptureBuilder == null || cancelOrFinishEarly) return;

        try {
            captureSession.capture(currentCaptureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "Sub-frame capture completed on sensor.");
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    Log.e(TAG, "Sub-frame capture failed: " + failure.getReason());
                    if (capturedFramesCount == 0) {
                        isCapturing = false;
                        updatePreviewSettings();
                        notifyError("Scatto fallito: " + failure.getReason());
                    } else {
                        // Finalize whatever was captured so far
                        finishAndSaveStack();
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error triggering sub-frame", e);
            if (capturedFramesCount > 0) {
                finishAndSaveStack();
            } else {
                isCapturing = false;
                updatePreviewSettings();
            }
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) return;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            image.close();

            if (targetFramesCount == 1 && !cancelOrFinishEarly) {
                // Single frame capture (standard)
                int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                int jpegOrientation = getOrientation(rotation);

                ImageSaver saver = new ImageSaver(
                        activity,
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length),
                        manualExposureNs,
                        manualIso,
                        manualFocusDistance,
                        jpegOrientation,
                        new ImageSaver.ImageSaveCallback() {
                            @Override
                            public void onSuccess(Uri imageUri, Bitmap thumbnail) {
                                isCapturing = false;
                                updatePreviewSettings();
                                mainHandler.post(() -> {
                                    if (listener != null) {
                                        listener.onCaptureCompleted(imageUri, thumbnail);
                                    }
                                });
                            }

                            @Override
                            public void onError(Exception e) {
                                isCapturing = false;
                                updatePreviewSettings();
                                mainHandler.post(() -> {
                                    if (listener != null) {
                                        listener.onCaptureFailed(e.getMessage());
                                    }
                                });
                            }
                        }
                );
                backgroundHandler.post(saver);
                return;
            }

            // Multi-frame stacking
            if (activeStacker != null) {
                activeStacker.addFrame(bytes);
                capturedFramesCount++;

                long elapsedMs = System.currentTimeMillis() - captureStartTimeMs;
                long totalMs = (targetFramesCount == 9999) ? 0 : (targetFramesCount * (subFrameExposureNs / 1_000_000L));

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onStackProgress(capturedFramesCount, targetFramesCount, elapsedMs, totalMs);
                    }
                });

                if (capturedFramesCount < targetFramesCount && !cancelOrFinishEarly) {
                    triggerNextSubFrame();
                } else {
                    finishAndSaveStack();
                }
            }
        }
    };

    /**
     * Finishes multi-frame stacking early and saves the resulting image.
     */
    public void stopCaptureAndSave() {
        if (!isCapturing) return;
        cancelOrFinishEarly = true;
        backgroundHandler.post(this::finishAndSaveStack);
    }

    private void finishAndSaveStack() {
        if (activeStacker == null || capturedFramesCount == 0) {
            isCapturing = false;
            updatePreviewSettings();
            return;
        }

        Bitmap finalStackedBitmap = activeStacker.finalizeStack();
        if (finalStackedBitmap == null) {
            isCapturing = false;
            updatePreviewSettings();
            notifyError("Errore durante l'elaborazione dell'immagine astronomica.");
            return;
        }

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int jpegOrientation = getOrientation(rotation);

        long actualExposureNs = capturedFramesCount * subFrameExposureNs;

        ImageSaver saver = new ImageSaver(
                activity,
                finalStackedBitmap,
                actualExposureNs,
                manualIso,
                manualFocusDistance,
                jpegOrientation,
                new ImageSaver.ImageSaveCallback() {
                    @Override
                    public void onSuccess(Uri imageUri, Bitmap thumbnail) {
                        isCapturing = false;
                        updatePreviewSettings();
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onCaptureCompleted(imageUri, thumbnail);
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        isCapturing = false;
                        updatePreviewSettings();
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onCaptureFailed(e.getMessage());
                            }
                        });
                    }
                }
        );
        saver.run();
    }

    public void setStackingMode(ImageStacker.StackingMode mode) {
        this.stackingMode = mode;
    }

    public ImageStacker.StackingMode getStackingMode() {
        return stackingMode;
    }

    /**
     * Touch to focus at specified coordinates in view.
     */
    public void focusOnPoint(float x, float y) {
        if (captureSession == null || previewRequestBuilder == null || manualFocusDistance >= 0f) return;

        try {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) return;

            Rect sensorRect = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensorRect == null) return;

            int halfTouchWidth = 150;
            int halfTouchHeight = 150;

            int centerX = (int) (x / viewWidth * sensorRect.width());
            int centerY = (int) (y / viewHeight * sensorRect.height());

            Rect focusArea = new Rect(
                    Math.max(0, centerX - halfTouchWidth),
                    Math.max(0, centerY - halfTouchHeight),
                    Math.min(sensorRect.width(), centerX + halfTouchWidth),
                    Math.min(sensorRect.height(), centerY + halfTouchHeight)
            );

            MeteringRectangle meteringRect = new MeteringRectangle(focusArea, MeteringRectangle.METERING_WEIGHT_MAX);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{meteringRect});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

            captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            updatePreviewSettings();

        } catch (Exception e) {
            Log.e(TAG, "Error focusing on point", e);
        }
    }

    // Setters for camera parameters
    public void setManualExposureNs(long exposureNs) {
        this.manualExposureNs = exposureNs;
        updatePreviewSettings();
    }

    public void setManualIso(int iso) {
        this.manualIso = iso;
        updatePreviewSettings();
    }

    public void setManualFocusDistance(float focusDistance) {
        this.manualFocusDistance = focusDistance;
        updatePreviewSettings();
    }

    public void setAeCompensation(int compensation) {
        this.aeCompensation = compensation;
        updatePreviewSettings();
    }

    public void setFlashMode(FlashMode mode) {
        this.flashMode = mode;
        updatePreviewSettings();
    }

    public void setNoiseReductionPreset(NoiseReductionPreset preset) {
        this.noiseReductionPreset = preset;
        updatePreviewSettings();
    }

    public CameraCapabilities getCapabilities() {
        return capabilities;
    }

    public long getManualExposureNs() {
        return manualExposureNs;
    }

    public int getManualIso() {
        return manualIso;
    }

    public float getManualFocusDistance() {
        return manualFocusDistance;
    }

    public FlashMode getFlashMode() {
        return flashMode;
    }

    public NoiseReductionPreset getNoiseReductionPreset() {
        return noiseReductionPreset;
    }

    private int getOrientation(int rotation) {
        int[] orientations = {0, 90, 180, 270};
        int deviceOrientation = orientations[rotation];
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorOrientation + deviceOrientation) % 360;
        } else {
            return (sensorOrientation - deviceOrientation + 360) % 360;
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || capabilities == null || capabilities.previewSize == null) return;

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, capabilities.previewSize.getHeight(), capabilities.previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / capabilities.previewSize.getHeight(),
                    (float) viewWidth / capabilities.previewSize.getWidth()
            );
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for (Size option : choices) {
            if (option.getWidth() <= 1920 && option.getHeight() <= 1080 &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
