package com.example.mycamera;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import androidx.appcompat.app.AppCompatActivity;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 1800L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isNavigating = false;

    private final Runnable navigateRunnable = this::launchMainActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        animateSplashScreen();

        // Tap to skip delay immediately
        findViewById(R.id.layoutSplashRoot).setOnClickListener(v -> launchMainActivity());

        handler.postDelayed(navigateRunnable, SPLASH_DELAY_MS);
    }

    private void animateSplashScreen() {
        View centerBrand = findViewById(R.id.layoutCenterBrand);
        View bottomSection = findViewById(R.id.layoutSplashBottom);

        if (centerBrand != null) {
            centerBrand.setAlpha(0f);
            centerBrand.setScaleX(0.85f);
            centerBrand.setScaleY(0.85f);
            centerBrand.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(900)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        }

        if (bottomSection != null) {
            bottomSection.setAlpha(0f);
            bottomSection.setTranslationY(20f);
            bottomSection.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(700)
                    .setStartDelay(400)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private synchronized void launchMainActivity() {
        if (isNavigating) return;
        isNavigating = true;
        handler.removeCallbacks(navigateRunnable);

        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(navigateRunnable);
        super.onDestroy();
    }
}