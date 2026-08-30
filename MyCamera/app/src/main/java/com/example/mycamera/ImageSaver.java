package com.example.mycamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageSaver implements Runnable {

    private static final String TAG = "ImageSaver";

    private final Context context;
    private final byte[] jpegBytes;
    private final long exposureTimeNs;
    private final int iso;
    private final float focusDistance;
    private final int orientationDegrees;
    private final ImageSaveCallback callback;

    public interface ImageSaveCallback {
        void onSuccess(Uri imageUri, Bitmap thumbnail);
        void onError(Exception e);
    }

    private final Bitmap inputBitmap;

    public ImageSaver(Context context, Image image, long exposureTimeNs, int iso,
                      float focusDistance, int orientationDegrees, ImageSaveCallback callback) {
        this.context = context;
        this.exposureTimeNs = exposureTimeNs;
        this.iso = iso;
        this.focusDistance = focusDistance;
        this.orientationDegrees = orientationDegrees;
        this.callback = callback;
        this.inputBitmap = null;

        // Extract bytes before image is closed
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        this.jpegBytes = new byte[buffer.remaining()];
        buffer.get(jpegBytes);
    }

    public ImageSaver(Context context, Bitmap bitmap, long exposureTimeNs, int iso,
                      float focusDistance, int orientationDegrees, ImageSaveCallback callback) {
        this.context = context;
        this.inputBitmap = bitmap;
        this.exposureTimeNs = exposureTimeNs;
        this.iso = iso;
        this.focusDistance = focusDistance;
        this.orientationDegrees = orientationDegrees;
        this.callback = callback;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        this.jpegBytes = baos.toByteArray();
    }

    @Override
    public void run() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "ASTRO_" + timeStamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroNightCamera");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            if (callback != null) {
                callback.onError(new Exception("Impossibile creare il file multimediale."));
            }
            return;
        }

        try {
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os != null) {
                    os.write(jpegBytes);
                    os.flush();
                }
            }

            // Write custom EXIF tags if applicable
            try (android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw")) {
                if (pfd != null) {
                    ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                    if (exposureTimeNs > 0) {
                        double seconds = exposureTimeNs / 1_000_000_000.0;
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.valueOf(seconds));
                    }
                    if (iso > 0) {
                        exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, String.valueOf(iso));
                    }
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Scattata con AstroNight Camera - Esposizione Manuale");
                    exif.saveAttributes();
                }
            } catch (Exception e) {
                Log.w(TAG, "Impossibile scrivere EXIF extra: " + e.getMessage());
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }

            // Generate thumbnail for in-app preview
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 8; // Downsample for memory efficiency
            Bitmap rawThumb = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            Bitmap finalThumb = rawThumb;

            if (rawThumb != null && orientationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(orientationDegrees);
                finalThumb = Bitmap.createBitmap(rawThumb, 0, 0, rawThumb.getWidth(), rawThumb.getHeight(), matrix, true);
            }

            if (callback != null) {
                callback.onSuccess(uri, finalThumb);
            }

        } catch (Exception e) {
            Log.e(TAG, "Errore durante il salvataggio dell'immagine", e);
            if (callback != null) {
                callback.onError(e);
            }
        }
    }
}
