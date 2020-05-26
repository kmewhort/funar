package com.kmewhort.funar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.Type;

import org.opencv.android.Utils;
import org.opencv.calib3d.StereoSGBM;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.bitwise_and;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.CvType.CV_16UC1;
import static org.opencv.core.CvType.CV_8U;
import static org.opencv.core.CvType.CV_8UC3;

public class Depth16ImageProcessor implements ImagePreprocessor {
    Mat mMat;
    boolean mNormalize;

    public Mat process(Image img) {
        // load the buffers and convert to OpenCV
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        Mat raw = new Mat(img.getHeight(), img.getWidth(), CV_16UC1, buffer);
        img.close();

        return process(raw);
    }

    public Mat process(Mat mat) {
        // first three bits are the confidence - mask them out
        mMat = new Mat();
        bitwise_and(mat, (new Mat(1, 1, CV_16UC1, new Scalar(0x1FFF))), mMat);

        return gray16();
    }

    public void recallibrate() {}

    public boolean isCallibrated() {
        return true;
    }

    public void setAutoCallibrate(boolean enable) {
        // not implemented
    }

    public boolean getAutoCallibrate() {
        return false;
    }

    public Mat getCallibration() { return null; }
    public void setCallibration(Mat warpMat) {};

    public int requiredInputFormat() {
        return ImageFormat.DEPTH16;
    }

    protected Mat gray16() {
        return mMat;
    }

    protected Bitmap gray8BmpBruteForce(Image image) {
        // just for a sanity check....based on https://android.googlesource.com/platform/pdk/+/e148126c8e537755afcfe7c85db15bfc84fa9461/apps/TestingCamera2/src/com/android/testingcamera2/ImageReaderSubPane.java
        ShortBuffer y16Buffer = image.getPlanes()[0].getBuffer().asShortBuffer();
        y16Buffer.rewind();
        int w = image.getWidth();
        int h = image.getHeight();
        int stride = image.getPlanes()[0].getRowStride();
        short[] yRow = new short[w];
        int[] imgArray = new int[w * h];
        for (int y = 0, j = 0; y < h; y++) {
            // Align to start of red row in the pair to sample from
            y16Buffer.position(y * stride/2);
            y16Buffer.get(yRow);
            for (int x = 0; x < w; x++) {
                int d = (yRow[x] >> 8) & 0xFF;
                imgArray[j++] = Color.rgb(d,d,d);
            }
        }
        Bitmap result = Bitmap.createBitmap(imgArray, w, h, Bitmap.Config.ARGB_8888);
        return result;
    }
}
