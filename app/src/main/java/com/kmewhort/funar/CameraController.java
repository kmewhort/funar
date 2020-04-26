package com.kmewhort.funar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

import com.kmewhort.funar.R;
import com.kmewhort.funar.CameraSelector;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

// adapted from https://inducesmile.com/android/android-camera2-api-example-tutorial/
public class CameraController extends AppCompatActivity {
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
    private StereoImageProcessor mStereoImageProcessor;

    private boolean mBitmapConsumed;
    private int mFrameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // load OpenCV
        OpenCVLoader.initDebug();

        // load stereo image processor (initializes RenderScript)
        mStereoImageProcessor = new StereoImageProcessor(this.getApplicationContext());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_layout);
        mainView = (ImageView) findViewById(R.id.main_view);
        assert mainView != null;

        // physical camera stream config
        mImageReaders = new ArrayList<>();
        mOutputConfigs = new ArrayList<>();
        mListeners = new ArrayList<>();
        mSurfaces = new ArrayList<>();

        mFrameCount = 0;

        openCamera();
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

            ImageReader reader = ImageReader.newInstance(
                    imageDimension.getWidth(),
                    imageDimension.getHeight(),
                    ImageFormat.DEPTH16,
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
                Image img = null;
                img = reader.acquireLatestImage();
                if (img == null) // not sure why this happens
                    return;

                Bitmap imgBitmap = (new Depth16ImageProcessor(img)).contoursBmp();
                img.close();

                // show and re-capture
                showBitmap(imgBitmap);

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