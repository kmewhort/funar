package com.kmewhort.funar;

import android.media.Image;
import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.imgproc.Imgproc.cvtColor;

public class StereoImageProcessor {
    private static final String TAG = "StereoImageProcessor";
    public static Mat Yuv420888toRGB(Image image) {
        Mat rgb = null;
        try {
            // from https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb
            // NOTE: fastest method, but only works when underlying buffers are nv21
            byte[] nv21;
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            nv21 = new byte[ySize + uSize + vSize];

            //U and V are swapped
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            rgb = getYUV2Mat(image, nv21);
        } catch (Exception e) {
            Log.w(TAG, e.getMessage());
        }
        return rgb;
    }

    private static Mat getYUV2Mat(Image image, byte[] data) {
        Mat mYuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CV_8UC1);
        mYuv.put(0, 0, data);
        Mat mRGB = new Mat();
        cvtColor(mYuv, mRGB, Imgproc.COLOR_YUV2RGB_NV21, 3);
        return mRGB;
    }
}
