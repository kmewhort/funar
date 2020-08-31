package com.kmewhort.funar.preprocessors;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

import static org.opencv.core.Core.divide;
import static org.opencv.core.Core.extractChannel;
import static org.opencv.core.Core.multiply;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.Core.subtract;

public class DepthJpegProcessor extends ImagePreprocessor {
    protected Bitmap mRgbBitmap;
    protected Mat mDepthMat;
    protected byte[] mImageData;

    protected double mNear; // in meters
    protected double mFar;
    protected boolean mCallibrated;
    protected int mCallibrationFrameCount;
    protected boolean mStaticCallibration;

    public DepthJpegProcessor(boolean staticCalliibration) {
        super();

        mStaticCallibration = staticCalliibration;
        recallibrate();
    }

    public int requiredInputFormat() {
        return ImageFormat.DEPTH_JPEG;
    }

    public Mat process(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        mImageData = new byte[buffer.remaining()];
        buffer.get(mImageData);
        img.close();

        return process(mImageData);
    }

    public Mat process(byte[] rawImageData) {
        mImageData = rawImageData;
        decodeDepthImage();
        return mDepthMat;
    }

    public byte[] getRawImageData() {
        return mImageData;
    }

    @Override
    public boolean supportsDepthCallibration() {
        return mStaticCallibration;
    }

    @Override
    public double getCallibratedMinDepth() {
        return mNear;
    }

    @Override
    public void setCallibratedMinDepth(double depth) {
        mNear = depth;
    }

    @Override
    public double getCallibratedMaxDepth() {
        return mFar;
    }

    @Override
    public void setCallibratedMaxDepth(double depth) {
       mFar = depth;
    }

    private void decodeDepthImage() {
        try {
            JpegParser parser = new JpegParser(mImageData);
            ByteBuffer depthJpeg = parser.getDepthMap();
            Bitmap bitmap = BitmapFactory.decodeByteArray(depthJpeg.array(), depthJpeg.arrayOffset(), depthJpeg.limit());
            mDepthMat = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3);
            Utils.bitmapToMat(bitmap, mDepthMat);
            extractChannel(mDepthMat, mDepthMat, 0);

            if(mCallibrated) {
                mDepthMat.convertTo(mDepthMat, CvType.CV_32FC1);
                // unwrap the RangeInverse values to meters
                // (see https://developer.android.com/training/camera2/Dynamic-depth-v1.0.pdf p. 39)
                // TODO: check values are still 255 based and not already normalized to 1.0
                double curNear = parser.getDepthNearValue();
                double curFar = parser.getDepthFarValue();
                Core.multiply(mDepthMat, new Scalar(-1.0*(curFar-curNear)/255.0), mDepthMat);
                Core.add(mDepthMat, new Scalar(curFar), mDepthMat);
                Core.divide(curFar*curNear, mDepthMat, mDepthMat);

                // renormalize either to the current near/far, or the callibrated one
                double calFar = mStaticCallibration ? mFar : curFar;
                double calNear = mStaticCallibration ? mNear : curNear;

                // truncate values exceeding the callibrated far
                Imgproc.threshold(mDepthMat, mDepthMat, calFar, 0, Imgproc.THRESH_TRUNC);

                // re-normalize between 0 and 255, truncating values below the callibrated min
                // at the same time
                Core.subtract(mDepthMat, new Scalar(calNear), mDepthMat);
                Imgproc.threshold(mDepthMat, mDepthMat, 0, 0, Imgproc.THRESH_TOZERO);
                Core.multiply(mDepthMat, new Scalar(255.0/(calFar-calNear)), mDepthMat);
                mDepthMat.convertTo(mDepthMat, CvType.CV_8UC1);
            } else {
                // find the min/max distance over 10 frames
                if(parser.getDepthNearValue() < mNear)
                    mNear = parser.getDepthNearValue();
                if(parser.getDepthFarValue() > mFar)
                    mFar = parser.getDepthFarValue();
                if(++mCallibrationFrameCount >= 10) {
                    mCallibrated = true;
                }
            }
        } catch (JpegParser.JpegMarkerNotFound jpegMarkerNotFound) {
            jpegMarkerNotFound.printStackTrace();
        } catch (JpegParser.DepthImageNotFound depthImageNotFound) {
            depthImageNotFound.printStackTrace();
        }
    }

    public void recallibrate() {
        mNear = 10000;
        mFar = 0;
        mCallibrated = false;
        mCallibrationFrameCount = 0;
    }

    public boolean isCallibrated() {
        return mCallibrated;
    }
}
