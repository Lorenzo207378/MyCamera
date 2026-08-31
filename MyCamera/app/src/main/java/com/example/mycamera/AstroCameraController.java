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
import java.util.concurrent.atomic.AtomicBoolean;

public class AstroCameraController {

    private static final String TAG = "AstroCameraController";

    public interface CameraEventListener {
        void onCameraReady(CameraCapabilities capabilities);
        void onExposureUpdated(long exposureNs, int iso, float focusDistance);
        void onZoomUpdated(float currentZoom, float minZoom, float maxZoom);
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
        public Float hyperfocalDistance;
        public Integer focusCalibration;
        public Range<Integer> postRawSensitivityBoostRange;
        public Range<Integer> aeCompensationRange;
        public Rational aeCompensationStep;
        public boolean supportsManualSensor;
        public Size previewSize;
        public Size captureSize;
        public float minZoom = 0.5f;
        public float maxZoom = 10.0f;
        public Range<Float> zoomRange;
        public Rect activeArraySize;
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
    private String mainBackCameraId = null;
    private String ultraWideCameraId = null;
    private String telephotoCameraId = null;
    private String frontCameraId = null;

    // Manual camera parameters
    private long manualExposureNs = -1; // -1 for Auto, >0 for manual nanoseconds, -2 for Bulb
    private int manualIso = -1;          // -1 for Auto, >0 for manual ISO
    private float manualFocusDistance = -1f; // -1 for AF, 0.0f for Infinity, >0 for manual diopters
    private int aeCompensation = 0;
    private float currentZoom = 1.0f;
    private final AtomicBoolean isZoomUpdatePending = new AtomicBoolean(false);
    private final AtomicBoolean isCameraSwitching = new AtomicBoolean(false);
    private FlashMode flashMode = FlashMode.OFF;
    private NoiseReductionPreset noiseReductionPreset = NoiseReductionPreset.HIGH_QUALITY;
    private ImageStacker.StackingMode stackingMode = ImageStacker.StackingMode.DEEP_SKY_INTEGRATION;
    private ImageStacker activeStacker;
    private boolean isAiDenoiseEnabled = true;
    private boolean isAiSatelliteFilterEnabled = true;
    private boolean isAiSkyGroundEnabled = true;
    private int targetFramesCount = 1;
    private int capturedFramesCount = 0;
    private long subFrameExposureNs = 0;
    private long totalRequestedExposureNs = 0;
    private volatile boolean cancelOrFinishEarly = false;
    private long captureStartTimeMs = 0;
    private CaptureRequest.Builder currentCaptureBuilder;

    private TfLiteAstroSegmenter tfLiteSegmenter;
    private boolean isCapturing = false;

    public AstroCameraController(Activity activity, AutoFitTextureView textureView, CameraEventListener listener) {
        this.activity = activity;
        this.textureView = textureView;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        this.tfLiteSegmenter = new TfLiteAstroSegmenter(activity);
    }

    public void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(300);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    public void startCamera() {
        startBackgroundThread();
        if (textureView != null && textureView.isAvailable()) {
            final int width = textureView.getWidth();
            final int height = textureView.getHeight();
            if (backgroundHandler != null) {
                backgroundHandler.post(() -> openCameraInternal(width, height));
            }
        } else if (textureView != null) {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    public void stopCamera() {
        closeCamera();
        stopBackgroundThread();
        if (tfLiteSegmenter != null) {
            tfLiteSegmenter.close();
            tfLiteSegmenter = null;
        }
    }

    public void switchCamera() {
        facing = (facing == CameraCharacteristics.LENS_FACING_BACK) ?
                CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        this.cameraId = (facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId != null) ?
                frontCameraId : (mainBackCameraId != null ? mainBackCameraId : "0");
        reopenCamera();
    }

    public void switchCameraTo(String targetCameraId) {
        if (targetCameraId == null || targetCameraId.equals(this.cameraId)) return;
        if (!isCameraSwitching.compareAndSet(false, true)) {
            return;
        }

        this.cameraId = targetCameraId;
        if (backgroundHandler != null) {
            backgroundHandler.post(() -> {
                try {
                    closeCameraInternal();
                    if (textureView != null && textureView.isAvailable()) {
                        openCameraInternal(textureView.getWidth(), textureView.getHeight());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error switching camera", e);
                } finally {
                    isCameraSwitching.set(false);
                }
            });
        } else {
            isCameraSwitching.set(false);
        }
    }

    public void switchToUltraWide() {
        this.currentZoom = 0.5f;
        if (facing == CameraCharacteristics.LENS_FACING_BACK && ultraWideCameraId != null && !ultraWideCameraId.equals(this.cameraId)) {
            switchCameraTo(ultraWideCameraId);
        } else {
            setZoom(0.5f);
        }
        if (listener != null) {
            mainHandler.post(() -> listener.onZoomUpdated(0.5f, 0.5f, 10.0f));
        }
    }

    public void switchToMainCamera() {
        if (facing == CameraCharacteristics.LENS_FACING_BACK && mainBackCameraId != null && !mainBackCameraId.equals(this.cameraId)) {
            switchCameraTo(mainBackCameraId);
        }
    }

    private void reopenCamera() {
        if (textureView != null && textureView.isAvailable()) {
            if (backgroundHandler != null) {
                backgroundHandler.post(() -> {
                    closeCameraInternal();
                    openCameraInternal(textureView.getWidth(), textureView.getHeight());
                });
            }
        }
    }

    private void closeCameraInternal() {
        try {
            if (captureSession != null) {
                try { captureSession.close(); } catch (Exception ignored) {}
                captureSession = null;
            }
            if (cameraDevice != null) {
                try { cameraDevice.close(); } catch (Exception ignored) {}
                cameraDevice = null;
            }
            if (imageReader != null) {
                try { imageReader.close(); } catch (Exception ignored) {}
                imageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in closeCameraInternal", e);
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            if (backgroundHandler != null) {
                backgroundHandler.post(() -> openCameraInternal(width, height));
            } else {
                startBackgroundThread();
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> openCameraInternal(width, height));
                }
            }
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
    private void openCameraInternal(int width, int height) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(1500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Time out waiting to lock camera opening.");
                return;
            }

            setUpCameraOutputs(width, height);
            activity.runOnUiThread(() -> configureTransform(width, height));

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Cannot access the camera", e);
            notifyError("Accesso alla fotocamera fallito: " + e.getMessage());
        } catch (InterruptedException e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Interrupted while trying to lock camera opening.", e);
        } catch (SecurityException e) {
            cameraOpenCloseLock.release();
            notifyError("Permesso fotocamera non concesso.");
        } catch (Exception e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Error opening camera", e);
        }
    }

    private void closeCamera() {
        try {
            if (!cameraOpenCloseLock.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timeout locking camera for closing, forcing close");
            }
            closeCameraInternal();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while closing camera", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void setUpCameraOutputs(int width, int height) {
        try {
            float mainFocal = 4.5f;
            // First pass: identify physical and logical lenses (wide, ultra-wide, tele, front)
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = chars.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing == null) continue;

                if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (frontCameraId == null) frontCameraId = id;
                } else if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (mainBackCameraId == null) {
                        mainBackCameraId = id;
                        float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        if (fl != null && fl.length > 0) mainFocal = fl[0];
                    } else {
                        float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        if (fl != null && fl.length > 0) {
                            if (fl[0] < mainFocal || fl[0] < 3.5f) {
                                ultraWideCameraId = id;
                            } else if (fl[0] > 6.0f) {
                                telephotoCameraId = id;
                            }
                        }
                    }
                }
            }

            // Select active camera ID based on facing and current zoom
            if (this.cameraId == null) {
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    this.cameraId = (frontCameraId != null) ? frontCameraId : "1";
                } else {
                    if (currentZoom <= 0.6f && ultraWideCameraId != null) {
                        this.cameraId = ultraWideCameraId;
                    } else {
                        this.cameraId = (mainBackCameraId != null) ? mainBackCameraId : "0";
                    }
                }
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(this.cameraId);
            this.capabilities = new CameraCapabilities();

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            capabilities.exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            capabilities.isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

            Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            capabilities.minFocusDistance = (minFocus != null) ? minFocus : 0f;
            capabilities.hyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
            capabilities.focusCalibration = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                capabilities.postRawSensitivityBoostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE);
            }

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

            Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            capabilities.activeArraySize = activeArray;
            capabilities.minZoom = 0.5f;
            capabilities.maxZoom = 10.0f;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                capabilities.zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (capabilities.zoomRange != null) {
                    capabilities.minZoom = capabilities.zoomRange.getLower();
                    capabilities.maxZoom = capabilities.zoomRange.getUpper();
                }
            }

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

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
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting up camera outputs", e);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            if (cameraDevice != null && cameraDevice != camera) {
                try {
                    cameraDevice.close();
                } catch (Exception ignored) {}
            }
            cameraDevice = camera;
            try {
                createCameraPreviewSession();
            } catch (Exception e) {
                Log.e(TAG, "Error creating preview session in onOpened", e);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            try {
                camera.close();
            } catch (Exception ignored) {}
            if (cameraDevice == camera) {
                cameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            try {
                camera.close();
            } catch (Exception ignored) {}
            if (cameraDevice == camera) {
                cameraDevice = null;
            }
            Log.e(TAG, "Hardware camera error code: " + error);
        }
    };

    private void createCameraPreviewSession() {
        if (cameraDevice == null || textureView == null || !textureView.isAvailable()) return;

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || capabilities == null || capabilities.previewSize == null) return;

            texture.setDefaultBufferSize(capabilities.previewSize.getWidth(), capabilities.previewSize.getHeight());
            Surface surface = new Surface(texture);

            if (cameraDevice == null) return;
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                try { session.close(); } catch (Exception ignored) {}
                                return;
                            }
                            captureSession = session;
                            updatePreviewSettings();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            try { session.close(); } catch (Exception ignored) {}
                            notifyError("Configurazione sessione fotocamera fallita.");
                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            if (captureSession == session) {
                                captureSession = null;
                            }
                        }
                    },
                    backgroundHandler
            );
        } catch (Exception e) {
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

            // Apply Zoom (0.5x to maxZoom)
            applyZoom(previewRequestBuilder);

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
            long maxSensorNs = 10_000_000_000L; // 10s fallback
            if (capabilities != null && capabilities.exposureTimeRange != null) {
                maxSensorNs = capabilities.exposureTimeRange.getUpper();
            }

            if (stackingMode == ImageStacker.StackingMode.STAR_TRAILS) {
                // Star Trails: 25s sub-frames are the astrophotography standard.
                // Shorter than 10s → too many inter-frame gaps; longer → dark current accumulates.
                // 25s prevents sky background saturation in light-polluted areas while keeping
                // continuous star trails without visible gaps between arcs (standard: 15–30s).
                subFrameExposureNs = Math.min(maxSensorNs, 25_000_000_000L);
                targetFramesCount = -1; // Continuous until duration ends
                totalRequestedExposureNs = (manualExposureNs == -2L) ? -2L : ((manualExposureNs > 0) ? manualExposureNs : 1_800_000_000_000L);
            } else if (manualExposureNs <= 0) {
                // Auto single capture
                targetFramesCount = 1;
                subFrameExposureNs = -1;
                totalRequestedExposureNs = 0;
            } else if (manualExposureNs == -2L) {
                // Bulb mode: continuous stacking until stopped
                subFrameExposureNs = Math.min(maxSensorNs, 10_000_000_000L);
                targetFramesCount = -1;
                totalRequestedExposureNs = -2L;
            } else if (manualExposureNs <= maxSensorNs) {
                // Single native long exposure
                targetFramesCount = 1;
                subFrameExposureNs = manualExposureNs;
                totalRequestedExposureNs = manualExposureNs;
            } else {
                // Multi-frame Astro Stacking (e.g. 60s, 120s, 180s, 240s, 300s): Continuous time-driven integration!
                subFrameExposureNs = Math.min(maxSensorNs, 10_000_000_000L);
                targetFramesCount = -1; // Continuous time-based stacking
                totalRequestedExposureNs = manualExposureNs;
            }

            activeStacker = new ImageStacker(stackingMode);
            activeStacker.setAiOptions(isAiDenoiseEnabled, isAiSatelliteFilterEnabled, isAiSkyGroundEnabled);
            activeStacker.setTfLiteSegmenter(tfLiteSegmenter);
            captureStartTimeMs = System.currentTimeMillis();

            long totalEstimatedDurationMs;
            if (totalRequestedExposureNs == -2L) {
                totalEstimatedDurationMs = 0; // Bulb
            } else if (totalRequestedExposureNs > 0) {
                totalEstimatedDurationMs = totalRequestedExposureNs / 1_000_000L;
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
                int effectiveIso;
                if (manualIso > 0) {
                    effectiveIso = manualIso;
                } else {
                    int maxIso = (capabilities != null && capabilities.isoRange != null) ? capabilities.isoRange.getUpper() : 3200;
                    effectiveIso = Math.min(maxIso, 3200);
                }
                currentCaptureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, subFrameExposureNs);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, subFrameExposureNs);
                currentCaptureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, effectiveIso);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && capabilities != null && capabilities.postRawSensitivityBoostRange != null) {
                    currentCaptureBuilder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, capabilities.postRawSensitivityBoostRange.getUpper());
                }
                Log.i(TAG, "Impostato scatto manuale: Esposizione=" + (subFrameExposureNs / 1_000_000_000.0) + "s, ISO=" + effectiveIso);
            } else {
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                currentCaptureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensation);
            }

            float effectiveFocus;
            if (manualFocusDistance >= 0f) {
                effectiveFocus = manualFocusDistance;
            } else {
                effectiveFocus = getOptimalAstroFocusDistance();
            }

            currentCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            currentCaptureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, effectiveFocus);

            // Apply Zoom to still capture
            applyZoom(currentCaptureBuilder);

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
                long totalMs = (totalRequestedExposureNs == -2L) ? 0 :
                        (totalRequestedExposureNs > 0 ? (totalRequestedExposureNs / 1_000_000L) : 0);
                boolean timeExpired = (totalMs > 0 && elapsedMs >= totalMs);

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onStackProgress(capturedFramesCount, -1, elapsedMs, totalMs);
                    }
                });

                if (!cancelOrFinishEarly && !timeExpired) {
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

    public void setAiOptions(boolean denoise, boolean satelliteFilter, boolean skyGround) {
        this.isAiDenoiseEnabled = denoise;
        this.isAiSatelliteFilterEnabled = satelliteFilter;
        this.isAiSkyGroundEnabled = skyGround;
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

    public float getOptimalAstroFocusDistance() {
        if (capabilities == null) return 0.0f;
        // 0.0f diopters is optical infinity (1 / infinity = 0)
        if (capabilities.hyperfocalDistance != null && capabilities.hyperfocalDistance > 0f && capabilities.hyperfocalDistance <= 0.05f) {
            return capabilities.hyperfocalDistance;
        }
        return 0.0f;
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

    /**
     * Applies optical/digital zoom to capture request builder (0.5x to 10.0x).
     */
    private void applyZoom(CaptureRequest.Builder builder) {
        if (builder == null || capabilities == null) return;

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && capabilities.zoomRange != null) {
                float clamped = Math.max(capabilities.zoomRange.getLower(), Math.min(currentZoom, capabilities.zoomRange.getUpper()));
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped);
            } else if (capabilities.activeArraySize != null) {
                float effectiveZoom = Math.max(1.0f, Math.min(currentZoom, capabilities.maxZoom));
                applyCropRegionZoom(builder, capabilities.activeArraySize, effectiveZoom);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying zoom", e);
        }
    }

    private void applyCropRegionZoom(CaptureRequest.Builder builder, Rect activeArray, float zoomFactor) {
        if (builder == null || activeArray == null || zoomFactor <= 0f) return;

        int fullW = activeArray.width();
        int fullH = activeArray.height();
        if (fullW <= 0 || fullH <= 0) return;

        int cropW = Math.max(160, Math.min(fullW, (int) (fullW / zoomFactor)));
        int cropH = Math.max(160, Math.min(fullH, (int) (fullH / zoomFactor)));

        int cropX = activeArray.left + (fullW - cropW) / 2;
        int cropY = activeArray.top + (fullH - cropH) / 2;
        Rect cropRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect);
    }

    /**
     * Sets zoom level with smooth preview update and listener callback.
     */
    public void setZoom(float zoomRatio) {
        float min = 0.5f;
        float max = 10.0f;
        this.currentZoom = Math.max(min, Math.min(zoomRatio, max));

        // Auto-switch between Ultra-Wide camera ID and Main Back camera ID if they are separate physical cameras
        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            if (currentZoom <= 0.6f && ultraWideCameraId != null && !ultraWideCameraId.equals(cameraId)) {
                switchCameraTo(ultraWideCameraId);
            } else if (currentZoom >= 0.8f && ultraWideCameraId != null && ultraWideCameraId.equals(cameraId) && mainBackCameraId != null) {
                switchCameraTo(mainBackCameraId);
            }
        }

        // Notify UI immediately so badge and HUD update at full 60/120fps with zero latency
        if (listener != null) {
            mainHandler.post(() -> listener.onZoomUpdated(currentZoom, min, max));
        }

        // Rate-limit request to backgroundHandler to prevent Camera2 HAL message queue congestion
        if (isZoomUpdatePending.compareAndSet(false, true)) {
            backgroundHandler.post(() -> {
                isZoomUpdatePending.set(false);
                if (captureSession != null && previewRequestBuilder != null && cameraDevice != null) {
                    try {
                        applyZoom(previewRequestBuilder);
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), previewCaptureCallback, backgroundHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating zoom on session", e);
                    }
                }
            });
        }
    }

    public float getCurrentZoom() {
        return currentZoom;
    }

    public float getMinZoom() {
        return 0.5f;
    }

    public float getMaxZoom() {
        return 10.0f;
    }
}
