/*
 * Copyright 2015 Alexander Martinz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package alexander.martinz.onthego;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class OnTheGoService extends Service {
    private static final String TAG = "OnTheGoService";
    private static final boolean DEBUG = false;

    public static final int CAMERA_BACK = 0;
    public static final int CAMERA_FRONT = 1;

    private static final int ONTHEGO_NOTIFICATION_ID = 81333378;

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";

    private static final int NOTIFICATION_STARTED = 0;
    private static final int NOTIFICATION_RESTART = 1;
    private static final int NOTIFICATION_ERROR = 2;

    private final Handler mHandler = new Handler();
    private final Object mRestartObject = new Object();

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;

    private TextureView mTextureView;
    private Size mPreviewSize;

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            stopOnTheGo(false);
        }

    };

    private FrameLayout mOverlay;
    private NotificationManager mNotificationManager;

    public class OnTheGoBinder extends Binder {
        private final OnTheGoService mService;

        public OnTheGoBinder(OnTheGoService service) {
            mService = service;
        }

        public OnTheGoService getService() {
            return mService;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new OnTheGoBinder(this);
    }

    @Override
    public void onDestroy() {
        unregisterReceivers(false);
        resetViews();
        super.onDestroy();
    }

    private void registerReceivers(boolean isScreenOn) {
        if (!isScreenOn) {
            final IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(mScreenReceiver, screenFilter);
        }
    }

    private void unregisterReceivers(boolean isScreenOff) {
        if (!isScreenOff) {
            try {
                unregisterReceiver(mScreenReceiver);
            } catch (Exception ignored) { }
        }
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            synchronized (mRestartObject) {
                final String action = intent.getAction();
                if (action != null && !action.isEmpty()) {
                    logDebug("mScreenReceiver: " + action);
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        setupViews(true);
                        registerReceivers(true);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        unregisterReceivers(true);
                        resetViews();
                    }
                }
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logDebug("onStartCommand called");

        if (intent == null || !Utils.hasCamera(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        if (!TextUtils.isEmpty(action)) {
            logDebug("Action: " + action);
            if (action.equals(ACTION_START)) {
                startOnTheGo();
            } else if (action.equals(ACTION_STOP)) {
                stopOnTheGo(false);
            }
        } else {
            logDebug("Action is NULL or EMPTY!");
            stopOnTheGo(false);
        }

        return START_NOT_STICKY;
    }

    private void startOnTheGo() {
        if (mNotificationManager != null) {
            logDebug("Starting while active, stopping.");
            stopOnTheGo(false);
            return;
        }

        resetViews();
        registerReceivers(false);
        setupViews(false);

        createNotification(NOTIFICATION_STARTED);
    }

    private void stopOnTheGo(boolean shouldRestart) {
        unregisterReceivers(false);
        resetViews();

        // Cancel notification
        if (mNotificationManager != null) {
            mNotificationManager.cancelAll();
            mNotificationManager = null;
        }

        if (shouldRestart) {
            createNotification(NOTIFICATION_RESTART);
        }

        stopSelf();
    }

    public void restartOnTheGo() {
        synchronized (mRestartObject) {
            final boolean restartService = Settings.get(OnTheGoService.this)
                    .getBoolean(Settings.KEY_ONTHEGO_SERVICE_RESTART, true);
            if (restartService) {
                restartOnTheGoInternal();
            } else {
                stopOnTheGo(true);
            }
        }
    }

    private void restartOnTheGoInternal() {
        resetViews();
        mHandler.removeCallbacks(mRestartRunnable);
        mHandler.postDelayed(mRestartRunnable, 750);
    }

    private final Runnable mRestartRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mRestartObject) {
                setupViews(true);
            }
        }
    };

    public void setAlpha(float alpha) {
        if (mOverlay != null) {
            mOverlay.setAlpha(alpha);
        }
    }

    private void setUpCameraOutputs(int type, int width, int height, CameraManager manager) {
        final boolean hasFrontCamera = Utils.hasFrontCamera(this);

        mCameraId = null;
        String cameraIdBack = null;
        String cameraIdFront = null;
        try {
            for (String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics info = manager.getCameraCharacteristics(cameraId);

                boolean isFront = info.get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_FRONT;
                isFront = (hasFrontCamera && isFront);

                boolean isBack = info.get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_BACK;

                if (isBack) {
                    cameraIdBack = cameraId;
                } else if (isFront) {
                    cameraIdFront = cameraId;
                }
            }
        } catch (CameraAccessException cae) {
            mCameraId = null;
        }

        if (type == CAMERA_BACK) {
            mCameraId = cameraIdBack;
        } else if (type == CAMERA_FRONT) {
            mCameraId = cameraIdFront;
        }

        if (mCameraId == null) {
            return;
        }

        final CameraCharacteristics cam;
        try {
            cam = manager.getCameraCharacteristics(mCameraId);
        } catch (CameraAccessException cae) {
            return;
        }

        StreamConfigurationMap map = cam.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // For still image captures, we use the largest available size.
        final Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                new CompareSizesByArea());

        mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                width, height, largest);
    }

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    private void openCamera(int type, int width, int height) throws Exception {
        releaseCamera();

        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        setUpCameraOutputs(type, width, height, manager);
        configureTransform(width, height);

        if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Time out waiting to lock camera opening.");
        }
        manager.openCamera(mCameraId, mStateCallback, null);
    }

    private void setupViews(final boolean isRestarting) {
        logDebug("Setup Views, restarting: " + (isRestarting ? "true" : "false"));

        final int cameraType = Settings.get(this).getInt(Settings.KEY_ONTHEGO_CAMERA, 0);
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                try {
                    openCamera(cameraType, width, height);
                } catch (Exception exc) {
                    // Well, you cant have all in this life..
                    logDebug("Exception: " + exc.getMessage());
                    createNotification(NOTIFICATION_ERROR);
                    stopOnTheGo(true);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                configureTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                releaseCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });

        mOverlay = new FrameLayout(this);
        mOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
        );
        mOverlay.addView(mTextureView);

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION |
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                PixelFormat.TRANSLUCENT
        );
        wm.addView(mOverlay, params);

        final float alpha = Settings.get(this).getFloat(Settings.KEY_ONTHEGO_ALPHA, 0.5f);
        mOverlay.setAlpha(alpha);
    }

    private void resetViews() {
        releaseCamera();
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (mOverlay != null) {
            mOverlay.removeAllViews();
            wm.removeView(mOverlay);
            mOverlay = null;
        }
    }

    private void releaseCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int rotation = wm.getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private void createCameraPreviewSession() {
        try {
            createCameraPreviewSessionImpl();
        } catch (CameraAccessException cae) {

        }
    }

    private void createCameraPreviewSessionImpl() throws CameraAccessException {
        SurfaceTexture texture = mTextureView.getSurfaceTexture();

        // We configure the size of default buffer to be the size of camera preview we want.
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

        // This is the output Surface we need to start preview.
        Surface surface = new Surface(texture);

        final ArrayList<Surface> surfaces = new ArrayList<>(1);
        surfaces.add(surface);

        // We set up a CaptureRequest.Builder with the output Surface.
        final CaptureRequest.Builder previewRequestBuilder
                = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewRequestBuilder.addTarget(surface);

        // Here, we create a CameraCaptureSession for camera preview.
        mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                    @Override
                    public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                        // The camera is already closed
                        if (null == mCameraDevice) {
                            return;
                        }

                        // When the session is ready, we start displaying the preview.
                        mCaptureSession = cameraCaptureSession;
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            // Flash is automatically enabled when necessary.
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                            // Finally, we start displaying the camera preview.
                            final CaptureRequest previewRequest = previewRequestBuilder.build();
                            mCaptureSession.setRepeatingRequest(previewRequest, null, null);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        // showToast("Failed");
                    }
                }, null
        );
    }

    private void createNotification(final int type) {
        final Intent i = new Intent(this, OnTheGoDialog.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 1000, i,
                PendingIntent.FLAG_UPDATE_CURRENT);

        final Resources r = getResources();
        final Notification.Builder builder = new Notification.Builder(this)
                .setContentIntent(pendingIntent)
                .setTicker(r.getString(
                        (type == 1 ? R.string.onthego_notif_camera_changed :
                                (type == 2 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_ticker))
                ))
                .setContentTitle(r.getString(
                        (type == 1 ? R.string.onthego_notif_camera_changed :
                                (type == 2 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_title))
                ))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setWhen(System.currentTimeMillis())
                .setOngoing(type != 2);

        final Notification notif = builder.build();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(ONTHEGO_NOTIFICATION_ID, notif);
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.e(TAG, msg);
        }
    }

}
