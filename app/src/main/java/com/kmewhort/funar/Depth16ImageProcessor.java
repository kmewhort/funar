package com.kmewhort.funar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
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

public class Depth16ImageProcessor {
    Image mImage;
    Mat mMat;
    boolean mNormalize;

    public Depth16ImageProcessor(Image img) {
        mImage = img;

        // load the buffers and convert to OpenCV
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        Mat raw = new Mat(img.getHeight(), img.getWidth(), CV_16UC1, buffer);

        // first three bits are the confidence - mask them out
        mMat = new Mat();
        bitwise_and(raw, (new Mat(1, 1, CV_16UC1, new Scalar(0x1FFF))), mMat);

        mNormalize = true;
    }

    public Mat gray16() {
        return mMat;
    }

    public Mat gray8() {
        Mat gray8 = new Mat(mMat.height(), mMat.width(), CV_8U);
        if(mNormalize)
          normalize(gray16(), gray8, 0, 255, NORM_MINMAX, CV_8U);
        else
          gray16().convertTo(gray8, CV_8U, 1/256.0);
        return gray8;
    }

    public Bitmap gray8Bmp() {
        Mat rgbMat = new Mat();
        Imgproc.cvtColor(gray8(), rgbMat, Imgproc.COLOR_GRAY2RGBA);
        Bitmap resultBmp = Bitmap.createBitmap(rgbMat.width(), rgbMat.height(), ARGB_8888);
        Utils.matToBitmap(rgbMat, resultBmp);
        return resultBmp;
    }

    public Bitmap colormap8Bmp() {
        Mat rgbMat = new Mat();
        Imgproc.applyColorMap(gray8(), rgbMat, Imgproc.COLORMAP_JET);
        Bitmap resultBmp = Bitmap.createBitmap(rgbMat.width(), rgbMat.height(), ARGB_8888);
        Utils.matToBitmap(rgbMat, resultBmp);
        return resultBmp;
    }

    public Mat contours() {
        // TODO: use gray16 for generating contours; and without normalization?
        Mat gray = gray8();

        Mat result = new Mat(mMat.height(), mMat.width(), CV_8UC3);

        // start with a colormap
        Imgproc.applyColorMap(gray, result, Imgproc.COLORMAP_JET);

        // for each spread of 8 between 0 and 256
        for(int i = 0; i < 32; i++) {
            Mat threshold = new Mat();
            Imgproc.threshold(gray, threshold, i * 8, (i + 1) * 8, 0);

            List<MatOfPoint> contours = new ArrayList();
            Mat hierarchy = new Mat();
            Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

            // draw all contours
            Imgproc.drawContours(result, contours, -1, new Scalar(0, 255, 0), 1);
        }
        return result;
    }

    public Bitmap contoursBmp() {
        Mat mat = contours();
        Bitmap resultBmp = Bitmap.createBitmap(mat.width(), mat.height(), ARGB_8888);
        Utils.matToBitmap(mat, resultBmp);
        return resultBmp;
    }

    public Bitmap gray8BmpBruteForce() {
        // just for a sanity check....based on https://android.googlesource.com/platform/pdk/+/e148126c8e537755afcfe7c85db15bfc84fa9461/apps/TestingCamera2/src/com/android/testingcamera2/ImageReaderSubPane.java
        ShortBuffer y16Buffer = mImage.getPlanes()[0].getBuffer().asShortBuffer();
        y16Buffer.rewind();
        int w = mImage.getWidth();
        int h = mImage.getHeight();
        int stride = mImage.getPlanes()[0].getRowStride();
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

    public void setNormalize(boolean norm) {
        mNormalize = norm;
    }

    public boolean getNormalize() {
        return mNormalize;
    }
}
