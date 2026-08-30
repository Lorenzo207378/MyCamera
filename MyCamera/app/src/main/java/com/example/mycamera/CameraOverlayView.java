package com.example.mycamera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class CameraOverlayView extends View {

    private boolean showGrid = false;
    private boolean showLevel = true;

    // Grid paint
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Horizon level paints
    private final Paint horizonLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Focus ring paint
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Orientation sensor angles
    private float rollAngle = 0f;
    private float pitchAngle = 0f;

    // Focus touch animation
    private float focusTouchX = -1f;
    private float focusTouchY = -1f;
    private float focusRadius = 0f;
    private int focusAlpha = 0;
    private ValueAnimator focusAnimator;

    public CameraOverlayView(Context context) {
        super(context);
        init();
    }

    public CameraOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CameraOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.argb(80, 255, 255, 255));
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);

        horizonLinePaint.setStrokeWidth(4f);
        horizonLinePaint.setStyle(Paint.Style.STROKE);

        horizonCenterPaint.setStyle(Paint.Style.FILL);

        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(3.5f);
        focusPaint.setColor(0xFF00E5FF);
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        invalidate();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setShowLevel(boolean showLevel) {
        this.showLevel = showLevel;
        invalidate();
    }

    public boolean isShowLevel() {
        return showLevel;
    }

    public void updateOrientation(float roll, float pitch) {
        this.rollAngle = roll;
        this.pitchAngle = pitch;
        if (showLevel) {
            invalidate();
        }
    }

    public void showFocusRing(float x, float y) {
        this.focusTouchX = x;
        this.focusTouchY = y;
        if (focusAnimator != null && focusAnimator.isRunning()) {
            focusAnimator.cancel();
        }

        focusAnimator = ValueAnimator.ofFloat(1.5f, 1.0f);
        focusAnimator.setDuration(400);
        focusAnimator.setInterpolator(new DecelerateInterpolator());
        focusAnimator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            focusRadius = 45f * val * getResources().getDisplayMetrics().density;
            focusAlpha = (int) (255 * (animation.getAnimatedFraction()));
            invalidate();
        });
        focusAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Fade out after a moment
                postDelayed(() -> {
                    ValueAnimator fadeOut = ValueAnimator.ofInt(255, 0);
                    fadeOut.setDuration(300);
                    fadeOut.addUpdateListener(anim -> {
                        focusAlpha = (int) anim.getAnimatedValue();
                        invalidate();
                    });
                    fadeOut.start();
                }, 1000);
            }
        });
        focusAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        // Draw 3x3 Grid (Rule of Thirds)
        if (showGrid) {
            float x1 = width / 3f;
            float x2 = 2 * width / 3f;
            float y1 = height / 3f;
            float y2 = 2 * height / 3f;

            canvas.drawLine(x1, 0, x1, height, gridPaint);
            canvas.drawLine(x2, 0, x2, height, gridPaint);
            canvas.drawLine(0, y1, width, y1, gridPaint);
            canvas.drawLine(0, y2, width, y2, gridPaint);
        }

        // Draw Horizon / Level Indicator for Tripod Astrophotography
        if (showLevel) {
            float centerX = width / 2f;
            float centerY = height / 2f;
            float lineLength = 120f * getResources().getDisplayMetrics().density;

            boolean isLevel = Math.abs(rollAngle) < 1.5f;
            int levelColor = isLevel ? 0xFF00E676 : 0xFFFFAB40;

            horizonLinePaint.setColor(levelColor);
            horizonCenterPaint.setColor(levelColor);

            canvas.save();
            canvas.translate(centerX, centerY);
            canvas.rotate(-rollAngle);

            // Left tick and right tick
            canvas.drawLine(-lineLength, 0, -lineLength / 3, 0, horizonLinePaint);
            canvas.drawLine(lineLength / 3, 0, lineLength, 0, horizonLinePaint);

            // Center target dot
            canvas.drawCircle(0, 0, 6f, horizonCenterPaint);

            canvas.restore();
        }

        // Draw Touch Focus Ring
        if (focusAlpha > 0 && focusTouchX >= 0 && focusTouchY >= 0) {
            focusPaint.setAlpha(focusAlpha);
            float halfSide = focusRadius;
            RectF rect = new RectF(
                    focusTouchX - halfSide,
                    focusTouchY - halfSide,
                    focusTouchX + halfSide,
                    focusTouchY + halfSide
            );
            canvas.drawRoundRect(rect, 12f, 12f, focusPaint);
        }
    }
}
