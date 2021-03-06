package com.kmewhort.funar;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.util.ArrayList;
import java.util.List;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import static android.graphics.Bitmap.Config.ARGB_8888;

// adapted from https://inducesmile.com/android/android-camera2-api-example-tutorial/
public class CameraController extends MainFullscreenActivityBase {
    private static final String TAG = "CameraController";
    private ImageView mainView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private CameraSelector mCameraSelector;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession mCaptureSession;
    protected CameraCaptureSession.CaptureCallback mCaptureListener;
    protected CaptureRequest mCaptureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean mFlashSupported;

    List<ImageReader> mImageReaders;
    List<OutputConfiguration> mOutputConfigs;
    List<ImageReader.OnImageAvailableListener> mListeners;
    List<Surface> mSurfaces;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private HandlerExecutor mBackgroundExecutor;

    private boolean mBitmapConsumed;
    private int mFrameCount;

    private EffectRunner mEffectRunner;
    private int mCurrentInputFormat;

    private boolean mUserRequestedArCoreInstall = true;
    private Session mArCoreSession;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // load OpenCV
        OpenCVLoader.initDebug();

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        super.onCreate(savedInstanceState);

        mainView = (ImageView) findViewById(R.id.fullscreen_content);
        assert mainView != null;

        // physical camera stream config
        mImageReaders = new ArrayList<>();
        mOutputConfigs = new ArrayList<>();
        mListeners = new ArrayList<>();
        mSurfaces = new ArrayList<>();

        mFrameCount = 0;

        mEffectRunner = new EffectRunner();

        openCamera();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        refreshOptionsMenuDepthItems(menu);
        return true;
    }

    protected void refreshOptionsMenuDepthItems(Menu menu) {
        // update visibility based on whether depth callibration supported
        boolean visible = mEffectRunner.supportsDepthCallibration();
        menu.findItem(R.id.min_depth_increase_meter).setVisible(visible);
        menu.findItem(R.id.min_depth_increase_decimeter).setVisible(visible);
        menu.findItem(R.id.min_depth_decrease_decimeter).setVisible(visible);
        menu.findItem(R.id.min_depth_decrease_meter).setVisible(visible);
        menu.findItem(R.id.max_depth_increase_meter).setVisible(visible);
        menu.findItem(R.id.max_depth_increase_decimeter).setVisible(visible);
        menu.findItem(R.id.max_depth_decrease_decimeter).setVisible(visible);
        menu.findItem(R.id.max_depth_decrease_meter).setVisible(visible);

        // update the labels to show the current depth callibration (TODO: strings)
        menu.findItem(R.id.min_depth_increase_meter).setTitle("+1m min depth    [" + String.format("%1.1f", mEffectRunner.getCallibratedMinDepth()) + "]");
        menu.findItem(R.id.max_depth_increase_meter).setTitle("+1m max depth    [" + String.format("%1.1f",mEffectRunner.getCallibratedMaxDepth()) + "]");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.callibrate:
                hide();
                mEffectRunner.recallibrate();
                break;
            case R.id.toggle_auto_callibrate:
                if(mEffectRunner.getAutoCallibrate()) {
                    mEffectRunner.setAutoCallibrate(false);
                    item.setTitle(getString(R.string.enable_auto_callibrate));
                } else {
                    mEffectRunner.setAutoCallibrate(true);
                    item.setTitle(getString(R.string.disable_auto_callibrate));
                }
                break;
            case R.id.min_depth_increase_meter:
                mEffectRunner.setCallibratedMinDepth(mEffectRunner.getCallibratedMinDepth()+1.0);
                invalidateOptionsMenu();
                break;
            case R.id.min_depth_increase_decimeter:
                mEffectRunner.setCallibratedMinDepth(mEffectRunner.getCallibratedMinDepth()+0.1);
                invalidateOptionsMenu();
                break;
            case R.id.min_depth_decrease_meter:
                mEffectRunner.setCallibratedMinDepth(mEffectRunner.getCallibratedMinDepth()-1.0);
                invalidateOptionsMenu();
                break;
            case R.id.min_depth_decrease_decimeter:
                mEffectRunner.setCallibratedMinDepth(mEffectRunner.getCallibratedMinDepth()-0.1);
                invalidateOptionsMenu();
                break;
            case R.id.max_depth_increase_meter:
                mEffectRunner.setCallibratedMaxDepth(mEffectRunner.getCallibratedMaxDepth()+1.0);
                invalidateOptionsMenu();
                break;
            case R.id.max_depth_increase_decimeter:
                mEffectRunner.setCallibratedMaxDepth(mEffectRunner.getCallibratedMaxDepth()+0.1);
                invalidateOptionsMenu();
                break;
            case R.id.max_depth_decrease_meter:
                mEffectRunner.setCallibratedMaxDepth(mEffectRunner.getCallibratedMaxDepth()-1.0);
                invalidateOptionsMenu();
                break;
            case R.id.max_depth_decrease_decimeter:
                mEffectRunner.setCallibratedMaxDepth(mEffectRunner.getCallibratedMaxDepth()-0.1);
                invalidateOptionsMenu();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    protected void onLeftSwipe() {
        mEffectRunner.prevEffectGroup();
        invalidateOptionsMenu();
        Toast.makeText(this, mEffectRunner.currentEffectName(), Toast.LENGTH_SHORT).show();
    }

    protected void onRightSwipe() {
        mEffectRunner.nextEffectGroup();
        invalidateOptionsMenu();
        Toast.makeText(this, mEffectRunner.currentEffectName(), Toast.LENGTH_SHORT).show();

    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            startCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundExecutor = new HandlerExecutor(mBackgroundHandler);
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        //mBackgroundThread.join();
        mBackgroundThread = null;
        mBackgroundHandler = null;
        mBackgroundExecutor = null;
    }

    protected void startCaptureSession() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());

            // physical camera readers
            mImageReaders.clear();
            mOutputConfigs.clear();
            mListeners.clear();
            mSurfaces.clear();

            // request builder to which to add the surfaces we're requesting
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);

            mCurrentInputFormat = mEffectRunner.requiredInputFormat();
            ImageReader reader = ImageReader.newInstance(
                    imageDimension.getWidth(),
                    imageDimension.getHeight(),
                    mCurrentInputFormat,
                    1
            );

            OutputConfiguration config = new OutputConfiguration(reader.getSurface());

            ImageReader.OnImageAvailableListener readerListener = readerListenerFactory();
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

            mImageReaders.add(reader);
            mOutputConfigs.add(config);
            mListeners.add(readerListener);
            mSurfaces.add(reader.getSurface());

            captureRequestBuilder.addTarget(reader.getSurface());

            mCaptureRequest = captureRequestBuilder.build();

            mCaptureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                }
            };

            final CameraCaptureSession.StateCallback stateListener = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        mCaptureSession = session;

                        mCaptureSession.capture(mCaptureRequest, mCaptureListener, mBackgroundHandler);
                        //session.setRepeatingRequest(requestBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            };

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    mOutputConfigs,
                    mBackgroundExecutor,
                    stateListener);
            cameraDevice.createCaptureSession(sessionConfig);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private ImageReader.OnImageAvailableListener readerListenerFactory() {
        return new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // if our required input format has changed, abandon this frame and restart
                // the camera capture
                if(mEffectRunner.requiredInputFormat() != mCurrentInputFormat) {
                    startCaptureSession();
                    return;
                }

                Image img = null;
                img = reader.acquireLatestImage();
                if (img == null) // not sure why this happens
                    return;

                Mat output = mEffectRunner.process(img);

                // show and re-capture
                if(output != null) {
                    Bitmap resultBmp = Bitmap.createBitmap(output.width(), output.height(), ARGB_8888);
                    Utils.matToBitmap(output, resultBmp);
                    showBitmap(resultBmp);
                }

                try {
                    mCaptureSession.capture(mCaptureRequest, mCaptureListener, mBackgroundHandler);
                    //mCaptureSession.capture(captureRequestBuilder.build(), mCaptureListener, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                } catch (IllegalStateException e) {
                    // camera already closed
                    e.printStackTrace();
                }
            }

        };
    }

    private void showBitmap(Bitmap bitmap) {
        /* TODO: TextureView not working, using Image View for now
        Rect rc = new Rect();
        Canvas c = mainView.draw
        rc.set(0, 0, imageDimension.getWidth(), imageDimension.getHeight());
        c.drawBitmap(imgBitmap, 0, 0, null);
        textureView.unlockCanvasAndPost(c);
        */

        mBitmapConsumed = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mainView.setImageBitmap(bitmap);
                mBitmapConsumed = true;
            }
        });
        while (!mBitmapConsumed) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            mCameraSelector = new CameraSelector(manager);
            String cameraId = mCameraSelector.depthCameraId();
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(CameraController.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }

        for(int i = 0; i < mImageReaders.size(); i++) {
            mImageReaders.get(i).close();
        }
        mImageReaders.clear();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(CameraController.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");

        // Make sure Google Play Services for AR is installed and up to date.
        try {
            if (mArCoreSession == null) {
                switch (ArCoreApk.getInstance().requestInstall(this, mUserRequestedArCoreInstall)) {
                    case INSTALLED:
                        // Success, create the AR session.
                        mArCoreSession = new Session(this);
                        break;
                    case INSTALL_REQUESTED:
                        // Ensures next invocation of requestInstall() will either return
                        // INSTALLED or throw an exception.
                        mUserRequestedArCoreInstall = false;
                        return;
                }
            }
        } catch (UnavailableUserDeclinedInstallationException | UnavailableDeviceNotCompatibleException | UnavailableArcoreNotInstalledException | UnavailableApkTooOldException | UnavailableSdkTooOldException e) {
            e.printStackTrace();
            return;
        }

        startBackgroundThread();
        openCamera();
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}