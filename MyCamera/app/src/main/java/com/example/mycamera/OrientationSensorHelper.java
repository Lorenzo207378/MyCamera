package com.example.mycamera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class OrientationSensorHelper implements SensorEventListener {

    public interface OrientationListener {
        void onOrientationChanged(float roll, float pitch);
    }

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final OrientationListener listener;

    private final float[] gravityValues = new float[3];
    private final float[] magneticValues = new float[3];
    private boolean hasGravity = false;
    private boolean hasMagnetic = false;

    private float smoothedRoll = 0f;
    private float smoothedPitch = 0f;

    public OrientationSensorHelper(Context context, OrientationListener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationVectorSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        this.accelerometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.magnetometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
    }

    public void start() {
        if (sensorManager == null) return;

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            if (magnetometer != null) {
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotationMatrix = new float[9];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            float[] orientation = new float[3];
            SensorManager.getOrientation(rotationMatrix, orientation);

            // Convert to degrees
            float pitch = (float) Math.toDegrees(orientation[1]);
            float roll = (float) Math.toDegrees(orientation[2]);

            dispatchOrientation(roll, pitch);

        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravityValues, 0, 3);
            hasGravity = true;
            calculateOrientationFallback();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magneticValues, 0, 3);
            hasMagnetic = true;
            calculateOrientationFallback();
        }
    }

    private void calculateOrientationFallback() {
        if (hasGravity && hasMagnetic) {
            float[] r = new float[9];
            float[] i = new float[9];
            if (SensorManager.getRotationMatrix(r, i, gravityValues, magneticValues)) {
                float[] orientation = new float[3];
                SensorManager.getOrientation(r, orientation);
                float pitch = (float) Math.toDegrees(orientation[1]);
                float roll = (float) Math.toDegrees(orientation[2]);
                dispatchOrientation(roll, pitch);
            }
        }
    }

    private void dispatchOrientation(float roll, float pitch) {
        // Apply low pass filter for smooth UI
        smoothedRoll = smoothedRoll + 0.15f * (roll - smoothedRoll);
        smoothedPitch = smoothedPitch + 0.15f * (pitch - smoothedPitch);

        if (listener != null) {
            listener.onOrientationChanged(smoothedRoll, smoothedPitch);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
