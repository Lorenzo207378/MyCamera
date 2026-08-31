package com.example.mycamera;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements
        AstroCameraController.CameraEventListener,
        OrientationSensorHelper.OrientationListener {

    private enum CameraMode {
        PHOTO,         // Simple stock-like camera mode (Default)
        ASTRO,         // Deep Sky Astrophotography
        NIGHT,         // Night landscape
        STAR_TRAILS,   // Continuous star trails integration
        MANUAL         // Manual / Pro mode
    }

    private enum ManualTab {
        SHUTTER,
        ISO,
        FOCUS,
        EV,
        ZOOM
    }

    // UI Elements
    private AutoFitTextureView textureView;
    private CameraOverlayView overlayView;
    private LinearLayout topBar;
    private ImageButton btnFlash;
    private ImageButton btnTimer;
    private TextView tvTimerBadge;
    private ImageButton btnGrid;
    private TextView btnStackMode;
    private ImageButton btnSettings;
    private ImageButton btnSwitchCamera;

    private LinearLayout hudPill;
    private TextView hudShutter;
    private TextView hudIso;
    private TextView hudFocus;
    private TextView hudZoom;
    private TextView tvTipBanner;

    // Quick Zoom Switcher & Live Zoom Badge
    private LinearLayout layoutQuickZoom;
    private TextView btnZoom05;
    private TextView btnZoom1x;
    private TextView btnZoom2x;
    private TextView btnZoom5x;
    private TextView btnZoom10x;
    private TextView tvLiveZoomBadge;
    private ScaleGestureDetector scaleGestureDetector;
    private final Handler zoomBadgeHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideZoomBadgeRunnable = () -> {
        if (tvLiveZoomBadge != null) tvLiveZoomBadge.setVisibility(View.GONE);
    };

    private CardView panelManualControls;
    private TextView tabShutter;
    private TextView tabIso;
    private TextView tabFocus;
    private TextView tabEv;
    private TextView tabZoom;
    private TextView tvParamValue;
    private Slider sliderParam;
    private LinearLayout layoutQuickChips;

    private TextView chipModePhoto;
    private TextView chipModeAstro;
    private TextView chipModeNight;
    private TextView chipModeStarTrails;
    private TextView chipModePro;

    private ShapeableImageView ivGalleryThumbnail;
    private FrameLayout btnShutterContainer;
    private ImageView ivShutterIcon;
    private ImageButton btnToggleManualPanel;

    private View layoutCaptureOverlay;
    private View layoutTopTripodProgress;
    private TextView tvTopTripodCountdown;
    private TextView tvTopTripodSubframe;
    private CircularProgressIndicator captureProgressBar;
    private TextView tvCaptureCountdown;
    private TextView tvCaptureStatus;
    private TextView tvCaptureSubframe;
    private TextView tvCaptureHint;
    private com.google.android.material.button.MaterialButton btnStopCapture;
    private ProgressBar pbSavingProgress;

    // Preferences & Settings
    private SharedPreferences sharedPreferences;
    private static final String PREF_TOP_TRIPOD_TIMER = "pref_top_tripod_timer";
    private static final String PREF_AI_DENOISING = "pref_ai_denoising";
    private static final String PREF_AI_SATELLITE_FILTER = "pref_ai_satellite_filter";
    private static final String PREF_AI_SKY_GROUND = "pref_ai_sky_ground";

    private boolean isTopTripodTimerEnabled = true;
    private boolean isAiDenoiseEnabled = true;
    private boolean isAiSatelliteFilterEnabled = true;
    private boolean isAiSkyGroundEnabled = true;

    // Controllers & Helpers
    private AstroCameraController cameraController;
    private OrientationSensorHelper orientationSensorHelper;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long captureStartTimestamp = 0;
    private long captureTotalDurationMs = 0;
    private int captureTotalFrames = 1;

    private final Runnable exposureTickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (layoutCaptureOverlay.getVisibility() != View.VISIBLE) return;

            long elapsedMs = System.currentTimeMillis() - captureStartTimestamp;
            String elapsedStr = formatDuration(elapsedMs);

            if (captureTotalFrames == 9999) {
                tvCaptureCountdown.setText(elapsedStr);
                tvCaptureStatus.setText("Posa B (Bulb) in corso…");
                if (isTopTripodTimerEnabled && tvTopTripodCountdown != null) {
                    tvTopTripodCountdown.setText("⏳ " + elapsedStr + " (Posa B)");
                }
            } else if (captureTotalDurationMs > 0) {
                String totalStr = formatDuration(captureTotalDurationMs);
                tvCaptureCountdown.setText(elapsedStr + " / " + totalStr);
                int percent = (int) Math.min(99, (elapsedMs * 100) / captureTotalDurationMs);
                captureProgressBar.setProgress(percent);
                tvCaptureStatus.setText("Esposizione astronomica in corso (" + percent + "%)");
                if (isTopTripodTimerEnabled && tvTopTripodCountdown != null) {
                    long remMs = Math.max(0, captureTotalDurationMs - elapsedMs);
                    String remStr = formatDuration(remMs);
                    tvTopTripodCountdown.setText("⏳ " + elapsedStr + " / " + totalStr + " (Rimanente: " + remStr + ")");
                }
            }

            mainHandler.postDelayed(this, 300);
        }
    };

    private String formatDuration(long millis) {
        long seconds = millis / 1000L;
        if (seconds < 60) {
            return seconds + "s";
        }
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format(Locale.getDefault(), "%dm %02ds", m, s);
    }

    // State variables
    private CameraMode currentMode = CameraMode.PHOTO;
    private ManualTab currentTab = ManualTab.SHUTTER;
    private int selfTimerSeconds = 0; // 0 = off, 2, 5, 10
    private boolean isGridEnabled = false;
    private Uri lastCapturedUri = null;

    // Exposure Presets List (nanoseconds)
    private final List<ExposureStep> exposureSteps = new ArrayList<>();
    private final List<Integer> isoSteps = new ArrayList<>();
    private final List<FocusStep> focusSteps = new ArrayList<>();

    private static class ExposureStep {
        final String label;
        final long nanoseconds; // -1 for Auto

        ExposureStep(String label, long nanoseconds) {
            this.label = label;
            this.nanoseconds = nanoseconds;
        }
    }

    private static class FocusStep {
        final String label;
        final float diopters; // -1 for Auto, 0.0 for Infinity

        FocusStep(String label, float diopters) {
            this.label = label;
            this.diopters = diopters;
        }
    }

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    initCamera();
                } else {
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("MyCameraPrefs", MODE_PRIVATE);
        isTopTripodTimerEnabled = sharedPreferences.getBoolean(PREF_TOP_TRIPOD_TIMER, true);
        isAiDenoiseEnabled = sharedPreferences.getBoolean(PREF_AI_DENOISING, true);
        isAiSatelliteFilterEnabled = sharedPreferences.getBoolean(PREF_AI_SATELLITE_FILTER, true);
        isAiSkyGroundEnabled = sharedPreferences.getBoolean(PREF_AI_SKY_GROUND, true);

        initViews();
        buildPresetTables();
        setupListeners();

        orientationSensorHelper = new OrientationSensorHelper(this, this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void initViews() {
        textureView = findViewById(R.id.textureView);
        overlayView = findViewById(R.id.overlayView);
        topBar = findViewById(R.id.topBar);
        btnFlash = findViewById(R.id.btnFlash);
        btnTimer = findViewById(R.id.btnTimer);
        tvTimerBadge = findViewById(R.id.tvTimerBadge);
        btnGrid = findViewById(R.id.btnGrid);
        btnStackMode = findViewById(R.id.btnStackMode);
        btnSettings = findViewById(R.id.btnSettings);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);

        hudPill = findViewById(R.id.hudPill);
        hudShutter = findViewById(R.id.hudShutter);
        hudIso = findViewById(R.id.hudIso);
        hudFocus = findViewById(R.id.hudFocus);
        hudZoom = findViewById(R.id.hudZoom);
        tvTipBanner = findViewById(R.id.tvTipBanner);

        layoutQuickZoom = findViewById(R.id.layoutQuickZoom);
        btnZoom05 = findViewById(R.id.btnZoom05);
        btnZoom1x = findViewById(R.id.btnZoom1x);
        btnZoom2x = findViewById(R.id.btnZoom2x);
        btnZoom5x = findViewById(R.id.btnZoom5x);
        btnZoom10x = findViewById(R.id.btnZoom10x);
        tvLiveZoomBadge = findViewById(R.id.tvLiveZoomBadge);

        panelManualControls = findViewById(R.id.panelManualControls);
        tabShutter = findViewById(R.id.tabShutter);
        tabIso = findViewById(R.id.tabIso);
        tabFocus = findViewById(R.id.tabFocus);
        tabEv = findViewById(R.id.tabEv);
        tabZoom = findViewById(R.id.tabZoom);
        tvParamValue = findViewById(R.id.tvParamValue);
        sliderParam = findViewById(R.id.sliderParam);
        layoutQuickChips = findViewById(R.id.layoutQuickChips);

        chipModePhoto = findViewById(R.id.chipModePhoto);
        chipModeAstro = findViewById(R.id.chipModeAstro);
        chipModeNight = findViewById(R.id.chipModeNight);
        chipModeStarTrails = findViewById(R.id.chipModeStarTrails);
        chipModePro = findViewById(R.id.chipModePro);

        ivGalleryThumbnail = findViewById(R.id.ivGalleryThumbnail);
        btnShutterContainer = findViewById(R.id.btnShutterContainer);
        ivShutterIcon = findViewById(R.id.ivShutterIcon);
        btnToggleManualPanel = findViewById(R.id.btnToggleManualPanel);

        layoutCaptureOverlay = findViewById(R.id.layoutCaptureOverlay);
        layoutTopTripodProgress = findViewById(R.id.layoutTopTripodProgress);
        tvTopTripodCountdown = findViewById(R.id.tvTopTripodCountdown);
        tvTopTripodSubframe = findViewById(R.id.tvTopTripodSubframe);
        captureProgressBar = findViewById(R.id.captureProgressBar);
        tvCaptureCountdown = findViewById(R.id.tvCaptureCountdown);
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus);
        tvCaptureSubframe = findViewById(R.id.tvCaptureSubframe);
        tvCaptureHint = findViewById(R.id.tvCaptureHint);
        btnStopCapture = findViewById(R.id.btnStopCapture);
        pbSavingProgress = findViewById(R.id.pbSavingProgress);
    }

    private void buildPresetTables() {
        exposureSteps.clear();
        exposureSteps.add(new ExposureStep("Auto", -1));
        exposureSteps.add(new ExposureStep("1/4000s", 250_000L));
        exposureSteps.add(new ExposureStep("1/2000s", 500_000L));
        exposureSteps.add(new ExposureStep("1/1000s", 1_000_000L));
        exposureSteps.add(new ExposureStep("1/500s", 2_000_000L));
        exposureSteps.add(new ExposureStep("1/250s", 4_000_000L));
        exposureSteps.add(new ExposureStep("1/125s", 8_000_000L));
        exposureSteps.add(new ExposureStep("1/60s", 16_666_666L));
        exposureSteps.add(new ExposureStep("1/30s", 33_333_333L));
        exposureSteps.add(new ExposureStep("1/15s", 66_666_666L));
        exposureSteps.add(new ExposureStep("1/8s", 125_000_000L));
        exposureSteps.add(new ExposureStep("1/4s", 250_000_000L));
        exposureSteps.add(new ExposureStep("1/2s", 500_000_000L));
        exposureSteps.add(new ExposureStep("1s", 1_000_000_000L));
        exposureSteps.add(new ExposureStep("2s", 2_000_000_000L));
        exposureSteps.add(new ExposureStep("4s", 4_000_000_000L));
        exposureSteps.add(new ExposureStep("8s", 8_000_000_000L));
        exposureSteps.add(new ExposureStep("15s", 15_000_000_000L));
        exposureSteps.add(new ExposureStep("30s", 30_000_000_000L));
        exposureSteps.add(new ExposureStep("45s", 45_000_000_000L));
        exposureSteps.add(new ExposureStep("1 min (60s)", 60_000_000_000L));
        exposureSteps.add(new ExposureStep("1.5 min (90s)", 90_000_000_000L));
        exposureSteps.add(new ExposureStep("2 min (120s)", 120_000_000_000L));
        exposureSteps.add(new ExposureStep("3 min (180s)", 180_000_000_000L));
        exposureSteps.add(new ExposureStep("4 min (240s)", 240_000_000_000L));
        exposureSteps.add(new ExposureStep("5 min (300s)", 300_000_000_000L));
        exposureSteps.add(new ExposureStep("♾️ Bulb (Posa B)", -2L));

        isoSteps.clear();
        isoSteps.add(-1); // Auto
        isoSteps.add(50);
        isoSteps.add(100);
        isoSteps.add(200);
        isoSteps.add(400);
        isoSteps.add(800);
        isoSteps.add(1600);
        isoSteps.add(3200);
        isoSteps.add(6400);

        focusSteps.clear();
        focusSteps.add(new FocusStep("Auto Focus", -1f));
        focusSteps.add(new FocusStep("Infinito (∞)", 0.0f));
        focusSteps.add(new FocusStep("5.0 m", 0.2f));
        focusSteps.add(new FocusStep("2.0 m", 0.5f));
        focusSteps.add(new FocusStep("1.0 m", 1.0f));
        focusSteps.add(new FocusStep("0.5 m", 2.0f));
        focusSteps.add(new FocusStep("0.2 m", 5.0f));
        focusSteps.add(new FocusStep("Macro", 10.0f));
    }

    private void initCamera() {
        cameraController = new AstroCameraController(this, textureView, this);
        cameraController.setAiOptions(isAiDenoiseEnabled, isAiSatelliteFilterEnabled, isAiSkyGroundEnabled);
        cameraController.startCamera();
        // Default mode is standard simple Photo mode like factory camera apps
        applyMode(CameraMode.PHOTO);
    }

    private void setupListeners() {
        chipModePhoto.setOnClickListener(v -> applyMode(CameraMode.PHOTO));
        chipModeAstro.setOnClickListener(v -> applyMode(CameraMode.ASTRO));
        chipModeNight.setOnClickListener(v -> applyMode(CameraMode.NIGHT));
        chipModeStarTrails.setOnClickListener(v -> applyMode(CameraMode.STAR_TRAILS));
        chipModePro.setOnClickListener(v -> applyMode(CameraMode.MANUAL));

        tabShutter.setOnClickListener(v -> selectManualTab(ManualTab.SHUTTER));
        tabIso.setOnClickListener(v -> selectManualTab(ManualTab.ISO));
        tabFocus.setOnClickListener(v -> selectManualTab(ManualTab.FOCUS));
        tabEv.setOnClickListener(v -> selectManualTab(ManualTab.EV));
        tabZoom.setOnClickListener(v -> selectManualTab(ManualTab.ZOOM));

        // Quick zoom button listeners
        btnZoom05.setOnClickListener(v -> {
            if (cameraController != null) cameraController.switchToUltraWide();
        });
        btnZoom1x.setOnClickListener(v -> {
            if (cameraController != null) {
                cameraController.switchToMainCamera();
                cameraController.setZoom(1.0f);
            }
        });
        btnZoom2x.setOnClickListener(v -> {
            if (cameraController != null) {
                cameraController.switchToMainCamera();
                cameraController.setZoom(2.0f);
            }
        });
        btnZoom5x.setOnClickListener(v -> {
            if (cameraController != null) {
                cameraController.switchToMainCamera();
                cameraController.setZoom(5.0f);
            }
        });
        btnZoom10x.setOnClickListener(v -> {
            if (cameraController != null) {
                cameraController.switchToMainCamera();
                cameraController.setZoom(10.0f);
            }
        });

        btnToggleManualPanel.setOnClickListener(v -> {
            boolean isVisible = panelManualControls.getVisibility() == View.VISIBLE;
            panelManualControls.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            btnToggleManualPanel.setBackgroundResource(
                    isVisible ? R.drawable.bg_chip_unselected : R.drawable.bg_chip_selected
            );
            if (!isVisible) {
                selectManualTab(currentTab);
            }
        });

        btnShutterContainer.setOnClickListener(v -> triggerCaptureSequence());

        btnFlash.setOnClickListener(v -> toggleFlash());
        btnTimer.setOnClickListener(v -> toggleTimer());
        btnGrid.setOnClickListener(v -> toggleGridAndLevel());
        btnStackMode.setOnClickListener(v -> toggleStackMode());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        btnStopCapture.setOnClickListener(v -> {
            mainHandler.removeCallbacks(exposureTickerRunnable);
            btnStopCapture.setEnabled(false);
            btnStopCapture.setText("⏳ Salvataggio in corso…");
            btnStopCapture.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.bg_dark_card)));
            btnStopCapture.setTextColor(getColor(R.color.astro_cyan));
            if (pbSavingProgress != null) {
                pbSavingProgress.setVisibility(View.VISIBLE);
            }

            long elapsedMs = System.currentTimeMillis() - captureStartTimestamp;
            String elapsedStr = formatDuration(elapsedMs);
            if (captureTotalFrames == 9999) {
                tvCaptureCountdown.setText(elapsedStr);
                if (isTopTripodTimerEnabled && tvTopTripodCountdown != null) {
                    tvTopTripodCountdown.setText("⏳ " + elapsedStr + " • Salvataggio...");
                }
            } else if (captureTotalDurationMs > 0) {
                String totalStr = formatDuration(captureTotalDurationMs);
                tvCaptureCountdown.setText(elapsedStr + " / " + totalStr);
                if (isTopTripodTimerEnabled && tvTopTripodCountdown != null) {
                    tvTopTripodCountdown.setText("⏳ " + elapsedStr + " / " + totalStr + " • Salvataggio...");
                }
            }

            tvCaptureStatus.setText("Elaborazione e fusione fotogrammi…");
            tvCaptureHint.setText("Attendere il completamento del salvataggio in galleria…");
            if (cameraController != null) {
                cameraController.stopCaptureAndSave();
            }
        });
        btnSwitchCamera.setOnClickListener(v -> {
            if (cameraController != null) {
                cameraController.switchCamera();
            }
        });

        ivGalleryThumbnail.setOnClickListener(v -> openGalleryOrLastImage());

        // Setup Pinch-to-Zoom ScaleGestureDetector
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (cameraController == null) return false;
                float scaleFactor = detector.getScaleFactor();
                float targetZoom = cameraController.getCurrentZoom() * scaleFactor;
                cameraController.setZoom(targetZoom);
                return true;
            }
        });

        textureView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getPointerCount() == 1 && event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX();
                float y = event.getY();
                overlayView.showFocusRing(x, y);
                if (cameraController != null) {
                    cameraController.focusOnPoint(x, y);
                }
            }
            return true;
        });

        sliderParam.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            handleSliderChange((int) value);
        });
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        MaterialSwitch switchTopTripodTimer = dialogView.findViewById(R.id.switchTopTripodTimer);
        MaterialSwitch switchAiDenoising = dialogView.findViewById(R.id.switchAiDenoising);
        MaterialSwitch switchAiSatelliteFilter = dialogView.findViewById(R.id.switchAiSatelliteFilter);
        MaterialSwitch switchAiSkyGround = dialogView.findViewById(R.id.switchAiSkyGround);

        ImageButton btnCloseSettings = dialogView.findViewById(R.id.btnCloseSettings);
        com.google.android.material.button.MaterialButton btnDoneSettings = dialogView.findViewById(R.id.btnDoneSettings);

        switchTopTripodTimer.setChecked(isTopTripodTimerEnabled);
        switchTopTripodTimer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isTopTripodTimerEnabled = isChecked;
            sharedPreferences.edit().putBoolean(PREF_TOP_TRIPOD_TIMER, isChecked).apply();
        });

        if (switchAiDenoising != null) {
            switchAiDenoising.setChecked(isAiDenoiseEnabled);
            switchAiDenoising.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isAiDenoiseEnabled = isChecked;
                sharedPreferences.edit().putBoolean(PREF_AI_DENOISING, isChecked).apply();
                if (cameraController != null) cameraController.setAiOptions(isAiDenoiseEnabled, isAiSatelliteFilterEnabled, isAiSkyGroundEnabled);
            });
        }

        if (switchAiSatelliteFilter != null) {
            switchAiSatelliteFilter.setChecked(isAiSatelliteFilterEnabled);
            switchAiSatelliteFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isAiSatelliteFilterEnabled = isChecked;
                sharedPreferences.edit().putBoolean(PREF_AI_SATELLITE_FILTER, isChecked).apply();
                if (cameraController != null) cameraController.setAiOptions(isAiDenoiseEnabled, isAiSatelliteFilterEnabled, isAiSkyGroundEnabled);
            });
        }

        if (switchAiSkyGround != null) {
            switchAiSkyGround.setChecked(isAiSkyGroundEnabled);
            switchAiSkyGround.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isAiSkyGroundEnabled = isChecked;
                sharedPreferences.edit().putBoolean(PREF_AI_SKY_GROUND, isChecked).apply();
                if (cameraController != null) cameraController.setAiOptions(isAiDenoiseEnabled, isAiSatelliteFilterEnabled, isAiSkyGroundEnabled);
            });
        }

        btnCloseSettings.setOnClickListener(v -> dialog.dismiss());
        btnDoneSettings.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void applyMode(CameraMode mode) {
        this.currentMode = mode;

        chipModePhoto.setBackgroundResource(mode == CameraMode.PHOTO ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chipModePhoto.setTextColor(mode == CameraMode.PHOTO ? getColor(R.color.black) : getColor(R.color.white));

        chipModeAstro.setBackgroundResource(mode == CameraMode.ASTRO ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chipModeAstro.setTextColor(mode == CameraMode.ASTRO ? getColor(R.color.black) : getColor(R.color.white));

        chipModeNight.setBackgroundResource(mode == CameraMode.NIGHT ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chipModeNight.setTextColor(mode == CameraMode.NIGHT ? getColor(R.color.black) : getColor(R.color.white));

        chipModeStarTrails.setBackgroundResource(mode == CameraMode.STAR_TRAILS ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chipModeStarTrails.setTextColor(mode == CameraMode.STAR_TRAILS ? getColor(R.color.black) : getColor(R.color.white));

        chipModePro.setBackgroundResource(mode == CameraMode.MANUAL ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chipModePro.setTextColor(mode == CameraMode.MANUAL ? getColor(R.color.black) : getColor(R.color.white));

        if (cameraController == null) return;

        switch (mode) {
            case PHOTO:
                // Standard Simple Photo Mode (Default): full auto, instant capture
                cameraController.setManualExposureNs(-1);
                cameraController.setManualIso(-1);
                cameraController.setManualFocusDistance(-1f);
                selfTimerSeconds = 0;
                updateTimerUI();
                tvTipBanner.setVisibility(View.GONE);
                panelManualControls.setVisibility(View.GONE);
                btnToggleManualPanel.setBackgroundResource(R.drawable.bg_chip_unselected);
                ivShutterIcon.setImageResource(R.drawable.ic_auto_mode);
                break;

            case ASTRO:
                // Astro Mode: 2 minutes exposure integration, maximum sensor sensitivity, calibrated infinity focus
                cameraController.setManualExposureNs(120_000_000_000L); // 2 minuti (120s)
                cameraController.setManualIso(-1); // Automatically chooses max clean ISO for sensor
                cameraController.setManualFocusDistance(cameraController.getOptimalAstroFocusDistance());
                cameraController.setStackingMode(ImageStacker.StackingMode.DEEP_SKY_INTEGRATION);
                selfTimerSeconds = 3;
                updateTimerUI();
                updateStackModeUI(cameraController.getStackingMode());
                tvTipBanner.setText("🌌 Deep Sky: Integrazione multi-frame Asinh • Stelle puntiformi");
                tvTipBanner.setVisibility(View.VISIBLE);
                panelManualControls.setVisibility(View.GONE);
                btnToggleManualPanel.setBackgroundResource(R.drawable.bg_chip_unselected);
                ivShutterIcon.setImageResource(R.drawable.ic_astro_mode);
                break;

            case NIGHT:
                // Night Landscape: 4s exposure, ISO 800.
                // Focus: auto AF (not fixed infinity) since night landscapes often have subjects
                // at intermediate distances (buildings, trees at 20–200m), not purely at infinity.
                cameraController.setManualExposureNs(4_000_000_000L); // 4 seconds
                cameraController.setManualIso(800);
                cameraController.setManualFocusDistance(-1f); // AF auto: let the camera decide
                cameraController.setStackingMode(ImageStacker.StackingMode.DEEP_SKY_INTEGRATION);
                selfTimerSeconds = 2;
                updateTimerUI();
                tvTipBanner.setText("🌃 Notte Paesaggio: Esposizione 4s • ISO 800 • Fuoco Infinito");
                tvTipBanner.setVisibility(View.VISIBLE);
                panelManualControls.setVisibility(View.GONE);
                btnToggleManualPanel.setBackgroundResource(R.drawable.bg_chip_unselected);
                ivShutterIcon.setImageResource(R.drawable.ic_night_mode);
                break;

            case STAR_TRAILS:
                // Star Trails Mode: Continuous Max-Lightness Integration for star arches & Earth rotation.
                // 30 min (1800s) is the astrophotography standard minimum for photogenic arcs.
                // Sub-frame = 25s (set in AstroCameraController): prevents dark current and sky saturation.
                cameraController.setManualExposureNs(1_800_000_000_000L); // 30 min default
                cameraController.setManualIso(800);
                cameraController.setManualFocusDistance(cameraController.getOptimalAstroFocusDistance());
                cameraController.setStackingMode(ImageStacker.StackingMode.STAR_TRAILS);
                selfTimerSeconds = 3;
                updateTimerUI();
                updateStackModeUI(ImageStacker.StackingMode.STAR_TRAILS);
                tvTipBanner.setText("🌠 Scie Stellari: 30 min • Sub-frame 25s • Treppiede indispensabile");
                tvTipBanner.setVisibility(View.VISIBLE);
                panelManualControls.setVisibility(View.GONE);
                btnToggleManualPanel.setBackgroundResource(R.drawable.bg_chip_unselected);
                ivShutterIcon.setImageResource(R.drawable.ic_astro_mode);
                break;

            case MANUAL:
                panelManualControls.setVisibility(View.VISIBLE);
                btnToggleManualPanel.setBackgroundResource(R.drawable.bg_chip_selected);
                selectManualTab(ManualTab.SHUTTER);
                tvTipBanner.setVisibility(View.GONE);
                ivShutterIcon.setImageResource(R.drawable.ic_tune);
                break;
        }
    }

    private void selectManualTab(ManualTab tab) {
        this.currentTab = tab;

        tabShutter.setBackgroundResource(tab == ManualTab.SHUTTER ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tabShutter.setTextColor(tab == ManualTab.SHUTTER ? getColor(R.color.black) : getColor(R.color.white));

        tabIso.setBackgroundResource(tab == ManualTab.ISO ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tabIso.setTextColor(tab == ManualTab.ISO ? getColor(R.color.black) : getColor(R.color.white));

        tabFocus.setBackgroundResource(tab == ManualTab.FOCUS ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tabFocus.setTextColor(tab == ManualTab.FOCUS ? getColor(R.color.black) : getColor(R.color.white));

        tabEv.setBackgroundResource(tab == ManualTab.EV ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tabEv.setTextColor(tab == ManualTab.EV ? getColor(R.color.black) : getColor(R.color.white));

        tabZoom.setBackgroundResource(tab == ManualTab.ZOOM ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tabZoom.setTextColor(tab == ManualTab.ZOOM ? getColor(R.color.black) : getColor(R.color.white));

        layoutQuickChips.removeAllViews();

        switch (tab) {
            case SHUTTER:
                setupShutterTab();
                break;
            case ISO:
                setupIsoTab();
                break;
            case FOCUS:
                setupFocusTab();
                break;
            case EV:
                setupEvTab();
                break;
            case ZOOM:
                setupZoomTab();
                break;
        }
    }

    private void setupZoomTab() {
        float min = (cameraController != null) ? cameraController.getMinZoom() : 0.5f;
        float max = (cameraController != null) ? cameraController.getMaxZoom() : 10.0f;
        int minInt = Math.round(min * 10f); // 5
        int maxInt = Math.round(max * 10f); // 100

        sliderParam.setTrackActiveTintList(ColorStateList.valueOf(getColor(R.color.astro_cyan)));
        sliderParam.setThumbTintList(ColorStateList.valueOf(getColor(R.color.astro_cyan)));

        float cur = (cameraController != null) ? cameraController.getCurrentZoom() : 1.0f;
        int activeVal = Math.min(maxInt, Math.max(minInt, Math.round(cur * 10f)));

        try {
            sliderParam.setValue(Math.min(sliderParam.getValueTo(), Math.max(sliderParam.getValueFrom(), minInt)));
            sliderParam.setValueFrom(minInt);
            sliderParam.setValueTo(maxInt);
            sliderParam.setStepSize(1f);
            sliderParam.setValue(activeVal);
        } catch (Exception e) {
            Log.e("MainActivity", "Error setting standard slider values", e);
        }

        tvParamValue.setText(String.format(Locale.getDefault(), "Zoom: %.1fx", cur));
        tvParamValue.setTextColor(getColor(R.color.astro_cyan));

        float[] zoomSteps = {0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f};
        String[] zoomLabels = {"0.5x (Grandangolo)", "1.0x (1x)", "2.0x (2x)", "3.0x (3x)", "5.0x (Tele)", "10.0x (Max)"};

        for (int i = 0; i < zoomSteps.length; i++) {
            final float z = zoomSteps[i];
            String label = zoomLabels[i];
            boolean isSelected = Math.abs(cur - z) < 0.15f;
            TextView chip = createChip(label, isSelected);
            chip.setOnClickListener(v -> {
                if (cameraController != null) {
                    if (z == 0.5f) {
                        cameraController.switchToUltraWide();
                    } else {
                        cameraController.switchToMainCamera();
                        cameraController.setZoom(z);
                    }
                    try {
                        sliderParam.setValue(Math.round(z * 10f));
                    } catch (Exception ignored) {}
                }
            });
            layoutQuickChips.addView(chip);
        }
    }

    private void setupShutterTab() {
        sliderParam.setValueFrom(0);
        sliderParam.setValueTo(exposureSteps.size() - 1);
        sliderParam.setStepSize(1);

        long currentExp = cameraController != null ? cameraController.getManualExposureNs() : -1;
        int activeIdx = 0;
        for (int i = 0; i < exposureSteps.size(); i++) {
            if (exposureSteps.get(i).nanoseconds == currentExp) {
                activeIdx = i;
                break;
            }
        }
        sliderParam.setValue(activeIdx);
        tvParamValue.setText("Esposizione: " + exposureSteps.get(activeIdx).label);

        for (int i = 0; i < exposureSteps.size(); i++) {
            final int index = i;
            final ExposureStep step = exposureSteps.get(i);
            TextView chip = createChip(step.label, index == activeIdx);
            chip.setOnClickListener(v -> {
                sliderParam.setValue(index);
                handleSliderChange(index);
            });
            layoutQuickChips.addView(chip);
        }
    }

    private void setupIsoTab() {
        sliderParam.setValueFrom(0);
        sliderParam.setValueTo(isoSteps.size() - 1);
        sliderParam.setStepSize(1);

        int currentIso = cameraController != null ? cameraController.getManualIso() : -1;
        int activeIdx = 0;
        for (int i = 0; i < isoSteps.size(); i++) {
            if (isoSteps.get(i) == currentIso) {
                activeIdx = i;
                break;
            }
        }
        sliderParam.setValue(activeIdx);
        String label = (isoSteps.get(activeIdx) == -1) ? "Auto" : "ISO " + isoSteps.get(activeIdx);
        tvParamValue.setText("Sensibilità: " + label);

        for (int i = 0; i < isoSteps.size(); i++) {
            final int index = i;
            final int iso = isoSteps.get(i);
            String chipText = (iso == -1) ? "Auto" : String.valueOf(iso);
            TextView chip = createChip(chipText, index == activeIdx);
            chip.setOnClickListener(v -> {
                sliderParam.setValue(index);
                handleSliderChange(index);
            });
            layoutQuickChips.addView(chip);
        }
    }

    private void setupFocusTab() {
        sliderParam.setValueFrom(0);
        sliderParam.setValueTo(focusSteps.size() - 1);
        sliderParam.setStepSize(1);

        float currentFocus = cameraController != null ? cameraController.getManualFocusDistance() : -1f;
        int activeIdx = 0;
        for (int i = 0; i < focusSteps.size(); i++) {
            if (Math.abs(focusSteps.get(i).diopters - currentFocus) < 0.01f) {
                activeIdx = i;
                break;
            }
        }
        sliderParam.setValue(activeIdx);
        tvParamValue.setText("Messa a Fuoco: " + focusSteps.get(activeIdx).label);

        for (int i = 0; i < focusSteps.size(); i++) {
            final int index = i;
            final FocusStep step = focusSteps.get(i);
            TextView chip = createChip(step.label, index == activeIdx);
            chip.setOnClickListener(v -> {
                sliderParam.setValue(index);
                handleSliderChange(index);
            });
            layoutQuickChips.addView(chip);
        }
    }

    private void setupEvTab() {
        sliderParam.setValueFrom(-6);
        sliderParam.setValueTo(6);
        sliderParam.setStepSize(1);
        sliderParam.setValue(0);
        tvParamValue.setText("Compensazione Esposizione: 0.0 EV");

        String[] evLabels = {"-2.0 EV", "-1.0 EV", "0.0 EV", "+1.0 EV", "+2.0 EV"};
        int[] evValues = {-6, -3, 0, 3, 6};
        for (int i = 0; i < evLabels.length; i++) {
            final int val = evValues[i];
            TextView chip = createChip(evLabels[i], val == 0);
            chip.setOnClickListener(v -> {
                sliderParam.setValue(val);
                handleSliderChange(val);
            });
            layoutQuickChips.addView(chip);
        }
    }

    private TextView createChip(String text, boolean isSelected) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(16);
        tv.setLayoutParams(lp);
        tv.setBackgroundResource(isSelected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tv.setTextColor(isSelected ? getColor(R.color.black) : getColor(R.color.white));
        return tv;
    }

    private void handleSliderChange(int index) {
        if (cameraController == null) return;

        switch (currentTab) {
            case SHUTTER:
                if (index >= 0 && index < exposureSteps.size()) {
                    ExposureStep step = exposureSteps.get(index);
                    tvParamValue.setText("Esposizione: " + step.label);
                    cameraController.setManualExposureNs(step.nanoseconds);
                    refreshQuickChipsSelection(index);
                }
                break;

            case ISO:
                if (index >= 0 && index < isoSteps.size()) {
                    int iso = isoSteps.get(index);
                    String label = (iso == -1) ? "Auto" : "ISO " + iso;
                    tvParamValue.setText("Sensibilità: " + label);
                    cameraController.setManualIso(iso);
                    refreshQuickChipsSelection(index);
                }
                break;

            case FOCUS:
                if (index >= 0 && index < focusSteps.size()) {
                    FocusStep step = focusSteps.get(index);
                    tvParamValue.setText("Messa a Fuoco: " + step.label);
                    cameraController.setManualFocusDistance(step.diopters);
                    refreshQuickChipsSelection(index);
                }
                break;

            case EV:
                float ev = index * 0.33f;
                tvParamValue.setText(String.format(Locale.getDefault(), "Compensazione: %+.1f EV", ev));
                cameraController.setAeCompensation(index);
                break;

            case ZOOM:
                float zoom = index / 10.0f;
                tvParamValue.setText(String.format(Locale.getDefault(), "Zoom: %.1fx", zoom));
                cameraController.setZoom(zoom);
                break;
        }
    }

    private void refreshQuickChipsSelection(int activeIndex) {
        int count = layoutQuickChips.getChildCount();
        for (int i = 0; i < count; i++) {
            View v = layoutQuickChips.getChildAt(i);
            if (v instanceof TextView) {
                boolean sel = (i == activeIndex);
                v.setBackgroundResource(sel ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
                ((TextView) v).setTextColor(sel ? getColor(R.color.black) : getColor(R.color.white));
            }
        }
    }

    private void triggerCaptureSequence() {
        if (cameraController == null) return;

        if (selfTimerSeconds > 0) {
            layoutCaptureOverlay.setVisibility(View.VISIBLE);
            tvCaptureStatus.setText("Autoscatto in corso…");
            captureProgressBar.setProgress(100);

            new CountDownTimer(selfTimerSeconds * 1000L, 1000L) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long remainingSec = (millisUntilFinished / 1000L) + 1;
                    tvCaptureCountdown.setText(remainingSec + "s");
                    int progress = (int) ((millisUntilFinished / (float) (selfTimerSeconds * 1000L)) * 100);
                    captureProgressBar.setProgress(progress);
                }

                @Override
                public void onFinish() {
                    tvCaptureCountdown.setText("0s");
                    cameraController.captureStillPicture();
                }
            }.start();
        } else {
            cameraController.captureStillPicture();
        }
    }

    private void toggleFlash() {
        if (cameraController == null) return;
        AstroCameraController.FlashMode current = cameraController.getFlashMode();
        AstroCameraController.FlashMode next = (current == AstroCameraController.FlashMode.OFF) ?
                AstroCameraController.FlashMode.TORCH : AstroCameraController.FlashMode.OFF;

        cameraController.setFlashMode(next);
        btnFlash.setImageResource(next == AstroCameraController.FlashMode.TORCH ?
                R.drawable.ic_flash_torch : R.drawable.ic_flash_off);
        btnFlash.setColorFilter(next == AstroCameraController.FlashMode.TORCH ?
                getColor(R.color.astro_amber) : getColor(R.color.white));
    }

    private void toggleTimer() {
        if (selfTimerSeconds == 0) {
            selfTimerSeconds = 2;
        } else if (selfTimerSeconds == 2) {
            selfTimerSeconds = 5;
        } else if (selfTimerSeconds == 5) {
            selfTimerSeconds = 10;
        } else {
            selfTimerSeconds = 0;
        }
        updateTimerUI();
    }

    private void updateTimerUI() {
        if (selfTimerSeconds > 0) {
            tvTimerBadge.setVisibility(View.VISIBLE);
            tvTimerBadge.setText(selfTimerSeconds + "s");
            btnTimer.setColorFilter(getColor(R.color.astro_cyan));
        } else {
            tvTimerBadge.setVisibility(View.GONE);
            btnTimer.setColorFilter(getColor(R.color.white));
        }
    }

    private void toggleGridAndLevel() {
        isGridEnabled = !isGridEnabled;
        overlayView.setShowGrid(isGridEnabled);
        btnGrid.setColorFilter(isGridEnabled ? getColor(R.color.astro_cyan) : getColor(R.color.white));
    }

    private void updateStackModeUI(ImageStacker.StackingMode mode) {
        if (btnStackMode == null) return;
        if (mode == ImageStacker.StackingMode.STAR_TRAILS) {
            btnStackMode.setText("🌠 Star Trails");
            btnStackMode.setTextColor(getColor(R.color.black));
            btnStackMode.setBackgroundResource(R.drawable.bg_chip_selected);
            if (currentMode == CameraMode.ASTRO) {
                tvTipBanner.setText("🌠 Star Trails: Fusione luminosa continua • Tracce Stellari • Treppiede fisso");
                tvTipBanner.setVisibility(View.VISIBLE);
            }
        } else {
            btnStackMode.setText("🌌 Deep Sky");
            btnStackMode.setTextColor(getColor(R.color.astro_cyan));
            btnStackMode.setBackgroundResource(R.drawable.bg_chip_unselected);
            if (currentMode == CameraMode.ASTRO) {
                tvTipBanner.setText("🌌 Modalità Astro 2m: Stacking integrato • Fuoco Infinito (∞) • Treppiede");
                tvTipBanner.setVisibility(View.VISIBLE);
            }
        }
    }

    private void toggleStackMode() {
        if (cameraController == null) return;
        ImageStacker.StackingMode current = cameraController.getStackingMode();
        ImageStacker.StackingMode next = (current == ImageStacker.StackingMode.DEEP_SKY_INTEGRATION) ?
                ImageStacker.StackingMode.STAR_TRAILS : ImageStacker.StackingMode.DEEP_SKY_INTEGRATION;

        cameraController.setStackingMode(next);
        updateStackModeUI(next);

        Toast.makeText(this, next == ImageStacker.StackingMode.STAR_TRAILS ?
                "🌠 Modalità Star Trails (Tracce Stellari attive)" :
                "🌌 Modalità Deep Sky (Integrazione & Denoise)", Toast.LENGTH_SHORT).show();
    }

    private void openGalleryOrLastImage() {
        if (lastCapturedUri != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(lastCapturedUri, "image/jpeg");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
                return;
            } catch (Exception e) {
                // Fallback to gallery intent
            }
        }

        Intent galleryIntent = new Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivity(galleryIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Nessuna app galleria trovata", Toast.LENGTH_SHORT).show();
        }
    }

    // CameraEventListener callbacks
    @Override
    public void onCameraReady(AstroCameraController.CameraCapabilities capabilities) {
        if (btnZoom05 != null) {
            btnZoom05.setVisibility(View.VISIBLE);
        }
        if (currentTab != null) {
            selectManualTab(currentTab);
        }
    }

    @Override
    public void onExposureUpdated(long exposureNs, int iso, float focusDistance) {
        // Update Shutter HUD
        if (exposureNs > 0) {
            if (exposureNs >= 1_000_000_000L) {
                double sec = exposureNs / 1_000_000_000.0;
                hudShutter.setText(String.format(Locale.getDefault(), "⏱ %.1fs", sec));
            } else {
                long denom = Math.round(1_000_000_000.0 / exposureNs);
                hudShutter.setText(String.format(Locale.getDefault(), "⏱ 1/%ds", denom));
            }
        } else {
            hudShutter.setText("⏱ Auto");
        }

        // Update ISO HUD
        if (iso > 0) {
            hudIso.setText("ISO " + iso);
        } else {
            hudIso.setText("ISO Auto");
        }

        // Update Focus HUD
        String focusStr;
        if (focusDistance == 0.0f) {
            focusStr = "🎯 ∞";
        } else if (focusDistance > 0) {
            float distMeters = 1.0f / focusDistance;
            focusStr = String.format(Locale.getDefault(), "🎯 %.1fm", distMeters);
        } else {
            focusStr = "🎯 AF";
        }
        hudFocus.setText(focusStr);
    }

    @Override
    public void onZoomUpdated(float currentZoom, float minZoom, float maxZoom) {
        hudZoom.setText(String.format(Locale.getDefault(), "🔍 %.1fx", currentZoom));

        // Update quick zoom pill buttons selection (.5, 1x, 2x, 5x, 10x)
        updateQuickZoomPillSelection(currentZoom);

        // Show live floating zoom badge with auto-hide animation
        tvLiveZoomBadge.setText(String.format(Locale.getDefault(), "%.1fx", currentZoom));
        tvLiveZoomBadge.setTextColor(getColor(R.color.astro_cyan));
        tvLiveZoomBadge.setVisibility(View.VISIBLE);
        zoomBadgeHandler.removeCallbacks(hideZoomBadgeRunnable);
        zoomBadgeHandler.postDelayed(hideZoomBadgeRunnable, 1500);

        if (currentTab == ManualTab.ZOOM) {
            tvParamValue.setText(String.format(Locale.getDefault(), "Zoom: %.1fx", currentZoom));
            int val = Math.round(currentZoom * 10f);
            if (!sliderParam.isPressed()) {
                try {
                    if (val >= (int) sliderParam.getValueFrom() && val <= (int) sliderParam.getValueTo()
                            && Math.round(sliderParam.getValue()) != val) {
                        sliderParam.setValue(val);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void updateQuickZoomPillSelection(float zoom) {
        btnZoom05.setBackgroundResource(Math.abs(zoom - 0.5f) < 0.2f ? R.drawable.bg_zoom_circle_selected : R.drawable.bg_zoom_circle_unselected);
        btnZoom05.setTextColor(Math.abs(zoom - 0.5f) < 0.2f ? getColor(R.color.black) : getColor(R.color.white));

        btnZoom1x.setBackgroundResource(Math.abs(zoom - 1.0f) < 0.3f ? R.drawable.bg_zoom_circle_selected : R.drawable.bg_zoom_circle_unselected);
        btnZoom1x.setTextColor(Math.abs(zoom - 1.0f) < 0.3f ? getColor(R.color.black) : getColor(R.color.white));

        btnZoom2x.setBackgroundResource(Math.abs(zoom - 2.0f) < 0.5f ? R.drawable.bg_zoom_circle_selected : R.drawable.bg_zoom_circle_unselected);
        btnZoom2x.setTextColor(Math.abs(zoom - 2.0f) < 0.5f ? getColor(R.color.black) : getColor(R.color.white));

        btnZoom5x.setBackgroundResource(Math.abs(zoom - 5.0f) < 1.0f ? R.drawable.bg_zoom_circle_selected : R.drawable.bg_zoom_circle_unselected);
        btnZoom5x.setTextColor(Math.abs(zoom - 5.0f) < 1.0f ? getColor(R.color.black) : getColor(R.color.white));

        btnZoom10x.setBackgroundResource(Math.abs(zoom - 10.0f) < 1.5f ? R.drawable.bg_zoom_circle_selected : R.drawable.bg_zoom_circle_unselected);
        btnZoom10x.setTextColor(Math.abs(zoom - 10.0f) < 1.5f ? getColor(R.color.black) : getColor(R.color.white));
    }

    @Override
    public void onCaptureStarted(long estimatedDurationMs, int totalFrames) {
        layoutCaptureOverlay.setVisibility(View.VISIBLE);
        tvCaptureStatus.setText("Esposizione astronomica in corso…");
        captureProgressBar.setProgress(0);

        captureStartTimestamp = System.currentTimeMillis();
        captureTotalDurationMs = estimatedDurationMs;
        captureTotalFrames = totalFrames;

        boolean isMultiFrame = (estimatedDurationMs > 0 || totalFrames == -1 || totalFrames > 1);

        if (isTopTripodTimerEnabled && layoutTopTripodProgress != null) {
            layoutTopTripodProgress.setVisibility(View.VISIBLE);
            tvTopTripodCountdown.setText(estimatedDurationMs > 0 ? ("⏳ 0s / " + formatDuration(estimatedDurationMs)) : "⏳ 0s (Posa B)");
            tvTopTripodSubframe.setText("Frame acquisiti: 1");
        } else if (layoutTopTripodProgress != null) {
            layoutTopTripodProgress.setVisibility(View.GONE);
        }

        if (isMultiFrame) {
            tvCaptureSubframe.setVisibility(View.VISIBLE);
            tvCaptureSubframe.setText("Frame acquisiti: 1 (Acquisizione continua)");
            btnStopCapture.setVisibility(View.VISIBLE);
            btnStopCapture.setEnabled(true);
            btnStopCapture.setText("⏹️ Termina e Salva Foto");
            btnStopCapture.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.astro_cyan)));
            btnStopCapture.setTextColor(getColor(R.color.black));
            if (pbSavingProgress != null) pbSavingProgress.setVisibility(View.GONE);
        } else {
            tvCaptureSubframe.setVisibility(View.GONE);
            btnStopCapture.setVisibility(View.GONE);
            if (pbSavingProgress != null) pbSavingProgress.setVisibility(View.GONE);
        }

        mainHandler.removeCallbacks(exposureTickerRunnable);
        mainHandler.post(exposureTickerRunnable);
    }

    @Override
    public void onStackProgress(int currentFrame, int totalFrames, long elapsedMs, long totalMs) {
        if (totalMs == 0) {
            tvCaptureSubframe.setText("Frame acquisiti: " + currentFrame + " • Tocca per terminare");
            if (isTopTripodTimerEnabled && tvTopTripodSubframe != null) {
                tvTopTripodSubframe.setText("Frame acquisiti: " + currentFrame + " (Posa continua)");
            }
        } else {
            tvCaptureSubframe.setText("Frame acquisiti: " + currentFrame + " (Acquisizione continua)");
            if (isTopTripodTimerEnabled && tvTopTripodSubframe != null) {
                tvTopTripodSubframe.setText("Frame elaborati: " + currentFrame);
            }
        }
    }

    @Override
    public void onCaptureCompleted(Uri imageUri, Bitmap thumbnail) {
        mainHandler.removeCallbacks(exposureTickerRunnable);
        if (pbSavingProgress != null) pbSavingProgress.setVisibility(View.GONE);
        if (layoutTopTripodProgress != null) layoutTopTripodProgress.setVisibility(View.GONE);
        layoutCaptureOverlay.setVisibility(View.GONE);
        this.lastCapturedUri = imageUri;

        if (thumbnail != null) {
            ivGalleryThumbnail.setImageBitmap(thumbnail);
        }
        Toast.makeText(this, R.string.photo_saved, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCaptureFailed(String errorMessage) {
        mainHandler.removeCallbacks(exposureTickerRunnable);
        if (pbSavingProgress != null) pbSavingProgress.setVisibility(View.GONE);
        if (layoutTopTripodProgress != null) layoutTopTripodProgress.setVisibility(View.GONE);
        layoutCaptureOverlay.setVisibility(View.GONE);
        Toast.makeText(this, "Errore scatto: " + errorMessage, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onError(String errorMessage) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onOrientationChanged(float roll, float pitch) {
        overlayView.updateOrientation(roll, pitch);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationSensorHelper != null) {
            orientationSensorHelper.start();
        }
        if (cameraController != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraController.startCamera();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(exposureTickerRunnable);
        if (orientationSensorHelper != null) {
            orientationSensorHelper.stop();
        }
        if (cameraController != null) {
            cameraController.stopCamera();
        }
        super.onPause();
    }
}