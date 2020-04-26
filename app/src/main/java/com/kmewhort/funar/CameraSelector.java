package com.kmewhort.funar;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.IntStream;

public class CameraSelector {
    private static final String TAG = "CameraSelector";
    private String mStereoCameraId = null;
    private String mDepthCameraId = null;
    private CameraManager mCameraManager;

    CameraSelector(CameraManager cameraManager) {
        mCameraManager = cameraManager;
    }

    public String stereoCameraId() {
        if (mStereoCameraId != null)
            return mStereoCameraId;

        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            for (int i = cameraIds.length - 1; i >= 0; i--) {
                String cameraId = cameraIds[i];

                Log.i(TAG, "Checking camera ID " + cameraId);
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
                int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean supportMulti = contains(capabilities, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
                Log.i(TAG, "Supports logical multi camera: " + supportMulti);

                if (supportMulti)
                    mStereoCameraId = cameraId;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return mStereoCameraId;
    }

    public String physicalCameraId(int i) {
        try {
            String logicalCameraId = this.stereoCameraId();
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(logicalCameraId);
            Set<String> physicalCameras = c.getPhysicalCameraIds();
            return (String)((physicalCameras.toArray())[i]);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return "";
    }

    public String depthCameraId() {
        if (mDepthCameraId != null)
            return mDepthCameraId;

        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            for (int i = cameraIds.length - 1; i >= 0; i--) {
                String cameraId = cameraIds[i];

                Log.i(TAG, "Checking camera ID " + cameraId);
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap configs =
                        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int[] outputFormats = configs.getOutputFormats();
                boolean hasDepth16 = contains(outputFormats, ImageFormat.DEPTH16);

                if (hasDepth16)
                    mDepthCameraId = cameraId;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return mDepthCameraId;
    }

    // for debugging camera support
    public void logDepthSupport() {
        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; i++) {
                String cameraId = cameraIds[i];

                Log.i(TAG, "Checking camera ID " + cameraId);
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
                int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);

                boolean supportDepth = contains(capabilities, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT);
                Log.i(TAG, "Supports depth:" + supportDepth);
                StreamConfigurationMap configs =
                        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int[] outputFormats = configs.getOutputFormats();

                boolean hasDepth16 = contains(outputFormats, ImageFormat.DEPTH16);
                if (hasDepth16) {
                    Size[] depthSizes = configs.getOutputSizes(ImageFormat.DEPTH16);
                    if (depthSizes != null) {
                        for (Size depthSize : depthSizes) {
                            Log.i(TAG, "Supports DEPTH16 - " + depthSize.getWidth() + " x " + depthSize.getHeight());
                        }
                    }
                }

                boolean hasDepthPointCloud = contains(capabilities, ImageFormat.DEPTH_POINT_CLOUD);
                if (hasDepthPointCloud) {
                    Size[] depthSizes = configs.getOutputSizes(ImageFormat.DEPTH_POINT_CLOUD);
                    if (depthSizes != null) {
                        for (Size depthSize : depthSizes) {
                            Log.i(TAG, "Supports DEPTH_POINT_CLOUD - " + depthSize.getWidth() + " x " + depthSize.getHeight());
                        }
                    }
                }

                boolean hasDepthJpeg = contains(outputFormats, ImageFormat.DEPTH_JPEG);
                if (hasDepthJpeg) {
                    Size[] depthSizes = configs.getOutputSizes(ImageFormat.DEPTH_JPEG);
                    if (depthSizes != null) {
                        for (Size depthSize : depthSizes) {
                            Log.i(TAG, "Supports DEPTH_JPEG - " + depthSize.getWidth() + " x " + depthSize.getHeight());
                        }
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private boolean contains(final int[] array, final int key) {
        // can't use array stream until Java 8, so do it the old fashioned way
        for (final int i : array) {
            if (i == key) {
                return true;
            }
        }
        return false;
    }
}
